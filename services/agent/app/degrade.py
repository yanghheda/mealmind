"""降级状态机（技术方案 §6.1）。

三档：NORMAL（主通道打分）/ PARTIAL（失败菜品走规则分）/ FULL（全部走规则分）。
状态是进程级的：连续失败计数与冷却跨 run 生效，避免模型恢复瞬间被并发请求打回降级。

控制器只在「至少有一条模型通道」时启用：全部走规则时停在 NORMAL——那是 M2 的基线，
不是降级。两条通道（理解器 / 打分器）各自注册可用性，任一条接入即启用。
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass

from .config import settings


class DegradeLevel:
    """降级档位（与 decision_run.degrade_level 取值一致）。"""

    NORMAL = "NORMAL"
    PARTIAL = "PARTIAL"
    FULL = "FULL"


@dataclass(frozen=True)
class Transition:
    """一次档位变化；由调用方转成 SSE 的 degraded 事件。"""

    from_level: str
    to_level: str
    reason: str


class DegradationController:
    """NORMAL / PARTIAL / FULL 三档迁移，线程安全（打分在线程池中并发执行）。"""

    def __init__(self, *,
                 partial_threshold: float,
                 cooldown_seconds: int,
                 understand_failures: int,
                 recover_successes: int) -> None:
        self._partial_threshold = partial_threshold
        self._cooldown_seconds = cooldown_seconds
        self._understand_failures = understand_failures
        self._recover_successes = recover_successes
        self._lock = threading.Lock()
        self._level = DegradeLevel.NORMAL
        self._channels: set[str] = set()
        self._consecutive_understand_failures = 0
        self._consecutive_score_successes = 0
        self._full_since = 0.0

    @property
    def level(self) -> str:
        with self._lock:
            return self._level

    @property
    def enabled(self) -> bool:
        with self._lock:
            return bool(self._channels)

    def set_channel_available(self, channel: str, available: bool) -> None:
        """登记一条模型通道是否接入；全部未接入时停用并回到 NORMAL。"""

        with self._lock:
            if available:
                self._channels.add(channel)
            else:
                self._channels.discard(channel)
            if not self._channels:
                self._reset_locked()

    def should_attempt_primary(self) -> bool:
        """本轮是否尝试主通道：FULL 档只在冷却结束后发起探测。"""

        with self._lock:
            return self._should_attempt_locked()

    def record_understand(self, success: bool) -> Transition | None:
        """理解器一次调用结果；连续失败达阈值转 FULL。"""

        with self._lock:
            if not self._channels:
                return None
            if success:
                self._consecutive_understand_failures = 0
                return None
            self._consecutive_understand_failures += 1
            if self._consecutive_understand_failures >= self._understand_failures:
                return self._to_locked(
                    DegradeLevel.FULL,
                    f"理解器连续失败 {self._consecutive_understand_failures} 次",
                )
            return None

    def record_scoring(self, total: int, failed: int) -> Transition | None:
        """一轮打分的失败统计；FULL 档下的这一轮即为恢复探测。"""

        with self._lock:
            if not self._channels or total <= 0:
                return None

            if self._level == DegradeLevel.FULL:
                if failed == 0:
                    return self._to_locked(DegradeLevel.NORMAL, "探测成功")
                self._full_since = time.monotonic()  # 探测失败：重置冷却，继续观察
                return None

            if failed == 0:
                self._consecutive_score_successes += total
                if (self._level == DegradeLevel.PARTIAL
                        and self._consecutive_score_successes >= self._recover_successes):
                    return self._to_locked(DegradeLevel.NORMAL, "连续打分全部成功")
                return None

            self._consecutive_score_successes = 0
            rate = failed / total
            if rate > self._partial_threshold:
                return self._to_locked(
                    DegradeLevel.FULL,
                    f"打分失败率 {failed}/{total} 超过阈值 {self._partial_threshold:.0%}",
                )
            if self._level != DegradeLevel.PARTIAL:
                return self._to_locked(
                    DegradeLevel.PARTIAL,
                    f"部分菜品打分失败（{failed}/{total}）",
                )
            return None

    # -- 内部（调用方已持锁） --------------------------------------------------

    def _should_attempt_locked(self) -> bool:
        if not self._channels:
            return False
        if self._level != DegradeLevel.FULL:
            return True
        return time.monotonic() - self._full_since >= self._cooldown_seconds

    def _to_locked(self, level: str, reason: str) -> Transition | None:
        if level == self._level:
            return None
        previous = self._level
        self._level = level
        self._consecutive_score_successes = 0
        self._consecutive_understand_failures = 0
        if level == DegradeLevel.FULL:
            self._full_since = time.monotonic()
        else:
            self._full_since = 0.0
        return Transition(previous, level, reason)

    def _reset_locked(self) -> None:
        self._level = DegradeLevel.NORMAL
        self._consecutive_understand_failures = 0
        self._consecutive_score_successes = 0
        self._full_since = 0.0


# 进程级单例：理解器与打分器共享同一份档位与计数（技术方案 §6.1）
degrade_controller = DegradationController(
    partial_threshold=settings.degrade_partial_threshold,
    cooldown_seconds=settings.degrade_cooldown_seconds,
    understand_failures=settings.degrade_understand_failures,
    recover_successes=settings.degrade_recover_successes,
)
