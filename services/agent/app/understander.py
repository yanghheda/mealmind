"""需求理解器（PRD 5.2）：qwen3.8 模型通道 + 关键字规则兜底。

两条通道输出同一个偏好结构，标签词表与种子数据（deploy/sql/20_seed.sql 的 dish_tag）
保持同一口径，保证「需求标签 ∩ 菜品标签」的命中判断在两端口径一致。
模型失败、超时或未配置密钥时降级为关键字规则解析（技术方案 §6.4）。
"""

from __future__ import annotations

import logging
import re

from dataclasses import dataclass

from .config import settings
from .degrade import Transition, degrade_controller
from .llm import LlmUsage, QwenClient
from .models import Profile

logger = logging.getLogger("mealmind.agent.understander")

# 辣度：与 dish_tag SPICY 取值一致，另保留「辣」表示不限辣度
_SPICY_NONE = ("不辣", "不吃辣", "不要辣", "别辣", "不能吃辣")
_SPICY_MILD = ("微辣", "少辣", "不要太辣", "别太辣", "不太辣", "微微辣")
_SPICY_HOT = ("特辣", "变态辣", "超辣", "巨辣")
_SPICY_MID = ("中辣", "正常辣")
_SPICY_ANY = ("辣", "吃辣", "带辣")

# 风味：与 dish_tag FLAVOR 取值一致
_FLAVOR_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("麻辣", ("麻辣", "麻椒", "椒麻", "藤椒", "花椒")),
    ("香辣", ("香辣", "剁椒")),
    ("酸辣", ("酸辣", "泡椒", "酸豆角")),
    ("卤味", ("卤味", "卤")),
    ("咖喱", ("咖喱",)),
    ("孜然", ("孜然", "烧烤", "烤串", "烤的")),
    ("蒜香", ("蒜香", "蒜蓉")),
    ("酸甜", ("酸甜", "糖醋")),
    ("甜口", ("甜的", "甜口", "甜一点")),
    ("清淡", ("清淡", "清汤", "水煮", "清爽", "少油", "不油腻")),
    ("麻酱", ("麻酱", "芝麻酱")),
    ("咸鲜", ("咸鲜", "鲜咸", "咸口")),
]

# 健康诉求：映射到 dish_tag HEALTH 的正向标签
_HEALTH_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("低卡", ("低卡", "低脂", "减脂", "减重", "无糖", "轻食", "沙拉", "健康", "轻负担")),
    ("高蛋白", ("高蛋白", "增肌", "蛋白质")),
]

# 忌口词表：映射到 dish_tag INGREDIENT 取值
_AVOID_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("香菜", ("香菜", "芫荽")),
    ("葱", ("葱", "洋葱")),
    ("蒜", ("蒜",)),
    ("花生", ("花生",)),
    ("辣椒", ("辣椒", "辣的")),
    ("牛肉", ("牛肉", "牛腩", "肥牛", "牛柳")),
    ("猪肉", ("猪肉", "五花", "排骨", "肉丝", "肉末")),
    ("鸡肉", ("鸡肉", "鸡腿", "鸡翅", "鸡排")),
    ("鸭肉", ("鸭肉", "鸭脖", "鸭翅")),
    ("虾", ("虾",)),
    ("鱼", ("鱼", "三文鱼", "鳗鱼")),
    ("蟹", ("蟹",)),
    ("蛋", ("鸡蛋", "蛋", "蛋黄")),
    ("乳制品", ("牛奶", "奶", "芝士", "黄油", "酸奶")),
    ("豆制品", ("豆制品", "豆腐", "豆干", "腐竹", "豆浆")),
    ("菌菇", ("香菇", "菌菇", "金针菇", "木耳", "蘑菇")),
    ("内脏", ("内脏", "肥肠", "毛肚", "鸭肠", "猪肝", "鸡杂")),
    ("米", ("米饭", "米线", "饭")),
    ("面", ("面条", "拉面", "面")),
    ("粉", ("米粉", "河粉", "粉丝", "粉条")),
    ("蔬菜", ("蔬菜", "青菜", "生菜", "黄瓜")),
]

_AVOID_PREFIX = ("不吃", "不要", "别放", "不加", "不放", "忌口", "忌", "去掉", "去", "少放")
_AVOID_SUFFIX = ("过敏",)

_SCENE_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("健康轻食", ("减脂", "健康", "轻食", "低卡", "沙拉", "无糖")),
    ("加班", ("加班", "赶工", "赶due", "宵夜", "夜宵")),
    ("一人食", ("一人食", "一个人", "单人", "自己吃")),
    ("多人分享", ("多人", "聚餐", "一起", "同事", "朋友", "分享")),
]

