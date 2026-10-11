"""JevScorer：经 TeamoRouter 调用 Jev 决策模型（技术方案 §6.2）。

Jev 不是对话模型：一次请求内对同一份 state 并行回答多个 typed question，
返回带概率的取值而非自由文本。本项目一次请求提交 5 个 `score` 维度问题
与 1 个 `noul` 忌口护栏问题。

走的是 TypeSafe 原生格式（`POST /v1/systemone`），不是 OpenAI 兼容格式：
是非判断用 `noul` 原语（返回 0–1 数值），而非网关侧的 `boolean`。
`score` 返回 criteria 数组上的 0 基插值下标（如 4 档返回 2.97），
因此归一化到 0–10 只需按档位数线性映射：`score / (档位数 - 1) * 10`。
置信度优先取模型自报的 `confidence`（已校准），缺失时才退回概率峰值。
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from .config import settings
from .models import Candidate, CandidateScore, DimScore, Profile

logger = logging.getLogger("mealmind.agent.jev")

DIM_NAMES = ("taste", "budget", "timeliness", "reputation", "scene")

# 五维档位（PRD 5.4 / §11「Rubric 档位定稿」）：描述从低到高，Jev 返回其上的插值下标。
# 措辞需用 Golden 集验证区分度后再定稿并升 rubric_version。
RUBRIC: dict[str, dict[str, Any]] = {
    "taste": {
        "instructions": "这道菜与用户的口味偏好在多大程度上贴合？",
        "criteria": [
            "完全不符：与用户要的口味明显冲突",
            "部分接近：沾边但不满足核心口味",
            "基本符合：满足用户的主要口味",
            "高度贴合：正好是用户想要的口味",
        ],
    },
    "budget": {
        "instructions": "这道菜的价格相对用户的预算上限有多大吸引力？",
        "criteria": [
            "超出预算：高于用户给出的预算上限",
            "勉强够格：几乎用满预算，没有余量",
            "比较划算：在预算内有明显余量",
            "非常划算：远低于预算上限",
        ],
    },
    "timeliness": {
        "instructions": "这道菜的配送时长与用户期望的送达时间相比如何？",
        "criteria": [
            "无法满足：超过用户期望的送达时间",
            "勉强赶上：刚好卡在期望时间上",
            "比较从容：比期望时间快出一截",
            "非常宽裕：远快于用户期望的送达时间",
        ],
    },
    "reputation": {
        "instructions": "这家商家与这道菜的口碑支撑有多强？",
        "criteria": [
            "偏弱：评分或销量明显低于同类",
            "一般：评分与销量都不突出",
            "良好：评分较高且有不错的销量",
            "优秀：高评分且是热销菜品",
        ],
    },
    "scene": {
        "instructions": "这道菜与用户描述的就餐场景有多大程度的适配？",
        "criteria": [
            "不适配：与场景诉求相冲突",
            "勉强可以：说不上契合，但也不冲突",
            "比较适配：符合该场景的常见选择",
            "高度适配：正是该场景的典型选择",
        ],
    },
}

GUARD_QUESTION = "conflict"


class JevError(RuntimeError):
    """Jev 调用或响应异常，交由降级状态机处理。"""


class JevClient:
    """TeamoRouter 的 TypeSafe 兼容端点客户端：POST {base}/systemone。"""

    def __init__(self, *, api_key: str, base_url: str, model: str,
                 timeout_s: float = 3.0, retries: int = 1, proxy_url: str = "") -> None:
        self._api_key = api_key
        self._model = model
        self._base_url = base_url.rstrip("/")
        self._timeout_s = timeout_s
        self._retries = retries
        # 本地开发需经 SOCKS 代理才能到达该域名（直连在 TLS 握手被重置，实测）；
        # 生产环境通常可直连，故默认留空
        self._proxy_url = proxy_url or None

    @property
    def configured(self) -> bool:
        return bool(self._api_key)

    def evaluate(self, state: dict[str, Any], questions: dict[str, Any]) -> dict[str, Any]:
        """一次决策请求；超时与重试按 §6.2（实测中位 1.2s、上限 2.1s，超时取 3s）。

        注意：TypeSafe 格式不使用 messages / stream / temperature / max_tokens。
        """

        payload = {
            "model": self._model,
            "state": state,
            "questions": questions,
        }
        last_error: Exception | None = None
        for attempt in range(self._retries + 1):
            try:
                with httpx.Client(timeout=self._timeout_s, proxy=self._proxy_url) as client:
                    response = client.post(
                        f"{self._base_url}/systemone",
                        headers={"Authorization": f"Bearer {self._api_key}"},
                        json=payload,
                    )
                if response.status_code != 200:
                    raise JevError(f"jev returned {response.status_code}: {response.text[:200]}")
                body = response.json()
                answers = body.get("answers")
                if not isinstance(answers, dict):
                    raise JevError("jev response missing answers")
                return body
            except Exception as exc:
                last_error = exc
                logger.warning("jev evaluate failed (attempt %d/%d): %s",
                               attempt + 1, self._retries + 1, exc)
        raise JevError(str(last_error))


class JevScorer:
    """五维 Jev 打分：一次请求并行回答 5 个 score 与 1 个忌口护栏 boolean。"""

    mode = "JEV"

    def __init__(self, client: JevClient) -> None:
        self._client = client

    def score(self, candidate: Candidate, profile: Profile, context: Any) -> CandidateScore:
        questions = {
            dim: {
                "type": "score",
                "instructions": RUBRIC[dim]["instructions"],
                "criteria": RUBRIC[dim]["criteria"],
            }
            for dim in DIM_NAMES
        }
        avoid = profile.avoid_ingredients
        if avoid:
            questions[GUARD_QUESTION] = {
                "type": "noul",
                "instructions": f"这道菜是否含有用户明确忌口的食材（{'、'.join(avoid)}）？",
                "criteria": {"true": "含有其中至少一种", "false": "一种都不含"},
            }

        answers = self._client.evaluate(_state(candidate, profile), questions).get("answers", {})
        # 成本计量（M4 W5）：一次成功的 Jev 调用（token 按 PRD §5.8 实测常量折算，不逐次采集）
        context.usage.count_jev_call()
        dims = [self._to_dim(dim, answers.get(dim)) for dim in DIM_NAMES]
        guard = 0.0
        if avoid:
            # noul 原语直接返回 0–1 的数值
            guard = _as_float(answers.get(GUARD_QUESTION, {}).get("noul"), 0.0)
        return CandidateScore(
            dishId=candidate.dishId,
            dims=dims,
            conflictGuard=guard,
            mode=self.mode,
        )

    @staticmethod
    def _to_dim(dim: str, answer: Any) -> DimScore:
        levels = len(RUBRIC[dim]["criteria"])
        raw = _as_float(answer.get("score") if isinstance(answer, dict) else None, None)
        if raw is None:
            raise JevError(f"answer for {dim} missing score")
        # 0 基插值下标 → 0–10；档位数 2 时退化为二分
        value = 0.0 if levels < 2 else raw / (levels - 1) * 10.0
        value = min(10.0, max(0.0, value))
        # 优先用模型自报的校准置信度；缺失时退回概率峰值
        reported = _as_float(answer.get("confidence"), None) if isinstance(answer, dict) else None
        if reported is not None:
            confidence = round(reported, 3)
        else:
            confidence = _peak(answer.get("probabilities") if isinstance(answer, dict) else None)
        return DimScore(dim=dim, value=round(value, 2), confidence=confidence)


def _state(candidate: Candidate, profile: Profile) -> dict[str, Any]:
    """结构化 state：需求 + 菜品事实，Jev 不需要再查任何存储。"""

    wants: dict[str, Any] = {}
    if profile.taste_tags:
        wants["口味"] = list(profile.taste_tags)
    if profile.budget_max is not None:
        wants["预算上限"] = profile.budget_max
    if profile.max_delivery_min is not None:
        wants["期望送达分钟"] = profile.max_delivery_min
    if profile.scene:
        wants["场景"] = profile.scene
    return {
        "用户需求": wants,
        "菜品": {
            "名称": candidate.name,
            "价格": candidate.price,
            "配送分钟": candidate.deliveryMinutes,
            "商家评分": candidate.merchantRating,
            "月售": candidate.dishMonthlySales,
            "标签": [tag.tag for tag in candidate.tags],
            "品类": candidate.categoryName,
            "商家": candidate.merchantName,
        },
    }


def _as_float(value: Any, default: float | None) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _peak(probabilities: Any) -> float:
    """置信度取概率分布峰值；缺失时给保守值。"""

    if not isinstance(probabilities, dict) or not probabilities:
        return 0.5
    values = [v for v in (_as_float(v, None) for v in probabilities.values()) if v is not None]
    return round(max(values), 3) if values else 0.5


_client = JevClient(
    api_key=settings.jev_api_key,
    base_url=settings.jev_base_url,
    model=settings.jev_model,
    timeout_s=settings.jev_timeout_s,
    proxy_url=settings.jev_proxy_url,
)


def resolve_primary():
    """配置了 TeamoRouter key 才启用 Jev 通道，否则整条链路走规则分。"""

    return JevScorer(_client) if _client.configured else None
