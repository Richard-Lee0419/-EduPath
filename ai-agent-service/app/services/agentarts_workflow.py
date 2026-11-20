"""Client for the published AgentArts Cache workflow."""

import hmac
import json
import logging
import re
from datetime import UTC, datetime
from hashlib import sha256
from time import monotonic
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import uuid4


MAX_RESPONSE_BYTES = 256 * 1024
TIMEOUT_SECONDS = 20
_ID = re.compile(r"[A-Za-z0-9_-]{1,128}")
_log = logging.getLogger(__name__)


class WorkflowError(RuntimeError):
    pass


class _NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPEN = build_opener(_NoRedirects()).open


def invoke_cache(
    endpoint: str,
    api_key: str,
    query: str,
    *,
    auth_mode: str = "api_key",
    access_key: str = "",
    secret_key: str = "",
    security_token: str = "",
) -> dict:
    try:
        url = urlsplit(endpoint)
        configured = (url.scheme == "https" and url.hostname and not url.username and not url.password
                      and url.query in {"", "endpoint=Latest"} and not url.fragment and re.fullmatch(
                          r"/runtimes/[A-Za-z0-9_-]{1,64}/invocations", url.path))
    except ValueError:
        configured = False
    if not configured:
        raise WorkflowError("请为已部署工作流配置 HTTPS 调用路径")
    request_id = str(uuid4())
    payload_bytes = json.dumps({"inputs": {"query": query}}).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream, application/json",
        "X-Invoke-Mode": "published",
        "x-hw-agentarts-session-id": request_id,
        "X-Request-Id": request_id,
    }
    if auth_mode == "api_key":
        if not re.fullmatch(r"[!-~]+", api_key):
            raise WorkflowError("请为已部署工作流配置 API Key")
        headers["Authorization"] = f"Bearer {api_key}"
    elif auth_mode == "iam":
        headers["Host"] = url.netloc
        _sign_iam(headers, url.path, url.query, access_key, secret_key, security_token)
    else:
        raise WorkflowError("AgentArts 鉴权模式配置错误")
    request = Request(endpoint, method="POST", data=payload_bytes, headers=headers)
    deadline = monotonic() + TIMEOUT_SECONDS
    try:
        with _OPEN(request, timeout=TIMEOUT_SECONDS) as response:
            if response.headers.get_content_type() == "text/event-stream":
                payload = _finished_event(response, deadline)
            else:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                if len(raw) > MAX_RESPONSE_BYTES:
                    raise WorkflowError("AgentArts 响应超过大小限制")
                payload = json.loads(raw)
    except HTTPError as failure:
        _log_http_failure(failure, request_id)
        raise WorkflowError(f"AgentArts 调用返回 HTTP {failure.code}") from None
    except (URLError, OSError) as failure:
        raise WorkflowError("AgentArts 连接失败或超时") from None
    except (ValueError, UnicodeError):
        raise WorkflowError("AgentArts 响应解析失败") from None

    if not isinstance(payload, dict) or payload.get("event") != "workflow_finished":
        raise WorkflowError("AgentArts 未返回工作流完成事件")
    data = payload.get("data")
    status = data.get("status") if isinstance(data, dict) else None
    if not isinstance(status, dict) or status.get("code") != 1 or status.get("desc") != "succeeded":
        raise WorkflowError("AgentArts 工作流未成功完成")
    outputs = data.get("outputs")
    fields = outputs.get("user_fields") if isinstance(outputs, dict) else None
    execution_id = data.get("execution_id")
    if not isinstance(execution_id, str) or not _ID.fullmatch(execution_id) or not isinstance(fields, dict):
        raise WorkflowError("AgentArts 结果缺少执行编号或工作流输出")
    try:
        result = json.loads(fields["result_json"])
    except (KeyError, TypeError, ValueError):
        raise WorkflowError("工作流结束节点需输出 result_json 字符串") from None
    if not isinstance(result, dict):
        raise WorkflowError("工作流的 result_json 需为对象")
    result["provenance"] = {
        "source": "agentarts", "request_id": request_id, "execution_id": execution_id,
    }
    return result


