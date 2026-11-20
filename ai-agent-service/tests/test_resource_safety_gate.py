"""Safety refusal must never become a successful task or expose resource content."""

import io
import time
from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from app.agents.orchestrator_agent import orchestrator
from app.main import app
from app.schemas.resource_schema import ResourceGenerateRequest, SafetyReview
from app.services.llm_gateway import LLMGateway, LLMGatewayError
from app.services.task_manager import task_manager


def _request():
    return ResourceGenerateRequest(
        course_id=1, knowledge_point_ids=[131], resource_types=["lecture"], difficulty="basic",
    )


def _reject(output_text, evidence, require_evidence=True):
    return SafetyReview(
        passed=False, risk_level="medium", issues=["test safety refusal"], suggestions=[], confidence=0.5,
    )


def _wait_for_terminal(client, task_id):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        response = client.get(f"/tasks/{task_id}")
        assert response.status_code == 200
        snapshot = response.json()
        if snapshot["status"] in {"success", "failed"}:
            return snapshot
        time.sleep(0.02)
    pytest.fail(f"Resource task {task_id} did not reach a terminal status")


def test_async_safety_refusal_finishes_failed_without_publishable_resources(monkeypatch):
    pipeline = orchestrator.resource_generation_pipeline
    monkeypatch.setattr(pipeline.safety_agent, "review", _reject)
    client = TestClient(app)
    created = client.post("/resource/tasks", json=_request().model_dump())
    assert created.status_code == 200
    snapshot = _wait_for_terminal(client, created.json()["task_id"])

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


@pytest.mark.parametrize(
    ("fault", "error_fragment"),
    [("timeout", "model read timed out"), ("truncated", "JSON output was truncated")],
)
def test_lecture_provider_faults_stay_distinct_and_never_publish(monkeypatch, fault, error_fragment):
    pipeline = orchestrator.resource_generation_pipeline
    fake_settings = replace(
        pipeline.llm_gateway.settings,
        llm_provider="test-gateway",
        llm_base_url="http://127.0.0.1:1",
        llm_model="test-lecture-model",
        llm_api_key="test-only",
        llm_max_retries=0,
    )
    monkeypatch.setattr(pipeline, "llm_gateway", LLMGateway(fake_settings))

    def urlopen(*args, **kwargs):
        if fault == "timeout":
            raise TimeoutError("model read timed out")
        return io.BytesIO(b'{"choices":[{"finish_reason":"length","message":{"content":"{"}}]}')

    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    task = pipeline.start(_request())
    snapshot = _wait_for_terminal(TestClient(app), task.task_id)
    assert snapshot["status"] == "failed"
    assert error_fragment in snapshot["error"]
    assert not snapshot.get("result")
    assert not any(event["event"] == "task_completed" for event in snapshot["events"])


def test_lecture_template_mode_reports_success_without_model_usage(monkeypatch):
    pipeline = orchestrator.resource_generation_pipeline
    monkeypatch.setattr(
        pipeline, "llm_gateway", LLMGateway(replace(pipeline.llm_gateway.settings, llm_api_key="")),
    )
    response = pipeline.generate(_request())
    assert response.status == "success"
    assert response.resources
    assert response.model_runtime.mode == "deterministic_fallback"
    assert response.model_runtime.real_model_used is False
    assert not response.model_runtime.calls
