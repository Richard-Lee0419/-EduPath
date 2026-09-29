from fastapi import APIRouter

from app.agents.orchestrator_agent import orchestrator
from app.schemas.resource_schema import (
    ResourceGenerateRequest,
    ResourceGenerateResponse,
    ResourceQualityRegressionRequest,
    ResourceQualityRegressionResponse,
    ResourceRepairRequest,
    ResourceRepairResponse,
    ResourceTaskCreated,
)
from app.rag.retriever import RagEvidence
from app.services.resource_quality_evaluator import ResourceQualityEvaluator

router = APIRouter(prefix="/resource", tags=["resource"])


@router.post("/generate", response_model=ResourceGenerateResponse)
def generate_resource(request: ResourceGenerateRequest) -> ResourceGenerateResponse:
    return orchestrator.generate_resource(request)


@router.post("/tasks", response_model=ResourceTaskCreated)
def create_resource_task(request: ResourceGenerateRequest) -> ResourceTaskCreated:
    """Start a real asynchronous multi-agent resource workflow."""

    return orchestrator.start_resource_task(request)


@router.post("/repair", response_model=ResourceRepairResponse)
def repair_resource(request: ResourceRepairRequest) -> ResourceRepairResponse:
    """Generate and re-evaluate one approved repair candidate without publishing it."""

    return orchestrator.repair_resource(request)


@router.post("/quality/regression", response_model=ResourceQualityRegressionResponse)
def regress_resource_quality(
    request: ResourceQualityRegressionRequest,
) -> ResourceQualityRegressionResponse:
    """Re-evaluate one stored resource with the current deterministic evaluator."""

    fallback_point = request.resource.knowledge_points[0] if request.resource.knowledge_points else ""
    evidence = [
        RagEvidence(
            chunk_id=item.chunk_id,
            title=item.title,
            content=item.content,
            score=item.score,
            source=item.source,
            course_id=request.course_id,
            knowledge_point_id=index + 1,
            knowledge_point=item.knowledge_point or fallback_point,
        )
        for index, item in enumerate(request.evidence)
    ]
    current = ResourceQualityEvaluator().evaluate(
        request.resource,
        evidence,
        request.safety,
        request.expected_difficulty,
    )
    baseline = request.baseline_evaluation
    regressed_dimensions: list[str] = []
    for name, current_dimension in current.dimensions.items():
        baseline_dimension = baseline.dimensions.get(name)
        if baseline_dimension is None:
            continue
        delta = current_dimension.score - baseline_dimension.score
        if delta <= -5 or (baseline_dimension.passed and not current_dimension.passed):
            regressed_dimensions.append(name)
    score_delta = round(current.total_score - baseline.total_score, 2)
    gate_changed = baseline.gate_passed != current.gate_passed
    regression_detected = (
        score_delta <= -5
        or (baseline.gate_passed and not current.gate_passed)
        or bool(regressed_dimensions)
    )
    return ResourceQualityRegressionResponse(
        resource_id=request.resource_id,
        evaluator_version=current.evaluator_version,
        baseline_evaluation=baseline,
        current_evaluation=current,
        score_delta=score_delta,
        gate_changed=gate_changed,
        evaluator_changed=baseline.evaluator_version != current.evaluator_version,
        regressed_dimensions=regressed_dimensions,
        regression_detected=regression_detected,
    )