_MEAL_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("早餐", ("早餐", "早饭")),
    ("午餐", ("午餐", "午饭", "中饭")),
    ("下午茶", ("下午茶", "下午", "茶点")),
    ("晚餐", ("晚餐", "晚饭")),
    ("夜宵", ("夜宵", "宵夜", "深夜", "半夜")),
]

_BUDGET_PATTERNS = (
    re.compile(r"(?:不超过|最多|封顶)\s*(\d{1,3})"),
    re.compile(r"预算\s*(\d{1,3})"),
    re.compile(r"(\d{1,3})\s*(?:元|块)(?:钱)?\s*(?:以内|以下|之内|内|左右|上下)?"),
    re.compile(r"(\d{1,3})\s*以内"),
)

_DELIVERY_PATTERNS = (
    re.compile(r"半小时"),
    re.compile(r"(?:一小时|1\s*小时|一个小时)"),
    re.compile(r"(\d{1,3})\s*(?:分钟|分)(?:钟)?(?:内|以内|以下)?"),
)


class KeywordUnderstander:
    """把一句自然语言需求解析为 PRD 5.2 的偏好结构；确定性、无外部依赖。"""

    def parse(self, text: str) -> Profile:
        normalized = text.strip()
        return Profile(
            taste_tags=self._taste_tags(normalized),
            budget_max=self._budget(normalized),
            avoid_ingredients=self._avoid(normalized),
            max_delivery_min=self._delivery(normalized),
            scene=self._scene(normalized),
            meal_period=self._meal_period(normalized),
            notes=normalized[:100],
            open_questions=[],
        )

    # -- 各字段的解析规则（全部可单测的纯函数） --------------------------------

    def _taste_tags(self, text: str) -> list[str]:
        tags: list[str] = []

        if any(k in text for k in _SPICY_NONE):
            tags.append("不辣")
        elif any(k in text for k in _SPICY_MILD):
            tags.append("微辣")
        elif any(k in text for k in _SPICY_HOT):
            tags.append("特辣")
        elif any(k in text for k in _SPICY_MID):
            tags.append("中辣")
        elif any(k in text for k in _SPICY_ANY):
            tags.append("辣")

        for tag, keywords in _FLAVOR_KEYWORDS:
            if any(k in text for k in keywords):
                tags.append(tag)
                break

        for tag, keywords in _HEALTH_KEYWORDS:
            if any(k in text for k in keywords):
                tags.append(tag)

        # 去重并守住「最多 5 个」的上限
        return list(dict.fromkeys(tags))[:5]

    def _budget(self, text: str) -> float | None:
        for pattern in _BUDGET_PATTERNS:
            match = pattern.search(text)
            if match:
                value = int(match.group(1))
                if 1 <= value <= 200:
                    return float(value)
        return None

    def _delivery(self, text: str) -> int | None:
        for pattern in _DELIVERY_PATTERNS:
            match = pattern.search(text)
            if not match:
                continue
            if pattern.pattern == "半小时":
                return 30
            if match.lastindex is None:
                return 60
            value = int(match.group(1))
            if 10 <= value <= 60:
                return value
        return None

    def _avoid(self, text: str) -> list[str]:
        hits: list[str] = []
        for tag, keywords in _AVOID_KEYWORDS:
            for keyword in keywords:
                if self._is_negated(text, keyword):
                    hits.append(tag)
                    break
        return hits

    @staticmethod
    def _is_negated(text: str, keyword: str) -> bool:
        for prefix in _AVOID_PREFIX:
            if re.search(re.escape(prefix) + r"\s*" + re.escape(keyword), text):
                return True
        for suffix in _AVOID_SUFFIX:
            if re.search(re.escape(keyword) + r"\s*" + re.escape(suffix), text):
                return True
        return False

    @staticmethod
    def _scene(text: str) -> str | None:
        for scene, keywords in _SCENE_KEYWORDS:
            if any(k in text for k in keywords):
                return scene
        return None

    @staticmethod
    def _meal_period(text: str) -> str:
        for period, keywords in _MEAL_KEYWORDS:
            if any(k in text for k in keywords):
                return period
        from datetime import datetime

        hour = datetime.now().hour
        if 5 <= hour < 10:
            return "早餐"
        if 10 <= hour < 14:
            return "午餐"
        if 14 <= hour < 17:
            return "下午茶"
        if 17 <= hour < 21:
            return "晚餐"
        return "夜宵"


# -- 标签词表：模型输出必须落在这个闭集内，否则与菜品标签对不上 ---------------

_TASTE_VOCAB: tuple[str, ...] = (
    ("不辣", "微辣", "中辣", "特辣", "辣")
    + tuple(tag for tag, _ in _FLAVOR_KEYWORDS)
    + tuple(tag for tag, _ in _HEALTH_KEYWORDS)
)
_AVOID_VOCAB: tuple[str, ...] = tuple(tag for tag, _ in _AVOID_KEYWORDS)
_SCENE_VOCAB: tuple[str, ...] = tuple(tag for tag, _ in _SCENE_KEYWORDS)
_MEAL_VOCAB: tuple[str, ...] = tuple(tag for tag, _ in _MEAL_KEYWORDS)

