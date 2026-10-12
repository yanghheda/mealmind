"""RuleScorer：标签匹配 + 价格公式 + 口碑归一化的确定性打分（技术方案 §6.2）。

它不是「假数据」：是 Jev 接入前整条链路的真实基线，也是评测里与 Jev 对比的对照组。
统一接口 score(candidate, profile, context) 为将来 JevScorer 保留同一形状。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

from .config import settings
from .degrade import DegradationController, degrade_controller
from .jev import resolve_primary as resolve_jev
from .models import Candidate, CandidateScore, DimScore, Profile
from .runs import RunUsage

logger = logging.getLogger("mealmind.agent.scorer")

# 与 dish_tag SPICY 取值一致；「辣」是需求侧的泛辣度，匹配任一非不辣菜品
_SPICY_LEVELS = {"不辣": 0, "微辣": 1, "中辣": 2, "特辣": 3}
_FLAVOR_TAGS = {
    "麻辣", "香辣", "酸辣", "卤味", "咖喱", "孜然", "蒜香", "酸甜", "甜口", "清淡", "麻酱", "咸鲜",
}
_HEALTH_TAGS = {"低卡", "高蛋白"}
_SCENE_RULES = {
    # 场景 -> 正向加分标签（命中给高分，明显冲突标签给低分）
    "健康轻食": ({"低卡", "高蛋白"}, {"油炸", "高糖", "高盐"}),
    "多人分享": ({"招牌", "套餐"}, set()),
    "加班": (set(), set()),
    "一人食": (set(), set()),
}

DIM_NAMES = ("taste", "budget", "timeliness", "reputation", "scene")


@dataclass
class ScoringContext:
    """一次打分批次内的上下文（口碑维度的月售归一化需要批内最大值）。

    M4 W5：去 frozen 并携带可变 usage，JevScorer 借它累加调用次数。
    """

    max_dish_sales: int
    usage: RunUsage = field(default_factory=RunUsage)


class RuleScorer:
    """五维规则打分：每维 0–10 与固定规则置信度，忌口护栏单独返回。"""

    mode = "RULE"

    def score(self, candidate: Candidate, profile: Profile, context: ScoringContext) -> CandidateScore:
        dish_tags = {tag.tag for tag in candidate.tags}
        dims = [
            self._taste(profile, dish_tags),
            self._budget(profile, candidate),
            self._timeliness(profile, candidate),
            self._reputation(candidate, context),
            self._scene(profile, dish_tags),
        ]
        return CandidateScore(
            dishId=candidate.dishId,
            dims=dims,
            conflictGuard=self._guard(profile, dish_tags),
            mode=self.mode,
        )

    # -- 各维度规则 ----------------------------------------------------------

    @staticmethod
    def _taste(profile: Profile, dish_tags: set[str]) -> DimScore:
        req = set(profile.taste_tags)
        parts: list[float] = []

        req_spicy = req & set(_SPICY_LEVELS) | ({"辣"} if "辣" in req else set())
        if req_spicy:
            dish_levels = [_SPICY_LEVELS[t] for t in dish_tags & set(_SPICY_LEVELS)]
            dish_level = max(dish_levels) if dish_levels else None
            if "辣" in req_spicy:
                score = 9.5 if dish_level and dish_level > 0 else (1.5 if dish_level == 0 else 5.0)
            else:
                req_level = max(_SPICY_LEVELS[t] for t in req_spicy)
                if dish_level is None:
                    score = 5.0
                elif dish_level == req_level:
                    score = 10.0
                elif dish_level > req_level:  # 比需求更辣：差一档可接受，差两档明显不符
                    score = 6.5 if dish_level - req_level == 1 else 2.0
                else:  # 比需求更淡：降一档（如要微辣给不辣）明显走味
                    score = 3.0 if req_level - dish_level == 1 else 1.0
            parts.append(score)

        req_flavor = req & _FLAVOR_TAGS
        if req_flavor:
            parts.append(10.0 if req_flavor & dish_tags else 2.5)

        req_health = req & _HEALTH_TAGS
        if req_health:
            parts.append(9.5 if req_health & dish_tags else 3.0)

        if not parts:
            return DimScore(dim="taste", value=6.0, confidence=0.5)
        value = sum(parts) / len(parts)
        confidence = 0.65 if value >= 9.0 else 0.55
        return DimScore(dim="taste", value=round(value, 2), confidence=confidence)

    @staticmethod
    def _budget(profile: Profile, candidate: Candidate) -> DimScore:
        if profile.budget_max is None:
            return DimScore(dim="budget", value=6.0, confidence=0.5)
        ratio = candidate.price / profile.budget_max
        # 0.5 以内满分，越接近上限分越低：1.0 → 2.0，超过 1.1（约束引擎放行的次选）→ 0
        value = max(0.0, 10.0 - max(0.0, ratio - 0.5) * 16.0)
        return DimScore(dim="budget", value=round(value, 2), confidence=0.6)

    @staticmethod
    def _timeliness(profile: Profile, candidate: Candidate) -> DimScore:
        if profile.max_delivery_min is None:
            return DimScore(dim="timeliness", value=6.0, confidence=0.5)
        margin = profile.max_delivery_min - candidate.deliveryMinutes
        if margin >= 10:
            value = 10.0
        elif margin >= 5:
            value = 8.0
        elif margin >= 0:
            value = 6.5
        else:
            value = 3.0
        return DimScore(dim="timeliness", value=value, confidence=0.6)

    @staticmethod
    def _reputation(candidate: Candidate, context: ScoringContext) -> DimScore:
        rating_part = candidate.merchantRating / 5.0 * 10.0
        sales_part = (
            candidate.dishMonthlySales / context.max_dish_sales * 10.0
            if context.max_dish_sales > 0
            else 0.0
        )
        value = rating_part * 0.7 + sales_part * 0.3
        return DimScore(dim="reputation", value=round(value, 2), confidence=0.65)

    @staticmethod
    def _scene(profile: Profile, dish_tags: set[str]) -> DimScore:
        if not profile.scene or profile.scene not in _SCENE_RULES:
            return DimScore(dim="scene", value=6.0, confidence=0.5)
        positive, negative = _SCENE_RULES[profile.scene]
        if positive & dish_tags:
            return DimScore(dim="scene", value=9.0, confidence=0.55)
        if negative & dish_tags:
            return DimScore(dim="scene", value=3.0, confidence=0.55)
        return DimScore(dim="scene", value=6.0, confidence=0.5)

    @staticmethod
    def _guard(profile: Profile, dish_tags: set[str]) -> float:
        """忌口护栏：标签精确匹配，命中即判风险（业务服务已过滤，这里只做复核）。"""

        return 1.0 if set(profile.avoid_ingredients) & dish_tags else 0.0


scorer = RuleScorer()


class ScorerRouter:
    """通道选择与逐菜降级（技术方案 §6.2）。

    主通道（Jev）可用时逐菜打分，单菜失败即回退规则分并计入本轮失败率，
    失败率驱动 DegradationController 在 NORMAL / PARTIAL / FULL 三档间迁移。
    RULE 模式或主通道未接入时始终走规则分，控制器停用、档位停在 NORMAL。
    """

    def __init__(self, primary, fallback, controller: DegradationController) -> None:
        self._primary = primary
        self._fallback = fallback
        self._controller = controller

    @property
    def primary_mode(self) -> str | None:
        return self._primary.mode if self._primary is not None else None

    @property
    def controller(self) -> DegradationController:
        return self._controller

    @property
    def mode(self) -> str:
        """当前轮次采用的主打通道标签（用于 scoring 事件）。"""

        return self._primary.mode if self._use_primary() else self._fallback.mode

    @property
    def uses_primary(self) -> bool:
        """本轮是否真正尝试主通道（RULE 模式恒为 False）。

        与 `_use_primary` 同源：只有真去调主通道了，逐菜回退规则分才算「失败」；
        强制 RULE 是设计内的对照通道，不能计入失败率（否则整轮被误判为 FULL 降级）。
        """

        return self._use_primary()

    def score(self, candidate: Candidate, profile: Profile, context: ScoringContext) -> CandidateScore:
        if self._use_primary():
            try:
                return self._primary.score(candidate, profile, context)
            except Exception as exc:  # 单菜失败不影响整批：该菜走规则分
                logger.warning("primary score failed, dishId=%s: %s", candidate.dishId, exc)
        return self._fallback.score(candidate, profile, context)

    def _use_primary(self) -> bool:
        if self._primary is None or settings.scorer_mode == "RULE":
            return False
        return self._controller.should_attempt_primary()


def resolve_primary():
    """主通道 Scorer：配置了 Vercel AI Gateway key 时启用 Jev，否则整条链路走规则分。"""

    return resolve_jev()


router = ScorerRouter(resolve_primary(), scorer, degrade_controller)
# 强制 RULE 时不登记打分通道：该模式下永远不会调主通道，不算「接入了一条模型通道」
degrade_controller.set_channel_available(
    "scorer", router.primary_mode is not None and settings.scorer_mode != "RULE"
)