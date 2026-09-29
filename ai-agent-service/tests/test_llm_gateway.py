import json

import pytest

from app.core.config import Settings
from app.services.llm_gateway import LLMGateway, LLMGatewayError


class _FakeResponse:
    def __init__(self, payload: dict | None = None, raw: bytes | None = None):
        self.payload = payload or {"choices": [{"message": {"content": "{\"ok\": true, \"source\": \"test-llm\"}"}}]}
        self.raw = raw

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def read(self):
        return self.raw if self.raw is not None else json.dumps(self.payload).encode("utf-8")


def test_llm_gateway_calls_openai_compatible_chat_completion(monkeypatch):
    captured = {}

    def fake_urlopen(request, timeout):
        captured["url"] = request.full_url
        captured["timeout"] = timeout
        captured["authorization"] = request.headers["Authorization"]
        captured["body"] = json.loads(request.data.decode("utf-8"))
        return _FakeResponse()

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    settings = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="openai-compatible",
        llm_base_url="http://llm.example.test",
        llm_model="demo-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local",
        embedding_api_key="",
    )

    result = LLMGateway(settings).complete_json("生成 JSON", {"course": "DSA"})

    assert result == {"ok": True, "source": "test-llm"}
    assert captured["url"] == "http://llm.example.test/chat/completions"
    assert captured["authorization"] == "Bearer test-key"
    assert captured["body"]["model"] == "demo-model"
    assert captured["body"]["response_format"]["type"] == "json_object"


def test_llm_gateway_rejects_incomplete_provider_configuration():
    settings = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="openai-compatible",
        llm_base_url="",
        llm_model="demo-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local",
        embedding_api_key="",
    )

    with pytest.raises(LLMGatewayError, match="LLM_BASE_URL"):
        LLMGateway(settings).complete_json("生成 JSON", {})


def test_llm_gateway_wraps_malformed_upstream_json(monkeypatch):
    def fake_urlopen(request, timeout):
        return _FakeResponse(raw=b"not-json")

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    settings = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="openai-compatible",
        llm_base_url="http://llm.example.test",
        llm_model="demo-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local",
        embedding_api_key="",
    )

    with pytest.raises(LLMGatewayError, match="invalid JSON"):
        LLMGateway(settings).complete_json("生成 JSON", {})
