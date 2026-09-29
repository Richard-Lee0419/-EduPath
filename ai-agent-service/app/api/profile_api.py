from fastapi import APIRouter

from app.agents.orchestrator_agent import orchestrator
from app.schemas.profile_schema import ProfileExtractRequest, ProfileExtractResponse

router = APIRouter(prefix="/profile", tags=["profile"])


@router.post("/extract", response_model=ProfileExtractResponse)
def extract_profile(request: ProfileExtractRequest) -> ProfileExtractResponse:
    return orchestrator.extract_profile(request)