def _log_http_failure(failure: HTTPError, request_id: str) -> None:
    reason = "unspecified"
    code = "-"
    if failure.fp is not None:
        try:
            with failure:
                payload = json.loads(failure.read(4096))
        except (OSError, ValueError):
            payload = None
        if isinstance(payload, dict):
            error_code = payload.get("error_code")
            if isinstance(error_code, str) and re.fullmatch(r"[A-Za-z]{2,16}\.[0-9]{3,12}", error_code):
                code = error_code
            message = str(payload.get("error_msg") or payload.get("message") or "").lower()
            for marker, hint in (
                ("signature expired", "timestamp"),
                ("access key does not exist", "access-key-not-found"),
                ("invalid access key", "access-key-invalid"),
                ("signature mismatch", "signature-mismatch"),
                ("verify aksk signature fail", "signature-rejected"),
                ("authentication failed", "authentication-rejected"),
            ):
                if marker in message:
                    reason = hint
                    break
    _log.warning("AgentArts request_id=%s http_status=%s cloud_code=%s category=%s",
                 request_id, failure.code, code, reason)


def _sign_iam(headers: dict[str, str], path: str, query: str, ak: str, sk: str, token: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_-]+", ak) or not re.fullmatch(r"[!-~]+", sk):
        raise WorkflowError("请在服务端为 AgentArts IAM 模式配置 AK/SK")
    if token:
        if not re.fullmatch(r"[!-~]+", token):
            raise WorkflowError("AgentArts IAM 临时安全令牌格式错误")
        headers["X-Security-Token"] = token
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    headers["X-Sdk-Date"] = timestamp
    headers["X-Sdk-Content-Sha256"] = "UNSIGNED-PAYLOAD"
    pairs = sorted((name.lower(), value.strip()) for name, value in headers.items())
    signed_headers = ";".join(name for name, _ in pairs)
    canonical_headers = "".join(f"{name}:{value}\n" for name, value in pairs)
    # The validated low-code path contains only unreserved characters and slashes.
    # Huawei requires the trailing slash in the signature and accepts the original URI on the wire.
    canonical = "\n".join(("POST", path + "/", query, canonical_headers, signed_headers, "UNSIGNED-PAYLOAD"))
    algorithm = "V11-HMAC-SHA256"
    scope = f"{timestamp[:8]}/cn-southwest-2/apic"
    string_to_sign = "\n".join((algorithm, timestamp, scope, sha256(canonical.encode("utf-8")).hexdigest()))
    # V11 derives a 32-byte key with HKDF-SHA256 and uses its hexadecimal text as the HMAC key.
    extracted_key = hmac.digest(ak.encode("ascii"), sk.encode("ascii"), sha256)
    signing_key = hmac.digest(extracted_key, scope.encode("ascii") + b"\x01", sha256).hex()
    signature = hmac.digest(signing_key.encode("ascii"), string_to_sign.encode("utf-8"), sha256).hex()
    headers["Authorization"] = (
        f"{algorithm} Credential={ak}/{scope}, SignedHeaders={signed_headers}, Signature={signature}"
    )


def _finished_event(response, deadline: float) -> dict:
    parts = []
    remaining = MAX_RESPONSE_BYTES
    while remaining > 0 and monotonic() < deadline:
        line = response.readline(remaining + 1)
        if not line:
            break
        remaining -= len(line)
        if remaining < 0 or monotonic() >= deadline:
            break
        if line.startswith(b"data:"):
            parts.append(line[5:].strip())
        elif not line.strip() and parts:
            event = json.loads(b"\n".join(parts))
            parts.clear()
            if isinstance(event, dict) and event.get("event") in {"error", "exception", "node_wait"}:
                raise WorkflowError("AgentArts 工作流报错或等待交互")
            if isinstance(event, dict) and event.get("event") == "workflow_finished":
                return event
    raise WorkflowError("AgentArts 未在限制内返回工作流完成事件")
