"""Thin, configurable client for a published AgentArts agent / workflow.

The exact request envelope depends on how the agent is published (API 调用、
Web 应用 or the AgentArts SDK runtime).  We therefore keep the protocol
configuration explicit instead of hard-coding an endpoint:

* ``AGENTARTS_ENDPOINT``  full invoke URL copied from the AgentArts console.
* ``AGENTARTS_API_KEY``   bearer token / API Key.
* ``AGENTARTS_PROTOCOL``  ``generic`` (default) or ``openai``.
* ``AGENTARTS_APP_ID``    optional app / agent id sent in the envelope.

``generic`` sends ``{"input": {...}, "session_id": ...}`` and accepts
``output``/``answer``/``data.output`` in the response.  ``openai`` sends an
OpenAI-compatible chat completion body and reads ``choices[0].message.content``.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Any


class AgentArtsNotConfigured(RuntimeError):
    pass


class AgentArtsClient:
    def __init__(
        self,
        endpoint: str | None = None,
        api_key: str | None = None,
        protocol: str | None = None,
        app_id: str | None = None,
        timeout: float = 120.0,
    ) -> None:
        self.endpoint = (endpoint or os.getenv("AGENTARTS_ENDPOINT", "")).strip()
        self.api_key = (api_key or os.getenv("AGENTARTS_API_KEY", "")).strip()
        self.protocol = (protocol or os.getenv("AGENTARTS_PROTOCOL", "generic")).strip().lower()
        self.app_id = (app_id or os.getenv("AGENTARTS_APP_ID", "")).strip()
        self.timeout = timeout

    @property
    def configured(self) -> bool:
        return bool(self.endpoint)

    def _payload(self, message: str, session_id: str, context: dict[str, Any]) -> dict[str, Any]:
        if self.protocol == "openai":
            return {
                "model": os.getenv("AGENTARTS_MODEL", "agentarts"),
                "messages": [{"role": "user", "content": message}],
                "stream": False,
                "user": session_id,
            }
        payload: dict[str, Any] = {
            "input": {"message": message, "context": context},
            "session_id": session_id,
        }
        if self.app_id:
            payload["app_id"] = self.app_id
        return payload

    def invoke(self, message: str, session_id: str = "huawei-ict-demo", context: dict[str, Any] | None = None) -> dict[str, Any]:
        if not self.configured:
            raise AgentArtsNotConfigured(
                "未配置 AGENTARTS_ENDPOINT。请先在 AgentArts 控制台发布智能体/工作流，"
                "把「API 调用」地址与 API Key 写入环境变量，或使用 EDUPATH_BRIDGE_MODE=fixture 运行本地演示。"
            )
        body = json.dumps(self._payload(message, session_id, context or {}), ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        request = urllib.request.Request(self.endpoint, data=body, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:  # noqa: S310 - operator-configured endpoint
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:500]
            raise RuntimeError(f"AgentArts HTTP {exc.code}: {detail}") from exc
        parsed = json.loads(raw)
        return self._normalise(parsed)

    @staticmethod
    def _normalise(parsed: dict[str, Any]) -> dict[str, Any]:
        answer = ""
        if isinstance(parsed.get("output"), str):
            answer = parsed["output"]
        elif isinstance(parsed.get("answer"), str):
            answer = parsed["answer"]
        elif isinstance(parsed.get("data"), dict) and isinstance(parsed["data"].get("output"), str):
            answer = parsed["data"]["output"]
        elif isinstance(parsed.get("choices"), list) and parsed["choices"]:
            message = parsed["choices"][0].get("message", {})
            answer = message.get("content", "") if isinstance(message, dict) else ""
        return {"raw": parsed, "answer": answer, "protocol": "openai" if "choices" in parsed else "generic"}
