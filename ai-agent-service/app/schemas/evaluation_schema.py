from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.resource_schema import ModelRuntime, ResourceEvidence, SafetyReview, TaskStatus


MistakePattern = Literal[
    "concept_confusion",
    "process_gap",
    "boundary_condition_missing",
    "answer_omission",
]


class QuizQuestionResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question_id: int = Field(ge=1)
    knowledge_point: str = Field(min_length=2, max_length=120)
    stem: str = Field(min_length=2, max_length=800)
    submitted_answer: str = Field(max_length=1000)
    correct_answer: str = Field(min_length=1, max_length=1000)
    explanation: str = Field(min_length=2, max_length=1200)
    correct: bool


class EvaluationAnalyzeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    quiz_id: int = Field(ge=1)
    course_id: int = Field(ge=1)
    score: int = Field(ge=0, le=100)
    question_results: list[QuizQuestionResult] = Field(min_length=1, max_length=3)
    student_profile: dict[str, object] = Field(default_factory=dict)


class KnowledgeMasteryResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    knowledge_point: str = Field(min_length=2, max_length=120)
    mastery_score: int = Field(ge=0, le=100)
    level: Literal["优秀", "良好", "一般", "待补救"]
    correct_count: int = Field(ge=0, le=3)
    total_count: int = Field(ge=1, le=3)


class EvaluationReport(BaseModel):
    model_config = ConfigDict(extra="forbid")

    overall_score: int = Field(ge=0, le=100)
    mastery: list[KnowledgeMasteryResult] = Field(min_length=1, max_length=3)
    weak_points: list[str] = Field(max_length=3)
    mistake_patterns: list[MistakePattern] = Field(max_length=4)
    next_actions: list[str] = Field(min_length=1, max_length=3)
    evidence_chunk_ids: list[str] = Field(min_length=1, max_length=6)


class EvaluationAnalyzeResponse(BaseModel):
    task_id: str
    status: TaskStatus
    planned_agents: list[str]
    safety_status: Literal["passed"]
    evaluation: EvaluationReport
    evidence: list[ResourceEvidence]
    safety: SafetyReview
    generation_mode: Literal["real_model", "deterministic_fallback"]
    model_runtime: ModelRuntime
