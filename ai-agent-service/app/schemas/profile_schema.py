from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.resource_schema import ModelRuntime, SafetyReview


class ProfileExtractRequest(BaseModel):
    student_id: str
    message: str
    course_ids: list[int]


class ProfileCourseProgress(BaseModel):
    model_config = ConfigDict(extra="forbid")

    course_id: int
    status: Literal["unknown", "weak", "learning", "mastered"]


class ProfileIncrement(BaseModel):
    """Strict, de-identified profile payload returned by ProfileAgent."""

    model_config = ConfigDict(extra="forbid")

    major: str
    grade: str
    target_courses: list[int]
    knowledge_base: dict[str, Literal["unknown", "beginner", "medium", "advanced"]]
    course_progress: list[ProfileCourseProgress]
    learning_goal: str
    weak_points: list[str]
    resource_preference: list[str]
    cognitive_style: list[str]
    mistake_patterns: list[str]
    learning_pace: str
    confidence_score: float = Field(ge=0, le=1)
    updated_reason: str


class ProfileExtractResponse(BaseModel):
    task_id: str
    student_id: str
    profile: dict[str, object]
    extracted: dict[str, object]
    dimensions: list[str]
    confidence: float
    generation_mode: Literal["real_model", "deterministic_fallback"]
    safety: SafetyReview
    model_runtime: ModelRuntime
