from fastapi import APIRouter

from app.agents.orchestrator_agent import orchestrator
from app.schemas.evaluation_schema import EvaluationAnalyzeRequest, EvaluationAnalyzeResponse

router = APIRouter(prefix="/evaluation", tags=["evaluation"])


@router.post("/analyze", response_model=EvaluationAnalyzeResponse)
def analyze(request: EvaluationAnalyzeRequest) -> EvaluationAnalyzeResponse:
    return orchestrator.analyze_evaluation(request)


@router.get("/report")
def report() -> dict[str, object]:
    return orchestrator.evaluation_report()
