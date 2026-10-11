"""澄清提问（PRD §5.6 追问优化，M4 实施方案 W2）：确定性规则，零额外模型调用。

触发口径：
    ask_taste  = 无口味标签且无场景     → 方向选择卡
    ask_budget = 无预算 且 同样没有口味/场景 → 预算选择卡（与方向卡同轮出现）
    最多 2 问、每 run 最多一轮；MERCHANT（本店）模式不追问。
预算追问限定在「整句没有任何方向」时：像「减脂期想吃清淡的，40 分钟内送到」这类
已表达明确方向的需求不再打断用户（对齐实施方案「欢迎态 3 条示例不触发追问」的验收口径）。

回答合并只补缺失字段：口味取并集、预算仅在为空时写入，不覆盖用户原话已说出的信息。
"""

from __future__ import annotations

from typing import Any

from .models import Profile

TASTE_QUESTION_ID = "q_taste"
BUDGET_QUESTION_ID = "q_budget"

# 选项 value 即要写入 profile 的值（dish_tag 词表口径）；value 为 null 表示不填（跳过该问）
TASTE_OPTIONS: list[dict[str, Any]] = [
    {"id": "mild", "label": "微辣", "value": "微辣"},
    {"id": "light", "label": "清淡", "value": "清淡"},
    {"id": "spicy", "label": "麻辣", "value": "麻辣"},
    {"id": "any", "label": "都行，看推荐", "value": None},
]
BUDGET_OPTIONS: list[dict[str, Any]] = [
    {"id": "b20", "label": "20 元内", "value": "20"},
    {"id": "b30", "label": "30 元内", "value": "30"},
    {"id": "b50", "label": "50 元内", "value": "50"},
    {"id": "any", "label": "不限预算", "value": None},
]


def questions_for(profile: Profile, mode: str | None) -> list[dict[str, Any]]:
    """按确定性规则生成澄清问题；返回空列表表示不追问。"""

    if (mode or "").upper() == "MERCHANT":
        return []
    no_direction = not profile.taste_tags and not profile.scene
    questions: list[dict[str, Any]] = []
    if no_direction:
        questions.append({
            "id": TASTE_QUESTION_ID,
            "type": "TASTE",
            "title": "想吃什么口味的方向？",
            "options": TASTE_OPTIONS,
            "skippable": True,
        })
    if profile.budget_max is None and no_direction:
        questions.append({
            "id": BUDGET_QUESTION_ID,
            "type": "BUDGET",
            "title": "预算大概多少？",
            "options": BUDGET_OPTIONS,
            "skippable": True,
        })
    return questions


def merge(profile: Profile, questions: list[dict[str, Any]], answers: list[dict[str, Any]] | None) -> Profile:
    """把回答合并进 profile：口味取并集、预算仅在为空时写入（话题外的选项一律忽略）。"""

    if not answers:
        return profile
    by_id = {question["id"]: question for question in questions}
    merged = profile
    for answer in answers:
        question = by_id.get(str(answer.get("questionId") or ""))
        if question is None:
            continue
        option = next((item for item in question["options"] if item["id"] == answer.get("optionId")), None)
        if option is None or option.get("value") is None:
            continue
        value = option["value"]
        if question["type"] == "TASTE":
            tags = list(merged.taste_tags)
            if value not in tags:
                tags.append(value)
            merged = merged.model_copy(update={"taste_tags": tags})
        elif question["type"] == "BUDGET" and merged.budget_max is None:
            try:
                merged = merged.model_copy(update={"budget_max": float(value)})
            except (TypeError, ValueError):
                continue
    return merged