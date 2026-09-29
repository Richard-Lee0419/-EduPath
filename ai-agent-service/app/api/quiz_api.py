from fastapi import APIRouter

from app.agents.orchestrator_agent import orchestrator
from app.schemas.quiz_schema import QuizGenerateRequest, QuizGenerateResponse

router = APIRouter(prefix="/quiz", tags=["quiz"])


@router.post("/generate", response_model=QuizGenerateResponse)
def generate(request: QuizGenerateRequest) -> QuizGenerateResponse:
    return orchestrator.generate_quiz(request)
