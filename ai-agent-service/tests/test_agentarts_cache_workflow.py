import io
import json
from datetime import UTC, datetime
from email.message import Message
from uuid import UUID
from urllib.error import HTTPError

import pytest
from fastapi.testclient import TestClient

from app.api.cache_practice_api import public_handout
from app.main import app
from app.rag import cache_diagnosis
from app.services import agentarts_workflow as cloud


ENDPOINT = "https://example.test/runtimes/agent-arts-test/invocations"
QUERY = (
    "32 位地址，按字节寻址，Cache 数据容量 4096 B，块大小 16 B，4 路组相联。"
    "学生作答：Tag 20 位，Index 8 位，Offset 4 位。"
)


class Reply(io.BytesIO):
    def __init__(self, data, content_type):
        super().__init__(data)
        self.headers = Message()
        self.headers["Content-Type"] = content_type


def workflow_event(status="succeeded", bad_citation=False):
    _, state = cache_diagnosis.prepare(QUERY)
    baseline = cache_diagnosis.complete(state, [public_handout()])
    citation = json.loads(baseline["citations_json"])[0]
    if bad_citation:
        citation["excerpt"] = "无需计算即可答题"
    result = {
        "decision": baseline["decision"], "mistake": baseline["mistake"],
        "explanation": baseline["answer"], "citation": citation,
    }
    return {
        "event": "workflow_finished",
        "data": {"status": {"code": 1, "desc": status}, "execution_id": "exec-contract-001",
                 "outputs": {"user_fields": {"result_json": json.dumps(result, ensure_ascii=False)}}},
    }


@pytest.fixture(autouse=True)
def remote_mode(monkeypatch):
    monkeypatch.setenv("EDUPATH_CACHE_DIAGNOSIS_PROVIDER", "agentarts")
    monkeypatch.setenv("AGENTARTS_CACHE_ENDPOINT", ENDPOINT)
    monkeypatch.setenv("AGENTARTS_CACHE_API_KEY", "test-only-key")
    monkeypatch.setenv("AGENTARTS_CACHE_AUTH_MODE", "api_key")


@pytest.mark.parametrize("streamed", [True, False])
def test_published_workflow_contract_and_trace(monkeypatch, streamed):
    def open_request(request, timeout):
        assert request.full_url == ENDPOINT
        assert request.get_method() == "POST"
        assert json.loads(request.data) == {"inputs": {"query": QUERY}}
        headers = {key.lower(): value for key, value in request.header_items()}
        assert headers["authorization"] == "Bearer test-only-key"
        assert headers["x-invoke-mode"] == "published"
        assert headers["x-hw-agentarts-session-id"] == headers["x-request-id"]
        payload = json.dumps(workflow_event(), ensure_ascii=False).encode("utf-8")
        if streamed:
            payload = b'data: {"event":"node_started"}\n\n' + b"data: " + payload + b"\n\ndata: {\"event\":\"end\"}\n\n"
        return Reply(payload, "text/event-stream" if streamed else "application/json")

    monkeypatch.setattr(cloud, "_OPEN", open_request)
    response = TestClient(app).post("/evaluation/cache-practice/diagnose", json={"query": QUERY})
    assert response.status_code == 200
    result = response.json()
    assert result["decision"] == "diagnose"
    assert result["mistake"] == "index_ignores_associativity"
    assert result["provenance"]["execution_id"] == "exec-contract-001"
    assert result["provenance"]["source"] == "agentarts"


@pytest.mark.parametrize("event", [
    {"event": "end"},
    workflow_event(status="failed"),
    workflow_event(bad_citation=True),
])
def test_incomplete_or_unverified_workflow_does_not_fall_back(monkeypatch, event):
    payload = json.dumps(event).encode("utf-8")
    monkeypatch.setattr(cloud, "_OPEN", lambda *args, **kwargs: Reply(payload, "application/json"))
    response = TestClient(app).post("/evaluation/cache-practice/diagnose", json={"query": QUERY})
    assert response.status_code == 502
    assert "provenance" not in response.json()


