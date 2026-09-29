from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.resource_schema import Difficulty, ModelRuntime, ResourceEvidence, SafetyReview, TaskStatus


class QuizGenerateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    course_id: int = Field(default=1, ge=1)
    knowledge_point_ids: list[int] = Field(default_factory=list, max_length=6)
    knowledge_points: list[str] = Field(default_factory=list, max_length=6)
    difficulty: Difficulty = "basic"
    question_count: int = Field(default=3, ge=1, le=3)
    student_profile: dict[str, object] = Field(default_factory=dict)


class QuizQuestionDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question_order: int = Field(ge=1, le=3)
    type: Literal["single_choice"] = "single_choice"
    difficulty: Difficulty
    knowledge_point: str = Field(min_length=2, max_length=120)
    stem: str = Field(min_length=8, max_length=800)
    options: list[str] = Field(min_length=4, max_length=4)
    answer: Literal["A", "B", "C", "D"]
    explanation: str = Field(min_length=12, max_length=1200)
    evidence_chunk_ids: list[str] = Field(min_length=1, max_length=3)


class QuizDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=2, max_length=160)
    questions: list[QuizQuestionDraft] = Field(min_length=1, max_length=3)


class QuizGenerateResponse(BaseModel):
    task_id: str
    status: TaskStatus
    planned_agents: list[str]
    safety_status: Literal["passed"]
    quiz: QuizDraft
    evidence: list[ResourceEvidence]
    safety: SafetyReview
    generation_mode: Literal["real_model", "deterministic_fallback"]
    model_runtime: ModelRuntime
