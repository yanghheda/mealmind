"""推荐理由表达器（PRD 5.5）：qwen3.8 生成 + 事实校验，失败回落模板。

两条通道：
- `QwenPhraser`：一批候选一次调用（单条理由独立生成的成本不可接受），
  输出后做事实校验（字数 / 绝对化表述 / 数字与标签是否都来自入参）。
- `TemplatePhraser`：确定性「命中点 + 分数依据」句式，作为校验失败与模型故障的兜底。

理由只引用入参中的事实，单卡 ≤30 字，且不引用低置信度维度。
"""

from __future__ import annotations

import logging
import re

from .config import settings
from .llm import LlmUsage, QwenClient
from .models import Candidate, CandidateScore, Profile

logger = logging.getLogger("mealmind.agent.phraser")

_DIM_LABELS = {
    "taste": "口味",
    "budget": "价格",
    "timeliness": "时效",
    "reputation": "口碑",
    "scene": "场景",
}

# 理由里可以引用的标签类型（营销词不适合作为「命中点」）
_FACT_TAG_TYPES = {"SPICY", "FLAVOR", "HEALTH", "INGREDIENT"}

# 低置信度维度不得写进理由（PRD 5.5 第 5 条）
_MIN_REASON_CONFIDENCE = 0.6

# 绝对化承诺与医疗表述（PRD 5.5 第 3 条）
_BANNED_WORDS = ("最好", "最佳", "最强", "第一", "绝对", "保证", "治疗", "治愈", "疗效", "药效")

_REASON_MAX_CHARS = 30
_NUMBER_PATTERN = re.compile(r"\d+(?:\.\d+)?")


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

template_phraser = TemplatePhraser()


class QwenPhraser:
    """一批候选一次调用生成理由；输出经事实校验，不合格的条目由上层回落模板。"""

    def __init__(self, client: QwenClient) -> None:
        self._client = client

    def reasons(self, items: list[tuple[Candidate, CandidateScore]], profile: Profile) -> tuple[dict[str, str], LlmUsage]:
        if not items:
            return {}, LlmUsage(input_tokens=0, output_tokens=0, estimated=False)
        result = self._client.chat_json(_SYSTEM_PROMPT, _user_prompt(items, profile))
        data = result.data
        raw = data.get("reasons")
        if not isinstance(raw, dict):
            raise ValueError("phraser output missing reasons object")

        reasons: dict[str, str] = {}
        for candidate, score in items:
            text = raw.get(candidate.dishId)
            if not isinstance(text, str):
                continue
            cleaned = text.strip()
            violation = _fact_check(cleaned, candidate, score, profile)
            if violation:
                logger.warning("reason rejected, dishId=%s, reason=%s", candidate.dishId, violation)
                continue
            reasons[candidate.dishId] = cleaned
        return reasons, result.usage


class PhraserRouter:
    """生成式优先、模板兜底：任一条目生成或校验失败即该条目回落模板。"""

    def __init__(self, primary: QwenPhraser | None) -> None:
        self._primary = primary

    @property
    def primary_enabled(self) -> bool:
        return self._primary is not None

    def reasons(self, items: list[tuple[Candidate, CandidateScore]],
                profile: Profile) -> tuple[dict[str, str], LlmUsage | None]:
        generated: dict[str, str] = {}
        usage: LlmUsage | None = None
        if self._primary is not None:
            try:
                generated, usage = self._primary.reasons(items, profile)
            except Exception as exc:  # 模型故障：本批全部回落模板
                logger.warning("primary phraser failed, fallback to template: %s", exc)
        # 缺哪条补哪条：模型漏答、校验不通过、整批失败都走模板
        return {
            candidate.dishId: generated.get(candidate.dishId)
            or template_phraser.reason(candidate, score, profile)
            for candidate, score in items
        }, usage


def _user_prompt(items: list[tuple[Candidate, CandidateScore]], profile: Profile) -> str:
    wants = []
    if profile.taste_tags:
        wants.append("口味 " + "、".join(profile.taste_tags))
    if profile.budget_max is not None:
        wants.append(f"预算 {profile.budget_max:.0f} 元以内")
    if profile.max_delivery_min is not None:
        wants.append(f"{profile.max_delivery_min} 分钟内送达")
    if profile.scene:
        wants.append("场景 " + profile.scene)

    lines = [f"用户需求：{'，'.join(wants) if wants else '未明确'}"]
    lines.append("菜品列表：")
    for candidate, score in items:
        tags = "、".join(t.tag for t in candidate.tags if t.tagType in _FACT_TAG_TYPES) or "无"
        dims = "、".join(
            f"{_DIM_LABELS.get(d.dim, d.dim)} {d.value:.1f}"
            for d in score.dims
            if d.confidence >= _MIN_REASON_CONFIDENCE
        ) or "无"
        lines.append(
            f"- 菜品ID: {candidate.dishId}；名称: {candidate.name}；标签: {tags}；"
            f"价格: {candidate.price:.0f} 元；配送: {candidate.deliveryMinutes} 分钟；"
            f"商家评分: {candidate.merchantRating:.1f}；可用分数: {dims}"
        )
    return "\n".join(lines)


