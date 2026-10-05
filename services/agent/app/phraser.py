"""模板表达器：受 PRD 5.5 约束的理由生成（M2 无生成式模型，走模板通道）。

理由只引用入参中的事实（标签、价格、时长、评分、分数），单卡 ≤30 字，
句式 = 命中点 + 分数依据；生成式表达器接入后作为事实校验失败时的兜底保留。
"""

from __future__ import annotations

from .models import Candidate, CandidateScore, Profile

_DIM_LABELS = {
    "taste": "口味",
    "budget": "价格",
    "timeliness": "时效",
    "reputation": "口碑",
    "scene": "场景",
}

# 理由里可以引用的标签类型（营销词不适合作为「命中点」）
_FACT_TAG_TYPES = {"SPICY", "FLAVOR", "HEALTH", "INGREDIENT"}


class TemplatePhraser:
    """确定性的「命中点 + 分数」句式，数字全部来自打分输入。"""

    def reason(self, candidate: Candidate, score: CandidateScore, profile: Profile) -> str:
        best = max(score.dims, key=lambda d: d.value)
        label = _DIM_LABELS.get(best.dim, best.dim)
        fact = self._fact(candidate, profile, best.dim)
        text = f"{fact}，{label} {best.value:.1f} 分"
        return text[:30]

    @staticmethod
    def _fact(candidate: Candidate, profile: Profile, dim: str) -> str:
        if dim == "taste":
            tags = [t.tag for t in candidate.tags if t.tagType in _FACT_TAG_TYPES]
            preferred = [t for t in tags if t in profile.taste_tags or t in _SPICY_HINT]
            return preferred[0] if preferred else (tags[0] if tags else candidate.name)
        if dim == "budget":
            return f"到手 {candidate.price:.0f} 元"
        if dim == "timeliness":
            return f"预计 {candidate.deliveryMinutes} 分钟送达"
        if dim == "reputation":
            return f"商家评分 {candidate.merchantRating:.1f}"
        return f"月售 {candidate.dishMonthlySales}"


_SPICY_HINT = {"不辣", "微辣", "中辣", "特辣", "辣"}

phraser = TemplatePhraser()