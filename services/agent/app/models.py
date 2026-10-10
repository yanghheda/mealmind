"""决策链路的输入输出模型。

字段命名对齐 docs/tech-design.md 与 PRD 5.2：偏好结构（profile）沿用 PRD 的 snake_case，
其余与业务服务 DTO 的 camelCase 保持一致。
"""

from pydantic import BaseModel, Field


class Profile(BaseModel):
    """PRD 5.2 需求理解输出。"""

    taste_tags: list[str] = Field(default_factory=list)
    budget_max: float | None = None
    avoid_ingredients: list[str] = Field(default_factory=list)
    max_delivery_min: int | None = None
    scene: str | None = None
    meal_period: str | None = None
    notes: str | None = None
    open_questions: list[str] = Field(default_factory=list)


class Tag(BaseModel):
    tag: str
    tagType: str


class Candidate(BaseModel):
    """业务服务过滤后回填的候选（含打所需的全部事实，Agent 不查任何存储）。"""

    dishId: str
    name: str
    merchantId: str
    merchantName: str
    price: float
    imageUrl: str | None = None
    deliveryMinutes: int
    merchantRating: float
    dishMonthlySales: int
    categoryName: str | None = None
    tags: list[Tag] = Field(default_factory=list)


class DimScore(BaseModel):
    """单个维度的分数与置信度（0–10 / 0–1）。"""

    dim: str
    value: float
    confidence: float


class CandidateScore(BaseModel):
    dishId: str
    dims: list[DimScore]
    conflictGuard: float
    mode: str


class DecideRequest(BaseModel):
    """POST /internal/decide 请求体。"""

    runId: str
    text: str
    mode: str = "GLOBAL"
    merchantId: str | None = None


class CandidatesRequest(BaseModel):
    """业务服务执行约束过滤后的回填体（协作式编排：过滤节点由业务服务执行）。"""

    candidates: list[Candidate] = Field(default_factory=list)


class ScoreRequest(BaseModel):
    """POST /internal/score 请求体（评测用：业务服务直接提供候选，跳过 SSE 编排）。"""

    text: str
    candidates: list[Candidate] = Field(default_factory=list)
    # 评测指标不依赖推荐理由，置 false 可跳过表达器，避免无谓的生成式模型调用
    withReason: bool = True


class ScoredCard(BaseModel):
    """评测打分返回的逐菜结果（与 graph.py _card_payload 对齐，但包含理由）。"""

    dishId: str
    name: str
    merchantId: str
    merchantName: str
    price: float
    imageUrl: str | None = None
    dims: list[DimScore]
    conflictGuard: float
    mode: str
    reason: str


class ScoreResponse(BaseModel):
    profile: Profile
    cards: list[ScoredCard]