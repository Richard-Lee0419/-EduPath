from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.resource_schema import ModelRuntime, ResourceEvidence, SafetyReview, TaskStatus


AnswerMode = Literal["hint", "socratic", "step_by_step", "summary", "code_first"]


class TutorChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    course_id: int = Field(default=1, ge=1)
    question: str = Field(min_length=2, max_length=2000)
    answer_mode: AnswerMode = "step_by_step"


class TutorCitation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer_fragment: str = Field(min_length=4, max_length=500)
    evidence_chunk_ids: list[str] = Field(min_length=1, max_length=3)


class TutorAnswerDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer_markdown: str = Field(min_length=20, max_length=12000)
    steps: list[str] = Field(min_length=1, max_length=8)
    citations: list[TutorCitation] = Field(min_length=1, max_length=10)
    confidence: float = Field(ge=0, le=1)


class TutorChatResponse(BaseModel):
    task_id: str
    status: TaskStatus
    planned_agents: list[str]
    safety_status: Literal["passed"]
    answer: str
    answer_markdown: str
    steps: list[str]
    citations: list[TutorCitation]
    confidence: float = Field(ge=0, le=1)
    evidence: list[ResourceEvidence]
    safety: SafetyReview
    generation_mode: Literal["real_model", "deterministic_fallback"]
    model_runtime: ModelRuntime