# 项目全部已知标签：表达器据此校验「理由是否引用了未给出的标签」
KNOWN_TAGS: frozenset[str] = frozenset(_TASTE_VOCAB + _AVOID_VOCAB)

_SYSTEM_PROMPT = (
    "你是外卖需求解析器。把用户的一句话需求解析为 JSON 对象，字段如下：\n"
    f"- taste_tags：口味标签数组，只能从这些值里选：{'、'.join(_TASTE_VOCAB)}\n"
    "- budget_max：预算上限（数字，单位元），未提到填 null\n"
    f"- avoid_ingredients：忌口食材数组，只能从这些值里选：{'、'.join(_AVOID_VOCAB)}\n"
    "- max_delivery_min：期望最长送达时间（整数分钟），未提到填 null\n"
    f"- scene：场景，只能是其中之一：{'、'.join(_SCENE_VOCAB)}，没有则填 null\n"
    f"- meal_period：餐段，只能是其中之一：{'、'.join(_MEAL_VOCAB)}，未提到则填 null\n"
    "只输出 JSON，不要解释、不要 Markdown 代码块。"
)


class QwenUnderstander:
    """qwen3.8 理解器：模型输出经词表校验与类型归一化后回填 Profile。"""

    def __init__(self, client: QwenClient) -> None:
        self._client = client

    def parse(self, text: str) -> tuple[Profile, LlmUsage]:
        result = self._client.chat_json(_SYSTEM_PROMPT, text)
        data = result.data
        taste = [t for t in _as_list(data.get("taste_tags")) if t in _TASTE_VOCAB]
        avoid = [t for t in _as_list(data.get("avoid_ingredients")) if t in _AVOID_VOCAB]
        scene = data.get("scene") if data.get("scene") in _SCENE_VOCAB else None
        meal = data.get("meal_period") if data.get("meal_period") in _MEAL_VOCAB else None
        if meal is None:
            # 与规则通道口径一致：未提及餐段时按当前时段推断
            meal = KeywordUnderstander._meal_period(text)
        profile = Profile(
            taste_tags=list(dict.fromkeys(taste))[:5],
            budget_max=_as_number(data.get("budget_max"), 1, 200),
            avoid_ingredients=list(dict.fromkeys(avoid)),
            max_delivery_min=_as_int(data.get("max_delivery_min"), 5, 120),
            scene=scene,
            meal_period=meal,
            notes=text.strip()[:100],
            open_questions=[],
        )
        return profile, result.usage


@dataclass(frozen=True)
class UnderstandResult:
    """一次理解的结果：偏好结构 + 是否走了兜底 + 档位迁移（有则上报 degraded）+ 模型用量。"""

    profile: Profile
    from_fallback: bool
    transition: Transition | None = None
    usage: LlmUsage | None = None


class UnderstanderRouter:
    """按通道选择理解实现：模型可用且未降级时走模型，否则走关键字规则。"""

    def __init__(self, primary: QwenUnderstander | None,
                 fallback: KeywordUnderstander) -> None:
        self._primary = primary
        self._fallback = fallback

    @property
    def primary_enabled(self) -> bool:
        return self._primary is not None

    def parse(self, text: str) -> UnderstandResult:
        if self._primary is None:
            return UnderstandResult(self._fallback.parse(text), True)
        try:
            profile, usage = self._primary.parse(text)
            return UnderstandResult(profile, False, degrade_controller.record_understand(True), usage)
        except Exception as exc:  # 超时 / 网络 / 非 200 / 输出不合法：整体降级
            logger.warning("primary understander failed, fallback to rules: %s", exc)
            transition = degrade_controller.record_understand(False)
            return UnderstandResult(self._fallback.parse(text), True, transition)


def _as_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str)]


def _as_number(value: object, low: float, high: float) -> float | None:
    try:
        number = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    return number if low <= number <= high else None


def _as_int(value: object, low: int, high: int) -> int | None:
    number = _as_number(value, low, high)
    return int(number) if number is not None else None


_client = QwenClient(
    api_key=settings.dashscope_api_key,
    base_url=settings.qwen_base_url,
    model=settings.qwen_model,
    timeout_s=settings.understand_timeout_s,
    enable_thinking=False,
)


def resolve_primary_understander() -> QwenUnderstander | None:
    """未配置百炼密钥时返回 None，链路直接走关键字规则通道（M2 基线）。"""

    return QwenUnderstander(_client) if _client.configured else None


understander = UnderstanderRouter(resolve_primary_understander(), KeywordUnderstander())
degrade_controller.set_channel_available("understander", understander.primary_enabled)