import json
from pathlib import Path

from app.rag.retriever import RagEvidence
from app.schemas.resource_schema import GeneratedResource, SafetyReview
from app.services.resource_quality_evaluator import ResourceQualityEvaluator
from app.agents.orchestrator_agent import orchestrator
from app.api.resource_api import regress_resource_quality, repair_resource
from app.core.config import Settings
from app.schemas.resource_schema import ResourceQualityRegressionRequest, ResourceRepairRequest
from app.services.llm_gateway import LLMGateway


BASELINE_PATH = Path(__file__).parent / "fixtures" / "resource_quality_baseline.json"


def test_fixed_resource_quality_regression_baseline():
    baseline = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    evaluator = ResourceQualityEvaluator()

    assert baseline["evaluator_version"] == evaluator.version
    for case in baseline["cases"]:
        resource_payload = case["resource"]
        resource = GeneratedResource(
            content_format="markdown",
            summary=resource_payload["title"],
            personalized_reason="固定回归样本",
            estimated_minutes=20,
            profile_fingerprint="baseline",
            **resource_payload,
        )
        evidence = [
            RagEvidence(
                score=0.95,
                source="baseline://course-corpus",
                course_id=1,
                knowledge_point_id=index + 1,
                **item,
            )
            for index, item in enumerate(case["evidence"])
        ]
        safety = SafetyReview.model_validate(
            {"issues": [], "suggestions": [], **case["safety"]}
        )

        result = evaluator.evaluate(
            resource,
            evidence,
            safety,
            case["expected_difficulty"],
        )

        assert result.total_score == case["expected"]["total_score"], case["case_id"]
        assert result.grade == case["expected"]["grade"], case["case_id"]
        assert result.gate_passed is case["expected"]["gate_passed"], case["case_id"]


def test_safety_failure_is_a_hard_gate_even_when_other_dimensions_are_strong():
    evaluator = ResourceQualityEvaluator()
    evidence = [
        RagEvidence(
            chunk_id="chunk_1",
            title="二叉树递归遍历",
            content="递归遍历必须先处理空树边界。",
            score=0.95,
            source="baseline://course-corpus",
            course_id=1,
            knowledge_point_id=1,
            knowledge_point="二叉树递归遍历",
        )
    ]
    resource = GeneratedResource(
        title="二叉树递归遍历讲义",
        resource_type="lecture",
        content="## 学习目标\n二叉树递归遍历\n## 分步讲解\n定义和步骤\n## 边界例题\n空树返回\n## RAG 证据\n[chunk_1]",
        summary="讲义",
        difficulty="basic",
        knowledge_points=["二叉树递归遍历"],
        personalized_reason="基础复习",
        estimated_minutes=20,
        profile_fingerprint="baseline",
        evidence_chunk_ids=["chunk_1"],
    )
    safety = SafetyReview(
        passed=False,
        risk_level="medium",
        issues=["关键事实需要复核"],
        suggestions=["依据课件重新核算"],
        confidence=0.9,
    )

    result = evaluator.evaluate(resource, evidence, safety, "basic")

    assert result.total_score >= 80
    assert result.gate_passed is False
    assert result.dimensions["safety_review"].passed is False


