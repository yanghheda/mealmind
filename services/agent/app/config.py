"""Agent 运行时配置（环境变量注入，见 deploy/compose.yml 与 docs/tech-design.md §8.5）。"""

import os
from dataclasses import dataclass


def _float(name: str, default: float) -> float:
    raw = os.getenv(name)
    return float(raw) if raw else default


def _int(name: str, default: int) -> int:
    raw = os.getenv(name)
    return int(raw) if raw else default


@dataclass(frozen=True)
class Settings:
    """进程级配置；不含任何模型密钥（Jev / 生成式模型在 M3 接入）。"""

    # 服务间令牌（与业务服务共享，空字符串表示本地不校验）
    internal_token: str
    # 打分通道：M2 固定 RULE（JevScorer 在 M3 接入后改为 AUTO）
    scorer_mode: str
    # 档位版本：改档位定义必须升版本号（打分缓存键与决策记录都引用它）
    rubric_version: str
    # 候选回填（业务服务执行约束过滤）的等待超时
    continuation_timeout_s: float
    # 打分批次大小，与 §8.5 scorer.batch-size 对齐
    batch_size: int


settings = Settings(
    internal_token=os.getenv("INTERNAL_TOKEN", ""),
    scorer_mode=os.getenv("SCORER_MODE", "RULE").upper(),
    rubric_version=os.getenv("RUBRIC_VERSION", "v1.2"),
    continuation_timeout_s=_float("CONTINUATION_TIMEOUT_S", 5.0),
    batch_size=_int("SCORER_BATCH_SIZE", 5),
)