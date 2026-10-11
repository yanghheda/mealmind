"""在途 run 注册表：协作式编排的会合点。

业务服务（SSE 客户端）在收到 understand 事件后执行约束过滤，
并通过 POST /internal/decide/{runId}/candidates 回填候选；图在过滤节点等待该回填。
注册表只存内存态的在途 run，不承担持久化（快照由业务服务写入 MySQL）。
"""

from __future__ import annotations

import asyncio
import threading
import time
import uuid
from dataclasses import dataclass, field


@dataclass
class RunUsage:
    """一次决策的用量与阶段耗时（M4 W5 成本计量）：各节点边执行边累加，_usage 节点统一上报。

    打分按批并发（asyncio.to_thread），jev_calls 用锁累加以防丢更新；其余字段只在图节点线程写。
    """

    input_tokens: int = 0
    output_tokens: int = 0
    estimated: bool = False
    jev_calls: int = 0
    understand_ms: float = 0.0
    score_ms: float = 0.0
    phrase_ms: float = 0.0
    filter_wait_ms: float = 0.0
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False, compare=False)

    def count_jev_call(self) -> None:
        with self._lock:
            self.jev_calls += 1


@dataclass
class RunContext:
    run_id: str
    queue: asyncio.Queue = field(default_factory=asyncio.Queue)
    candidates_ready: asyncio.Event = field(default_factory=asyncio.Event)
    candidates: list | None = None
    closed: bool = False
    created_at: float = field(default_factory=time.monotonic)
    task: asyncio.Task | None = None
    # 澄清（M4 W2）：等待回答期间跳过空闲清理，回答由 /answers 端点回填
    clarify_pending: bool = False
    answers_ready: asyncio.Event = field(default_factory=asyncio.Event)
    answers: list | None = None
    answers_skipped: bool = False
    # 最近一次活动时间：sweep 以它判断僵尸 run（澄清等待不计）
    last_activity: float = field(default_factory=time.monotonic)
    # 成本计量（M4 W5）：token / Jev 调用数 / 各阶段耗时
    usage: RunUsage = field(default_factory=RunUsage)

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
        """清理既没有回填也没有断开的僵尸 run（例如业务服务中途崩溃）。

        澄清等待中的 run 一律跳过空闲清理：问答窗口由图内 CLARIFY_TIMEOUT_S 兜底，
        否则「整轮时长 > max_idle_seconds」的合法 run 会被误杀（M4 W2）。
        """

        now = time.monotonic()
        for run_id, context in list(self._runs.items()):
            if context.clarify_pending:
                continue
            if now - context.last_activity > max_idle_seconds:
                context.closed = True
                context.finish()
                self._runs.pop(run_id, None)


registry = RunRegistry()