_SYSTEM_PROMPT = (
    "你是外卖推荐理由生成器。为列表中每个菜品写一句推荐理由。\n"
    "硬性规则：\n"
    "1. 只能引用给定的事实（标签、价格、配送时长、商家评分、可用分数），不得新增任何信息；\n"
    f"2. 单条不超过 {_REASON_MAX_CHARS} 个字，句式 = 命中点 + 分数依据，例如「微辣，口味 9.5 分」；\n"
    "3. 不使用绝对化承诺（最好、保证、第一），不做健康与疗效表述；\n"
    "4. 价格与分数照抄给定数值，不要换算或估算；\n"
    "5. 只引用「可用分数」里给出的维度，不要提及其他维度。\n"
    '只输出 JSON，格式为 {"reasons": {"<菜品ID>": "<理由>"}}，不要解释、不要 Markdown 代码块。'
)


def _fact_check(text: str, candidate: Candidate, score: CandidateScore, profile: Profile) -> str | None:
    """返回违规原因；None 表示通过。任何一项不过即回落模板理由。"""

    if not text:
        return "空理由"
    if len(text) > _REASON_MAX_CHARS:
        return f"超过 {_REASON_MAX_CHARS} 字（{len(text)}）"
    for word in _BANNED_WORDS:
        if word in text:
            return f"含绝对化或医疗表述「{word}」"

    allowed_numbers = _allowed_numbers(candidate, score)
    for token in _NUMBER_PATTERN.findall(text):
        # 理由按 1 位小数展示，与允许值同精度比较，避免 8.21 → 8.2 被误判
        if round(float(token), 1) not in allowed_numbers:
            return f"引用了未给出的数字「{token}」"

    allowed_tags = {t.tag for t in candidate.tags} | set(profile.taste_tags)
    # 「辣」是泛辣度描述：菜品本身带辣或需求要辣时，它也算已给出的事实
    if _spicy_context(candidate, profile):
        allowed_tags.add("辣")
    for tag in _matched_tags(text):
        if tag not in allowed_tags:
            return f"引用了未给出的标签「{tag}」"
    return None


def _spicy_context(candidate: Candidate, profile: Profile) -> bool:
    dish_spicy = any(t.tag in _SPICY_HINT and t.tag != "不辣" for t in candidate.tags)
    return dish_spicy or bool(set(profile.taste_tags) & _SPICY_HINT)


def _allowed_numbers(candidate: Candidate, score: CandidateScore) -> set[float]:
    numbers = {
        float(candidate.price),
        float(candidate.deliveryMinutes),
        float(candidate.merchantRating),
        float(candidate.dishMonthlySales),
    }
    for dim in score.dims:
        if dim.confidence >= _MIN_REASON_CONFIDENCE:
            numbers.add(float(dim.value))
    # 菜名里的数字（如「3 号套餐」）也视为可引用
    numbers.update(float(token) for token in _NUMBER_PATTERN.findall(candidate.name))
    return {round(value, 1) for value in numbers}


def _matched_tags(text: str) -> list[str]:
    """最长优先扫描命中标签：「微辣」不会被误判成「辣」。"""

    ordered = sorted(_known_tags(), key=len, reverse=True)
    matched: list[str] = []
    index = 0
    while index < len(text):
        for tag in ordered:
            if text.startswith(tag, index):
                matched.append(tag)
                index += len(tag)
                break
        else:
            index += 1
    return matched


def _known_tags() -> frozenset[str]:
    """项目标签词表（懒加载，避免与理解器模块形成导入环）。"""

    global _KNOWN_TAGS
    if _KNOWN_TAGS is None:
        from .understander import KNOWN_TAGS

        _KNOWN_TAGS = KNOWN_TAGS
    return _KNOWN_TAGS


_KNOWN_TAGS: frozenset[str] | None = None

_client = QwenClient(
    api_key=settings.dashscope_api_key,
    base_url=settings.qwen_base_url,
    model=settings.qwen_model,
    timeout_s=settings.phrase_timeout_s,
    enable_thinking=False,
)


def resolve_primary_phraser() -> QwenPhraser | None:
    """未配置百炼密钥时返回 None，理由直接走模板通道。"""

    return QwenPhraser(_client) if _client.configured else None


phraser = PhraserRouter(resolve_primary_phraser())
