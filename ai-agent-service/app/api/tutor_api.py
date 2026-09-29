from fastapi import APIRouter

from app.agents.orchestrator_agent import orchestrator
from app.schemas.tutor_schema import TutorChatRequest, TutorChatResponse

router = APIRouter(prefix="/tutor", tags=["tutor"])


@router.post("/chat", response_model=TutorChatResponse)
def chat(request: TutorChatRequest) -> TutorChatResponse:
    return orchestrator.chat_tutor(request)
