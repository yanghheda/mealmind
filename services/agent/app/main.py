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
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from .config import settings
from .graph import decision_graph
from .models import CandidatesRequest, DecideRequest
from .runs import registry

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
    context.candidates_ready.set()
    logger.info("candidates provided, runId=%s count=%d", run_id, len(request.candidates))
    return {"accepted": len(request.candidates)}