from typing import Literal

from pydantic import BaseModel, Field

ResourceType = Literal[
    "lecture",
    "mindmap",
    "quiz",
    "codelab",
    "animation_script",
    "flowchart",
    "reading",
]
Difficulty = Literal["basic", "medium", "advanced"]
TaskStatus = Literal["pending", "running", "success", "failed", "cancelled"]


class ResourceGenerateRequest(BaseModel):
    course_id: int
    knowledge_point_ids: list[int] = Field(default_factory=list)
    knowledge_points: list[str] = Field(default_factory=list)
    goal: str = ""
    resource_types: list[ResourceType]
    difficulty: Difficulty = "medium"
    student_profile: dict[str, object] = Field(default_factory=dict)


class ResourceEvidence(BaseModel):
    chunk_id: str
    title: str
    content: str
    score: float
    source: str


class SafetyReview(BaseModel):
    passed: bool
    risk_level: Literal["low", "medium", "high"]
    issues: list[str] = Field(default_factory=list)
    suggestions: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0, le=1)


class QualityDimensionScore(BaseModel):
    score: float = Field(ge=0, le=100)
    weight: float = Field(gt=0, le=1)
    passed: bool
    findings: list[str] = Field(default_factory=list)


class ResourceQualityEvaluation(BaseModel):
    evaluator_version: str = "resource-quality-v1"
    total_score: float = Field(ge=0, le=100)
    grade: Literal["A", "B", "C", "D"]
    gate_passed: bool
    dimensions: dict[str, QualityDimensionScore]
    issues: list[str] = Field(default_factory=list)
    recommendations: list[str] = Field(default_factory=list)


class GeneratedResource(BaseModel):
    title: str
    resource_type: ResourceType
    content_format: str = "markdown"
    content: str
    summary: str
    difficulty: Difficulty
    knowledge_points: list[str]
    personalized_reason: str
    estimated_minutes: int
    profile_fingerprint: str
    evidence_chunk_ids: list[str] = Field(default_factory=list)
    quality_evaluation: ResourceQualityEvaluation | None = None


class ModelCallRecord(BaseModel):
    agent: str
    provider: str
    model: str
    duration_ms: int
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    request_id: str = ""


class ModelRuntime(BaseModel):
    configured: bool
    real_model_used: bool
    mode: Literal["real_model", "deterministic_fallback"]
    provider: str = ""
    model: str = ""
    call_count: int = 0
    total_tokens: int = 0
    duration_ms: int = 0
    quality_revision_rounds: int = 0
    safety_feedback_revision_rounds: int = 0
    quality_feedback_revision_rounds: int = 0
    quality_revised_resource_count: int = 0
    calls: list[ModelCallRecord] = Field(default_factory=list)


class ResourceTaskCreated(BaseModel):
    task_id: str
    status: TaskStatus
    planned_agents: list[str]


class ResourceGenerateResponse(BaseModel):
    task_id: str
    status: TaskStatus
    planned_agents: list[str]
    safety_status: str
    resources: list[GeneratedResource]
    evidence: list[ResourceEvidence]
    safety: SafetyReview
    model_runtime: ModelRuntime


class QualityRegressionEvidence(BaseModel):
    chunk_id: str
    title: str
    content: str
    score: float
    source: str
    knowledge_point: str = ""


class ResourceRepairRequest(BaseModel):
    repair_id: str
    resource_id: str
    course_id: int
    resource: GeneratedResource
    evidence: list[QualityRegressionEvidence] = Field(default_factory=list)
    safety: SafetyReview
    baseline_evaluation: ResourceQualityEvaluation
    current_evaluation: ResourceQualityEvaluation
    regressed_dimensions: list[str] = Field(default_factory=list)
    expected_difficulty: Difficulty


class ResourceRepairResponse(BaseModel):
    repair_id: str
    resource_id: str
    status: Literal["candidate_ready", "quality_rejected"]
    candidate_resource: GeneratedResource
    safety: SafetyReview
    quality_evaluation: ResourceQualityEvaluation
    recovery_score_delta: float
    improved_dimensions: list[str] = Field(default_factory=list)
    target_dimensions_passed: bool
    model_runtime: ModelRuntime


class ResourceQualityRegressionRequest(BaseModel):
    resource_id: str
    course_id: int
    resource: GeneratedResource
    evidence: list[QualityRegressionEvidence] = Field(default_factory=list)
    safety: SafetyReview
    baseline_evaluation: ResourceQualityEvaluation
    expected_difficulty: Difficulty


class ResourceQualityRegressionResponse(BaseModel):
    resource_id: str
    evaluator_version: str
    baseline_evaluation: ResourceQualityEvaluation
    current_evaluation: ResourceQualityEvaluation
    score_delta: float
    gate_changed: bool
    evaluator_changed: bool
    regressed_dimensions: list[str] = Field(default_factory=list)
    regression_detected: bool