def test_remote_errors_hide_response_bodies_and_missing_config_is_explicit(monkeypatch, caplog):
    def reject(*args, **kwargs):
        body = io.BytesIO(b'{"error_code":"APIGW.0301","error_msg":"signature expired: sensitive example"}')
        raise HTTPError(ENDPOINT, 401, "authentication error", {}, body)

    monkeypatch.setattr(cloud, "_OPEN", reject)
    client = TestClient(app)
    response = client.post("/evaluation/cache-practice/diagnose", json={"query": QUERY})
    assert response.status_code == 502
    assert response.json()["detail"] == "AgentArts 调用返回 HTTP 401"
    assert "cloud_code=APIGW.0301 category=timestamp" in caplog.text
    assert "sensitive example" not in caplog.text
    monkeypatch.delenv("AGENTARTS_CACHE_API_KEY")
    assert client.post("/evaluation/cache-practice/diagnose", json={"query": QUERY}).status_code == 502


@pytest.mark.parametrize(("endpoint", "security_token", "expected_signature"), [
    (ENDPOINT, "", "f045361f67d07e51997d45490c4029a6dcf7a5a3184a86653258c82cdbc88171"),
    (ENDPOINT, "synthetic-test-token", "0ab130bf704b262fb00cbe19efc974e2ed382604a0f48717caaa3d348bca1c65"),
    (ENDPOINT + "?endpoint=Latest", "", "bd032f840e8afaa761d15df9e7b604d6c537496d9719eab0a91ebfc0443cfae5"),
])
def test_iam_published_request_matches_huawei_reference_signer(monkeypatch, endpoint, security_token, expected_signature):
    # Reference signatures generated by the official ApiGateway Python SDK 2.0.7 with synthetic keys.
    class FrozenClock:
        @staticmethod
        def now(tz):
            return datetime(2026, 11, 19, 3, 4, 5, tzinfo=UTC).astimezone(tz)

    def open_request(request, timeout):
        assert request.full_url == endpoint
        assert request.data == b'{"inputs": {"query": "fixture"}}'
        headers = {name.lower(): value for name, value in request.header_items()}
        assert headers["host"] == "example.test"
        assert headers["x-sdk-date"] == "20261119T030405Z"
        assert headers.get("x-security-token", "") == security_token
        assert headers["x-sdk-content-sha256"] == "UNSIGNED-PAYLOAD"
        signed = (
            "accept;content-type;host;x-hw-agentarts-session-id;x-invoke-mode;"
            "x-request-id;x-sdk-content-sha256;x-sdk-date"
        )
        if security_token:
            signed += ";x-security-token"
        assert headers["authorization"] == (
            f"V11-HMAC-SHA256 Credential=OFFLINE_TEST/20261119/cn-southwest-2/apic, "
            f"SignedHeaders={signed}, Signature={expected_signature}"
        )
        return Reply(json.dumps(workflow_event()).encode("utf-8"), "application/json")

    monkeypatch.setattr(cloud, "datetime", FrozenClock)
    monkeypatch.setattr(cloud, "uuid4", lambda: UUID("00000000-0000-4000-8000-000000000001"))
    monkeypatch.setattr(cloud, "_OPEN", open_request)
    result = cloud.invoke_cache(
        endpoint, "unused-bearer", "fixture", auth_mode="iam",
        access_key="OFFLINE_TEST", secret_key="dummy-signing-key", security_token=security_token,
    )
    assert result["provenance"]["execution_id"] == "exec-contract-001"


def test_iam_requires_its_own_credentials(monkeypatch):
    monkeypatch.setenv("AGENTARTS_CACHE_AUTH_MODE", "iam")
    monkeypatch.setenv("AGENTARTS_CACHE_IAM_AK", "")
    monkeypatch.setenv("AGENTARTS_CACHE_IAM_SK", "")
    monkeypatch.setattr(cloud, "_OPEN", lambda *args, **kwargs: pytest.fail("unexpected remote call"))
    response = TestClient(app).post("/evaluation/cache-practice/diagnose", json={"query": QUERY})
    assert response.status_code == 502
    assert "AK/SK" in response.json()["detail"]
