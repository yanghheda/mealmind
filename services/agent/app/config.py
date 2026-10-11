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
    """进程级配置；模型密钥只从环境变量读，仅存在于 agent 容器（见 deploy/compose.yml）。"""

    # 服务间令牌（与业务服务共享，空字符串表示本地不校验）
    internal_token: str
    # 打分通道：AUTO 优先 Jev、失败自动降级规则；RULE 强制规则；JEV 强制 Jev
    scorer_mode: str
    # 档位版本：改档位定义必须升版本号（打分缓存键与决策记录都引用它）
    rubric_version: str
    # 候选回填（业务服务执行约束过滤）的等待超时
    continuation_timeout_s: float
    # 澄清提问等待用户回答的最长时长（秒）；超时按跳过处理（M4 W2）
    clarify_timeout_s: float
    # 打分批次大小，与 §8.5 scorer.batch-size 对齐
    batch_size: int
    # Jev 直连 TypeSafe 官方 API
    jev_api_key: str
    jev_base_url: str
    jev_model: str
    # 生成式模型（理解与表达）：阿里云百炼 qwen3.8
    dashscope_api_key: str
    qwen_base_url: str
    qwen_model: str
    # 降级状态机（技术方案 §6.1）：打分失败率超过该比例即转 FULL
    degrade_partial_threshold: float
    # 全量降级后重新探测的冷却时间（秒）
    degrade_cooldown_seconds: int
    # 连续理解器失败次数达到该值即转 FULL
    degrade_understand_failures: int
    # PARTIAL 恢复 NORMAL 所需的连续打分成功次数
    degrade_recover_successes: int
    # 理解器单次调用超时（秒）；实测 qwen3.8-flash 关思考后 max ~2.0s，留 1.5x 余量
    understand_timeout_s: float
    # 表达器单批调用超时（秒）
    phrase_timeout_s: float
    # Jev 单菜打分超时（秒）；实测中位 1.2s、上限 2.1s，取 3s 留余量
    jev_timeout_s: float
    # Jev 单次调用的实测 token 载荷（PRD §5.8：1018 入 / 90 出，输出免费）；
    # 仅用于 _usage 节点的折算日志，计价走「按次」而非逐次采 token
    jev_tokens_in: int
    jev_tokens_out: int
    # Jev 出网代理（本地开发需经系统 SOCKS 代理才能到达该域名；生产可留空）
    jev_proxy_url: str


settings = Settings(
    internal_token=os.getenv("INTERNAL_TOKEN", ""),
    scorer_mode=os.getenv("SCORER_MODE", "AUTO").upper(),
    rubric_version=os.getenv("RUBRIC_VERSION", "v1.2"),
    continuation_timeout_s=_float("CONTINUATION_TIMEOUT_S", 5.0),
    clarify_timeout_s=_float("CLARIFY_TIMEOUT_S", 60.0),
    batch_size=_int("SCORER_BATCH_SIZE", 5),
    jev_api_key=os.getenv("JEV_API_KEY", ""),
    jev_base_url=os.getenv("JEV_BASE_URL", "https://api.teamorouter.com/v1"),
    jev_model=os.getenv("JEV_MODEL", "jev"),
    dashscope_api_key=os.getenv("DASHSCOPE_API_KEY", ""),
    qwen_base_url=os.getenv("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    qwen_model=os.getenv("QWEN_MODEL", "qwen3.8-max-0902"),
    degrade_partial_threshold=_float("DEGRADE_PARTIAL_THRESHOLD", 0.3),
    degrade_cooldown_seconds=_int("DEGRADE_COOLDOWN_SECONDS", 60),
    degrade_understand_failures=_int("DEGRADE_UNDERSTAND_FAILURES", 3),
    degrade_recover_successes=_int("DEGRADE_RECOVER_SUCCESSES", 20),
    understand_timeout_s=_float("UNDERSTAND_TIMEOUT_S", 3.0),
    phrase_timeout_s=_float("PHRASE_TIMEOUT_S", 4.0),
    jev_timeout_s=_float("JEV_TIMEOUT_S", 3.0),
    jev_tokens_in=_int("JEV_TOKENS_IN", 1018),
    jev_tokens_out=_int("JEV_TOKENS_OUT", 90),
    jev_proxy_url=os.getenv("JEV_PROXY_URL", ""),
)