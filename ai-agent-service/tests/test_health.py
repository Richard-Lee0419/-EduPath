from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.main import app


client = TestClient(app)


def test_health_endpoint_returns_service_status():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"service": "ai-agent-service", "status": "ok"}


def test_runtime_endpoint_describes_reserved_integrations_without_secrets():
    response = client.get("/runtime")

    assert response.status_code == 200
    payload = response.json()
    assert payload["vector_store"]["provider"] in {"local", "chroma"}
    assert "api_key" not in str(payload).lower()
    assert payload["llm"]["configured"] is False


def test_readiness_endpoint_exposes_safe_degradation_without_secrets():
    response = client.get("/readiness")

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] in {"ready", "degraded", "blocked"}
    assert isinstance(payload["live_generation_ready"], bool)
    assert payload["corpus"]["chunks"] > 0
    assert payload["validation_scope"] == "service_configuration_and_local_dependencies"
    assert "api_key" not in str(payload).lower()


def test_key_only_environment_uses_documented_deepseek_defaults(monkeypatch):
    monkeypatch.setenv("LLM_API_KEY", "test-key")
    monkeypatch.delenv("LLM_PROVIDER", raising=False)
    monkeypatch.delenv("LLM_BASE_URL", raising=False)
    monkeypatch.delenv("LLM_MODEL", raising=False)

    runtime_settings = Settings.from_env()

    assert runtime_settings.llm_provider == "deepseek"
    assert runtime_settings.llm_base_url == "https://api.deepseek.com"
    assert runtime_settings.llm_model == "deepseek-chat"


def test_production_settings_reject_local_ai_fallbacks():
    runtime_settings = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="",
        llm_base_url="",
        llm_model="",
        llm_api_key="",
        llm_timeout_seconds=30,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
        environment="prod",
    )

    with pytest.raises(RuntimeError, match="prod"):
        runtime_settings.validate_for_startup()


def test_production_settings_accept_real_rag_and_llm_configuration(tmp_path: Path):
    seed_path = tmp_path / "seed_documents.jsonl"
    seed_path.write_text('{"course_id": 1, "content": "unit test"}\n', encoding="utf-8")
    runtime_settings = Settings(
        vector_store_provider="chroma",
        chroma_url="http://chroma:8000",
        chroma_collection="edupath_course_chunks",
        llm_provider="openai-compatible",
        llm_base_url="https://llm.example.test/v1",
        llm_model="qwen-json",
        llm_api_key="test-key",
        llm_timeout_seconds=30,
        embedding_provider="huggingface",
        embedding_model="BAAI/bge-m3",
        embedding_api_key="",
        embedding_strict=True,
        environment="production",
        seed_corpus_path=str(seed_path),
    )

    runtime_settings.validate_for_startup()
