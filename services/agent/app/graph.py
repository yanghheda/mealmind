"""决策编排图（LangGraph StateGraph，技术方案 §6）。

节点边界与 SSE 事件一一对应：
    understand  -> understand        理解需求（规则通道）
    filter      -> （不发事件）        等待业务服务回填约束过滤结果（协作式编排）
    score       -> scoring / card     逐菜分批打分
    phrase      -> reason.delta       模板理由（事实全部来自输入）
过滤后候选为空或等待回填超时时，图直接结束；done / degraded / error 的收口由业务服务完成。
"""

from __future__ import annotations

import asyncio
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from .config import settings
from .degrade import DegradeLevel
from .models import Candidate, CandidateScore, Profile
from .phraser import phraser
from .runs import registry
from .scorer import ScoringContext, router
from .understander import understander

_SCORE_BATCH_SIZE = settings.batch_size


class DecisionState(TypedDict, total=False):
    run_id: str
    text: str
    mode: str
    merchant_id: str | None
    profile: Profile
    from_fallback: bool
    questions: list[str]
    candidates: list[Candidate] | None
    scored: list[tuple[Candidate, CandidateScore]]
    reasons: list[dict[str, Any]]


async def _understand(state: DecisionState) -> DecisionState:
    context = registry.get(state["run_id"])
    # 模型通道是同步 HTTP，放到线程里执行，避免阻塞事件循环（超时 2s、重试 1 次）
    result = await asyncio.to_thread(understander.parse, state["text"])
    if context:
        context.emit(
            "understand",
            {"profile": result.profile.model_dump(), "questions": [], "fromFallback": result.from_fallback},
        )
        if result.transition:
            context.emit("degraded", {"level": result.transition.to_level, "reason": result.transition.reason})
    return {"profile": result.profile, "from_fallback": result.from_fallback, "questions": []}


async def _filter(state: DecisionState) -> DecisionState:
    """过滤节点：由业务服务执行（预算 / 忌口 / 营业 / 时效），这里只等待回填。"""

    context = registry.get(state["run_id"])
    if context is None:
        return {"candidates": None}
    try:
        await asyncio.wait_for(
            context.candidates_ready.wait(), timeout=settings.continuation_timeout_s
        )
    except asyncio.TimeoutError:
        context.emit(
            "error",
            {
                "code": "FILTER_TIMEOUT",
                "message": "等待候选回填超时",
                "retryable": True,
            },
        )
        return {"candidates": None}
    return {"candidates": context.candidates or []}


def _route_after_filter(state: DecisionState) -> str:
    candidates = state.get("candidates")
    if not candidates:
        return "end"  # 为空：无候选提示由业务服务合成；回填超时已发 error
    return "score"


async def _score(state: DecisionState) -> DecisionState:
    context = registry.get(state["run_id"])
    candidates = state.get("candidates") or []
    profile = state["profile"]
    total = len(candidates)
    if context:
        context.emit("scoring", {"done": 0, "total": total, "mode": router.mode})

    max_sales = max((c.dishMonthlySales for c in candidates), default=0)
    scoring_context = ScoringContext(max_dish_sales=max_sales)
    scored: list[tuple[Candidate, CandidateScore]] = []
    primary_mode = router.primary_mode
    # FULL 档冷却期内不发探测（整轮走规则分）：这一轮既不算失败也不写回控制器。
    # 否则每轮都会因 mode≠primary 被判为「探测失败」而重置冷却——持续流量下永远无法自动恢复。
    attempted = primary_mode is not None and router.controller.should_attempt_primary()
    failed = 0

    for start in range(0, total, _SCORE_BATCH_SIZE):
        batch = candidates[start : start + _SCORE_BATCH_SIZE]
        results = await asyncio.gather(
            *(asyncio.to_thread(router.score, candidate, profile, scoring_context) for candidate in batch)
        )
        for candidate, score in zip(batch, results):
            # 主通道模式下该菜回退到规则分：计入本轮失败率，驱动降级档位迁移
            if attempted and score.mode != primary_mode:
                failed += 1
            scored.append((candidate, score))
            if context:
                context.emit("card", _card_payload(candidate, score))
        if context:
            context.emit("scoring", {"done": min(start + _SCORE_BATCH_SIZE, total), "total": total, "mode": router.mode})

    transition = router.controller.record_scoring(total, failed) if attempted else None
    if context:
        if transition:
            context.emit("degraded", {"level": transition.to_level, "reason": transition.reason})
        elif router.controller.level != DegradeLevel.NORMAL:
            # 无迁移但本轮仍处于降级档：同步当前档位，业务服务据此把本轮 run 记为降级
            context.emit("degraded",
                         {"level": router.controller.level, "reason": "维持降级档位"})

    return {"scored": scored}


async def _phrase(state: DecisionState) -> DecisionState:
    """理由生成：按批并发调用表达器，逐条 push（生成式失败或校验不通过回落模板）。"""

    context = registry.get(state["run_id"])
    profile = state["profile"]
    scored = state.get("scored") or []
    if not scored:
        return {"reasons": []}

    chunks = [scored[i : i + _SCORE_BATCH_SIZE] for i in range(0, len(scored), _SCORE_BATCH_SIZE)]
    results = await asyncio.gather(
        *(asyncio.to_thread(phraser.reasons, chunk, profile) for chunk in chunks)
    )
    by_dish: dict[str, str] = {}
    for part in results:
        by_dish.update(part)

    reasons: list[dict[str, Any]] = []
    for candidate, score in scored:
        # PhraserRouter 保证每条候选都有理由（生成失败或校验不过即模板兜底）
        text = by_dish[candidate.dishId]
        reasons.append({"dishId": candidate.dishId, "reason": text})
        if context:
            context.emit("reason.delta", {"dishId": candidate.dishId, "text": text})
    return {"reasons": reasons}


def _card_payload(candidate: Candidate, score) -> dict[str, Any]:
    return {
        "dishId": candidate.dishId,
        "name": candidate.name,
        "merchantId": candidate.merchantId,
        "merchantName": candidate.merchantName,
        "price": candidate.price,
        "imageUrl": candidate.imageUrl,
        "dims": [dim.model_dump() for dim in score.dims],
        "mode": score.mode,
        # 业务服务在转发时补齐综合分、置信度与并列标记（排序与合成属于业务服务）
    }


def build_graph():
    graph = StateGraph(DecisionState)
    graph.add_node("understand", _understand)
    graph.add_node("filter", _filter)
    graph.add_node("score", _score)
    graph.add_node("phrase", _phrase)

    graph.add_edge(START, "understand")
    graph.add_edge("understand", "filter")
    graph.add_conditional_edges("filter", _route_after_filter, {"score": "score", "end": END})
    graph.add_edge("score", "phrase")
    graph.add_edge("phrase", END)
    # checkpointer 关闭：一次决策有界执行，run 状态由业务服务统一持久化
    return graph.compile()


decision_graph = build_graph()