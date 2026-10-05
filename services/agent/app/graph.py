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
from .models import Candidate, Profile
from .phraser import phraser
from .runs import registry
from .scorer import ScoringContext, scorer
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
    reasons: list[dict[str, Any]]


async def _understand(state: DecisionState) -> DecisionState:
    context = registry.get(state["run_id"])
    profile = understander.parse(state["text"])
    # M2 的理解通道即关键字规则（模型接入前的既定通道），fromFallback 恒为 True
    if context:
        context.emit(
            "understand",
            {"profile": profile.model_dump(), "questions": [], "fromFallback": True},
        )
    return {"profile": profile, "from_fallback": True, "questions": []}


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
    mode = scorer.mode
    if context:
        context.emit("scoring", {"done": 0, "total": total, "mode": mode})

    max_sales = max((c.dishMonthlySales for c in candidates), default=0)
    scoring_context = ScoringContext(max_dish_sales=max_sales)
    reasons: list[dict[str, Any]] = []

    for start in range(0, total, _SCORE_BATCH_SIZE):
        batch = candidates[start : start + _SCORE_BATCH_SIZE]
        results = await asyncio.gather(
            *(asyncio.to_thread(scorer.score, candidate, profile, scoring_context) for candidate in batch)
        )
        for candidate, score in zip(batch, results):
            reasons.append({"dishId": candidate.dishId, "reason": phraser.reason(candidate, score, profile)})
            if context:
                context.emit("card", _card_payload(candidate, score))
        if context:
            context.emit("scoring", {"done": min(start + _SCORE_BATCH_SIZE, total), "total": total, "mode": mode})

    return {"reasons": reasons}


async def _phrase(state: DecisionState) -> DecisionState:
    context = registry.get(state["run_id"])
    if context:
        for item in state.get("reasons") or []:
            context.emit("reason.delta", {"dishId": item["dishId"], "text": item["reason"]})
    return {}


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