def test_quality_regression_re_evaluates_stored_resource_without_model_call():
    evaluator = ResourceQualityEvaluator()
    resource = GeneratedResource(
        title="二叉树递归遍历讲义",
        resource_type="lecture",
        content="## 学习目标\n二叉树递归遍历\n## 分步讲解\n基础定义和步骤\n## 边界例题\n空树返回\n## RAG 证据\n[chunk_1]",
        summary="讲义",
        difficulty="basic",
        knowledge_points=["二叉树递归遍历"],
        personalized_reason="基础复习",
        estimated_minutes=20,
        profile_fingerprint="regression",
        evidence_chunk_ids=["chunk_1"],
    )
    evidence = [
        RagEvidence(
            chunk_id="chunk_1",
            title="二叉树递归遍历",
            content="递归遍历必须先处理空树边界。",
            score=0.95,
            source="baseline://course-corpus",
            course_id=1,
            knowledge_point_id=1,
            knowledge_point="二叉树递归遍历",
        )
    ]
    safety = SafetyReview(
        passed=True,
        risk_level="low",
        issues=[],
        suggestions=[],
        confidence=0.95,
    )
    baseline = evaluator.evaluate(resource, evidence, safety, "basic")
    request = ResourceQualityRegressionRequest.model_validate({
        "resource_id": "res_regression_1",
        "course_id": 1,
        "resource": resource.model_dump(mode="json"),
        "evidence": [{
            "chunk_id": item.chunk_id,
            "title": item.title,
            "content": item.content,
            "score": item.score,
            "source": item.source,
            "knowledge_point": item.knowledge_point,
        } for item in evidence],
        "safety": safety.model_dump(mode="json"),
        "baseline_evaluation": baseline.model_dump(mode="json"),
        "expected_difficulty": "basic",
    })

    result = regress_resource_quality(request)

    assert result.regression_detected is False
    assert result.score_delta == 0
    assert result.current_evaluation == baseline


def test_controlled_repair_keeps_original_isolated_and_rechecks_quality(monkeypatch):
    evaluator = ResourceQualityEvaluator()
    resource = GeneratedResource(
        title="二叉树递归遍历讲义",
        resource_type="lecture",
        content="## 学习目标\n二叉树递归遍历\n## 分步讲解\n基础定义和步骤\n## 边界例题\n空树返回\n## RAG 证据\n[chunk_1]",
        summary="讲义",
        difficulty="basic",
        knowledge_points=["二叉树递归遍历"],
        personalized_reason="基础复习",
        estimated_minutes=20,
        profile_fingerprint="repair",
        evidence_chunk_ids=["chunk_1"],
    )
    evidence = [
        RagEvidence(
            chunk_id="chunk_1",
            title="二叉树递归遍历",
            content="递归遍历必须先处理空树边界。",
            score=0.95,
            source="baseline://course-corpus",
            course_id=1,
            knowledge_point_id=1,
            knowledge_point="二叉树递归遍历",
        )
    ]
    safety = SafetyReview(
        passed=True,
        risk_level="low",
        issues=[],
        suggestions=[],
        confidence=0.95,
    )
    baseline = evaluator.evaluate(resource, evidence, safety, "basic")
    current = baseline.model_copy(update={
        "total_score": baseline.total_score - 10,
        "grade": "B",
        "gate_passed": False,
    })
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="test",
        llm_provider="",
        llm_base_url="",
        llm_model="",
        llm_api_key="",
        llm_timeout_seconds=1,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(
        orchestrator.resource_generation_pipeline,
        "llm_gateway",
        LLMGateway(runtime),
    )
    request = ResourceRepairRequest.model_validate({
        "repair_id": "repair_fixed_1",
        "resource_id": "res_fixed_1",
        "course_id": 1,
        "resource": resource.model_dump(mode="json"),
        "evidence": [{
            "chunk_id": item.chunk_id,
            "title": item.title,
            "content": item.content,
            "score": item.score,
            "source": item.source,
            "knowledge_point": item.knowledge_point,
        } for item in evidence],
        "safety": safety.model_dump(mode="json"),
        "baseline_evaluation": baseline.model_dump(mode="json"),
        "current_evaluation": current.model_dump(mode="json"),
        "regressed_dimensions": ["evidence_coverage"],
        "expected_difficulty": "basic",
    })

    result = repair_resource(request)

    assert result.status == "candidate_ready"
    assert result.candidate_resource.content == resource.content
    assert result.quality_evaluation.gate_passed is True
    assert result.recovery_score_delta >= 9.8
    assert result.target_dimensions_passed is True
    assert result.model_runtime.mode == "deterministic_fallback"
