from fastapi import APIRouter

from app.agents.orchestrator_agent import orchestrator
from app.schemas.path_schema import (
    PathGenerateRequest,
    PathGenerateResponse,
    PathReplanRequest,
    PathReplanResponse,
)

router = APIRouter(prefix="/path", tags=["path"])


@router.post("/generate", response_model=PathGenerateResponse)
def generate_path(request: PathGenerateRequest) -> PathGenerateResponse:
    return orchestrator.generate_path(request)


@router.post("/replan", response_model=PathReplanResponse)
def replan_path(request: PathReplanRequest) -> PathReplanResponse:
    return orchestrator.replan_path(request)
