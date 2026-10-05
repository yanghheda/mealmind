"""在途 run 注册表：协作式编排的会合点。

业务服务（SSE 客户端）在收到 understand 事件后执行约束过滤，
并通过 POST /internal/decide/{runId}/candidates 回填候选；图在过滤节点等待该回填。
注册表只存内存态的在途 run，不承担持久化（快照由业务服务写入 MySQL）。
"""

from __future__ import annotations

import asyncio
import time
import uuid
from dataclasses import dataclass, field


@dataclass
class RunContext:
    run_id: str
    queue: asyncio.Queue = field(default_factory=asyncio.Queue)
    candidates_ready: asyncio.Event = field(default_factory=asyncio.Event)
    candidates: list | None = None
    closed: bool = False
    created_at: float = field(default_factory=time.monotonic)
    task: asyncio.Task | None = None

    def emit(self, event: str, data: dict) -> None:
        """节点向 SSE 流推送一个事件（None 为流结束哨兵）。"""

        self.queue.put_nowait({"event": event, "data": data})

    def finish(self) -> None:
        self.queue.put_nowait(None)


class RunRegistry:
    def __init__(self) -> None:
        self._runs: dict[str, RunContext] = {}

    def create(self, run_id: str) -> RunContext:
        context = RunContext(run_id=run_id or uuid.uuid4().hex)
        self._runs[context.run_id] = context
        return context

    def get(self, run_id: str) -> RunContext | None:
        return self._runs.get(run_id)

    def remove(self, run_id: str) -> None:
        self._runs.pop(run_id, None)

    def sweep(self, max_idle_seconds: float = 60.0) -> None:
        """清理既没有回填也没有断开的僵尸 run（例如业务服务中途崩溃）。"""

        now = time.monotonic()
        for run_id, context in list(self._runs.items()):
            if now - context.created_at > max_idle_seconds:
                context.closed = True
                context.finish()
                self._runs.pop(run_id, None)


registry = RunRegistry()