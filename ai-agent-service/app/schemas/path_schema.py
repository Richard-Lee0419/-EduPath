from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.schemas.resource_schema import (
    Difficulty,
    ModelRuntime,
    ResourceEvidence,
    ResourceType,
    SafetyReview,
    TaskStatus,
)


class PathGenerateRequest(BaseModel):
    course_ids: list[int] = Field(default_factory=lambda: [1, 2])
    target: str = "掌握核心知识点"
    days: int = Field(default=14, ge=1, le=30)
    daily_minutes: int = Field(default=40, ge=10, le=180)
    student_profile: dict[str, object] = Field(default_factory=dict)


class LearningPathTask(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: ResourceType
    resource_id: str
    title: str = Field(min_length=2, max_length=120)
    estimated_minutes: int = Field(ge=5, le=180)


class LearningPathDay(BaseModel):
    model_config = ConfigDict(extra="forbid")

    day: int = Field(ge=1, le=30)
    theme: str = Field(min_length=2, max_length=120)
    difficulty: Difficulty
    reason: str = Field(min_length=8, max_length=500)
    tasks: list[LearningPathTask] = Field(min_length=1, max_length=4)
    expected_outcome: str = Field(min_length=8, max_length=300)
    evidence_chunk_ids: list[str] = Field(min_length=1, max_length=4)


class LearningPathPlan(BaseModel):
    model_config = ConfigDict(extra="forbid")

    path_id: str
    path_title: str = Field(min_length=2, max_length=160)
    target: str = Field(min_length=2, max_length=300)
    course_ids: list[int] = Field(min_length=1)
    daily_minutes: int = Field(ge=10, le=180)
    daily_plan: list[LearningPathDay] = Field(min_length=1, max_length=30)
    personalization_summary: str = Field(min_length=8, max_length=500)
    profile_fingerprint: str
    adjustment_strategy: str = Field(min_length=8, max_length=500)
    evidence_chunk_ids: list[str] = Field(min_length=1)


class PathGenerateResponse(BaseModel):
    task_id: str
    status: TaskStatus
    planned_agents: list[str]
    safety_status: str
    path: LearningPathPlan
    evidence: list[ResourceEvidence]
    safety: SafetyReview
    generation_mode: Literal["real_model", "deterministic_fallback"]
    model_runtime: ModelRuntime


class PathEvaluationInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    quiz_id: int = Field(ge=1)
    course_id: int = Field(ge=1)
    overall_score: int = Field(ge=0, le=100)
    weak_points: list[str] = Field(max_length=3)
    mistake_patterns: list[str] = Field(max_length=4)
    next_actions: list[str] = Field(min_length=1, max_length=3)
    source_task_id: str = Field(min_length=1, max_length=120)


class PathBehaviorSignalInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    trigger_key: str = Field(min_length=8, max_length=255)
    risk_score: int = Field(ge=3, le=20)
    reasons: list[str] = Field(min_length=1, max_length=6)
    weak_points: list[str] = Field(default_factory=list, max_length=3)
    metrics: dict[str, object] = Field(default_factory=dict)
    pacing: dict[str, object] = Field(default_factory=dict)


class PathReplanRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    course_ids: list[int] = Field(default_factory=lambda: [1, 2], min_length=1)
    target: str = Field(default="根据最近测评动态调整学习路径", min_length=2, max_length=300)
    days: int = Field(default=3, ge=1, le=3)
    daily_minutes: int = Field(default=40, ge=10, le=180)
    student_profile: dict[str, object] = Field(default_factory=dict)
    current_path: dict[str, object] = Field(default_factory=dict)
    trigger: Literal["quiz_evaluation", "behavior_signal"] = "quiz_evaluation"
    evaluation: PathEvaluationInput | None = None
    behavior_signal: PathBehaviorSignalInput | None = None

    @model_validator(mode="after")
    def validate_trigger_context(self) -> "PathReplanRequest":
        if self.trigger == "quiz_evaluation" and self.evaluation is None:
            raise ValueError("quiz_evaluation trigger requires evaluation")
        if self.trigger == "behavior_signal" and self.behavior_signal is None:
            raise ValueError("behavior_signal trigger requires behavior_signal")
        if self.trigger == "quiz_evaluation" and self.behavior_signal is not None:
            raise ValueError("quiz_evaluation trigger must not include behavior_signal")
        if self.trigger == "behavior_signal" and self.evaluation is not None:
            raise ValueError("behavior_signal trigger must not include evaluation")
        return self


class PathAdjustment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action: Literal["insert_remediation", "reorder", "adjust_difficulty", "advance"]
    knowledge_point: str = Field(min_length=2, max_length=120)
    reason: str = Field(min_length=8, max_length=300)


class PathReplanResponse(PathGenerateResponse):
    previous_path_id: str
    trigger: Literal["quiz_evaluation", "behavior_signal"]
    evaluation_task_id: str = ""
    behavior_trigger_key: str = ""
    changes: list[PathAdjustment] = Field(min_length=1, max_length=6)
