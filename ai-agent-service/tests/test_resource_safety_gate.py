"""Safety refusal must never become a successful task or expose resource content."""

import time

import pytest
from fastapi.testclient import TestClient

from app.agents.orchestrator_agent import orchestrator
from app.main import app
from app.schemas.resource_schema import ResourceGenerateRequest, SafetyReview
from app.services.llm_gateway import LLMGatewayError
from app.services.task_manager import task_manager


def _request():
    return ResourceGenerateRequest(
        course_id=1, knowledge_point_ids=[131], resource_types=["lecture"], difficulty="basic",
    )


def _reject(output_text, evidence, require_evidence=True):
    return SafetyReview(
        passed=False, risk_level="medium", issues=["test safety refusal"], suggestions=[], confidence=0.5,
    )


def test_async_safety_refusal_finishes_failed_without_publishable_resources(monkeypatch):
    pipeline = orchestrator.resource_generation_pipeline
    monkeypatch.setattr(pipeline.safety_agent, "review", _reject)
    client = TestClient(app)
    created = client.post("/resource/tasks", json=_request().model_dump())
    assert created.status_code == 200
    task_id = created.json()["task_id"]
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        response = client.get(f"/tasks/{task_id}")
        assert response.status_code == 200
        snapshot = response.json()
        if snapshot["status"] in {"success", "failed"}:
            break
        time.sleep(0.02)
    else:
        pytest.fail("Safety refusal did not finish within the local task timeout")

    assert snapshot["status"] == "failed"
    assert snapshot["result"]["safety_status"] == "review_required"
    assert snapshot["result"]["safety"]["passed"] is False
    assert snapshot["result"]["resources"] == []
    assert snapshot["result"]["evidence"]
    assert snapshot["result"]["model_runtime"]["real_model_used"] is False
    assert any(step["agent"] == "SafetyAgent" and step["status"] == "failed" for step in snapshot["steps"])
    assert any(event["event"] == "task_failed" for event in snapshot["events"])
    assert not any(event["event"] == "task_completed" for event in snapshot["events"])


def test_sync_safety_refusal_raises_instead_of_returning_generated_content(monkeypatch):
    pipeline = orchestrator.resource_generation_pipeline
    monkeypatch.setattr(pipeline.safety_agent, "review", _reject)
    with pytest.raises(LLMGatewayError, match="SafetyAgent"):
        pipeline.generate(_request())


def test_quality_failure_is_kept_separate_from_safety_approval(monkeypatch):
    pipeline = orchestrator.resource_generation_pipeline
    original_evaluate = pipeline.quality_evaluator.evaluate

    def reject_quality(*args, **kwargs):
        return original_evaluate(*args, **kwargs).model_copy(update={"gate_passed": False})

    monkeypatch.setattr(pipeline.quality_evaluator, "evaluate", reject_quality)
    response = pipeline.generate(_request())
    assert response.status == "success"
    assert response.safety.passed is True
    assert response.safety_status == "passed"
    assert response.resources[0].quality_evaluation.gate_passed is False
    assert task_manager.get_task(response.task_id)["status"] == "success"
