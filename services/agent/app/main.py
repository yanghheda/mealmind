"""MealMind Agent 运行时（FastAPI + LangGraph）。

对外只暴露内网接口（服务间令牌鉴权）：
    POST /internal/decide                     一次决策的 SSE 流（协作式编排的主通道）
    POST /internal/decide/{runId}/candidates  业务服务回填约束过滤结果，恢复暂停的图
职责边界：只做理解、打分、表达；不算价、不排序、不写任何存储。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from .config import settings
from .graph import decision_graph
from .models import (
    AnswersRequest,
    Candidate,
    CandidateScore,
    CandidatesRequest,
    DecideRequest,
    ScoreRequest,
    ScoreResponse,
    ScoredCard,
)
from .phraser import phraser, template_phraser
from .runs import registry
from .scorer import ScoringContext, router
from .understander import understander

logger = logging.getLogger("mealmind.agent")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

_SSE_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


def verify_internal_token(x_internal_token: str = Header(default="")) -> None:
    """服务间令牌；未配置令牌（本地开发）时跳过校验。"""

    if settings.internal_token and x_internal_token != settings.internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


@asynccontextmanager
async def lifespan(_: FastAPI):
    async def sweeper() -> None:
        while True:
            await asyncio.sleep(30)
            registry.sweep()

    task = asyncio.create_task(sweeper())
    yield
    task.cancel()


app = FastAPI(title="MealMind Agent", version="0.2.0", lifespan=lifespan)


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok", "service": "mealmind-agent", "scorerMode": settings.scorer_mode}


@app.post("/internal/decide", dependencies=[Depends(verify_internal_token)])
async def decide(request: DecideRequest) -> StreamingResponse:
    context = registry.create(request.runId)

    async def run_graph() -> None:
        try:
            await decision_graph.ainvoke(
                {
                    "run_id": request.runId,
                    "text": request.text,
                    "mode": request.mode,
                    "merchant_id": request.merchantId,
                }
            )
        except asyncio.CancelledError:
            raise
        except Exception:  # 图的兜底失败出口：报错并结束，不吞异常信息
            logger.exception("decision graph failed, runId=%s", request.runId)
            context.emit(
                "error",
                {"code": "AGENT_INTERNAL", "message": "决策执行失败", "retryable": True},
            )
        finally:
            context.finish()

    context.task = asyncio.create_task(run_graph())
    logger.info("decide started, runId=%s mode=%s", request.runId, request.mode)
    return StreamingResponse(_stream(context), media_type="text/event-stream", headers=_SSE_HEADERS)


async def _stream(context):
    try:
        while True:
            item = await context.queue.get()
            if item is None:
                break
            yield _format_event(item)
    finally:
        context.closed = True
        if context.task and not context.task.done():
            context.task.cancel()
        registry.remove(context.run_id)
        logger.info("decide stream closed, runId=%s", context.run_id)


def _format_event(item: dict) -> str:
    data = json.dumps(item["data"], ensure_ascii=False, separators=(",", ":"))
    return f"event: {item['event']}\ndata: {data}\n\n"


@app.post("/internal/decide/{run_id}/candidates", dependencies=[Depends(verify_internal_token)])
def provide_candidates(run_id: str, request: CandidatesRequest) -> dict:
    context = registry.get(run_id)
    if context is None or context.closed:
        raise HTTPException(status_code=404, detail="run not found or finished")
    context.candidates = request.candidates
    context.last_activity = time.monotonic()
    context.candidates_ready.set()
    logger.info("candidates provided, runId=%s count=%d", run_id, len(request.candidates))
    return {"accepted": len(request.candidates)}


@app.post("/internal/decide/{run_id}/answers", dependencies=[Depends(verify_internal_token)])
def provide_answers(run_id: str, request: AnswersRequest) -> dict:
    """澄清回答（M4 W2）：把用户选择回填给暂停在澄清节点的图；不处于等待态时返回 409。"""

    context = registry.get(run_id)
    if context is None or context.closed:
        raise HTTPException(status_code=404, detail="run not found or finished")
    if not context.clarify_pending:
        raise HTTPException(status_code=409, detail="clarify not pending")
    context.answers = [answer.model_dump() for answer in request.answers]
    context.answers_skipped = request.skipped
    context.last_activity = time.monotonic()
    context.answers_ready.set()
    logger.info("clarify answers provided, runId=%s skipped=%s count=%d",
                run_id, request.skipped, len(request.answers))
    return {"accepted": True}


@app.post("/internal/score", dependencies=[Depends(verify_internal_token)])
async def score(request: ScoreRequest) -> ScoreResponse:
    """评测打分端点：业务服务直接提供候选，复用 understander + scorer + phraser，不走 SSE 编排。"""

    result = await asyncio.to_thread(understander.parse, request.text)
    profile = result.profile
    candidates = request.candidates
    max_sales = max((c.dishMonthlySales for c in candidates), default=0)
    scoring_context = ScoringContext(max_dish_sales=max_sales)

    # 与生产图 _score 节点同一口径：按批并发打分（决策模型单次含网络往返约 0.3s，串行 20 道会明显拖慢）
    scores: list[tuple[Candidate, CandidateScore]] = []
    for start in range(0, len(candidates), settings.batch_size):
        batch = candidates[start : start + settings.batch_size]
        batch_scores = await asyncio.gather(
            *(asyncio.to_thread(router.score, candidate, profile, scoring_context) for candidate in batch)
        )
        scores.extend(zip(batch, batch_scores))
    # 理由生成同样按批并发，评测与线上走同一通道（含事实校验与模板兜底）
    if request.withReason:
        chunks = [scores[i : i + settings.batch_size] for i in range(0, len(scores), settings.batch_size)]
        parts = await asyncio.gather(*(asyncio.to_thread(phraser.reasons, chunk, profile) for chunk in chunks))
        reasons: dict[str, str] = {}
        for part, _usage in parts:  # 评测成本不进 cost_yuan（只统计真实决策），用量在此丢弃
            reasons.update(part)
    else:
        # 评测指标不依赖理由：走模板通道，省掉整轮表达器调用
        reasons = {
            candidate.dishId: template_phraser.reason(candidate, score, profile)
            for candidate, score in scores
        }

    cards: list[ScoredCard] = []
    for candidate, candidate_score in scores:
        cards.append(
            ScoredCard(
                dishId=candidate.dishId,
                name=candidate.name,
                merchantId=candidate.merchantId,
                merchantName=candidate.merchantName,
                price=candidate.price,
                imageUrl=candidate.imageUrl,
                dims=candidate_score.dims,
                conflictGuard=candidate_score.conflictGuard,
                mode=candidate_score.mode,
                reason=reasons[candidate.dishId],
            )
        )
    logger.info("score request processed, candidates=%d mode=%s fromFallback=%s",
                len(candidates), router.mode, result.from_fallback)
    return ScoreResponse(profile=profile, cards=cards)