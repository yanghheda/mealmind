"""RuleScorer：标签匹配 + 价格公式 + 口碑归一化的确定性打分（技术方案 §6.2）。

它不是「假数据」：是 Jev 接入前整条链路的真实基线，也是评测里与 Jev 对比的对照组。
统一接口 score(candidate, profile, context) 为将来 JevScorer 保留同一形状。
"""

from __future__ import annotations

from dataclasses import dataclass

from .models import Candidate, CandidateScore, DimScore, Profile

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


@dataclass(frozen=True)
class ScoringContext:
    """一次打分批次内的上下文（口碑维度的月售归一化需要批内最大值）。"""

    max_dish_sales: int


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