from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any

from app.core.config import Settings, settings


log = logging.getLogger(__name__)


class LLMGatewayError(RuntimeError):
    """Controlled error for upstream LLM provider failures."""


@dataclass(frozen=True)
class LLMCallMetadata:
    provider: str
    model: str
    duration_ms: int
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    request_id: str = ""


@dataclass(frozen=True)
class LLMJsonCompletion:
    data: dict[str, object]
    metadata: LLMCallMetadata


class LLMGateway:
    """Small OpenAI-compatible gateway with strict JSON parsing and retries."""

    _RETRYABLE_STATUS = {408, 409, 429, 500, 502, 503, 504}

    def __init__(self, runtime_settings: Settings | None = None) -> None:
        self.settings = runtime_settings or settings

    @property
    def is_configured(self) -> bool:
        return bool(
            self.settings.llm_provider
            and self.settings.llm_base_url
            and self.settings.llm_model
            and self.settings.llm_api_key
        )

    def complete_json(self, prompt: str, context: dict[str, object]) -> dict[str, object]:
        """Compatibility wrapper returning only the validated JSON object."""

        return self.complete_json_with_metadata(prompt, context).data

    def complete_json_with_metadata(
        self,
        prompt: str,
        context: dict[str, object],
        *,
        max_output_tokens: int | None = None,
    ) -> LLMJsonCompletion:
        if not self.is_configured:
            self._validate_provider_config()
        return self._complete_openai_compatible(prompt, context, max_output_tokens=max_output_tokens)

    def _complete_openai_compatible(
        self,
        prompt: str,
        context: dict[str, object],
        *,
        max_output_tokens: int | None,
    ) -> LLMJsonCompletion:
        self._validate_provider_config()
        base_url = self.settings.llm_base_url.rstrip("/")
        url = f"{base_url}/chat/completions"
        body: dict[str, object] = {
            "model": self.settings.llm_model,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are an EduPath education agent. Return one strict JSON object only. "
                        "Do not wrap JSON in Markdown fences and do not reveal private profile fields."
                    ),
                },
                {"role": "user", "content": prompt},
                {"role": "user", "content": json.dumps(context, ensure_ascii=False)},
            ],
            "temperature": self.settings.llm_temperature,
            "max_tokens": max_output_tokens or self.settings.llm_max_output_tokens,
            "response_format": {"type": "json_object"},
        }
        if self.settings.llm_provider.lower() in {"dashscope", "aliyun", "qwen"}:
            # Qwen JSON Mode is incompatible with thinking mode.
            body["enable_thinking"] = False

        request = urllib.request.Request(
            url,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.settings.llm_api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )

        attempts = max(1, self.settings.llm_max_retries + 1)
        last_error: Exception | None = None
        for attempt in range(attempts):
            started = time.perf_counter()
            try:
                with urllib.request.urlopen(request, timeout=self.settings.llm_timeout_seconds) as response:
                    raw_payload = response.read().decode("utf-8")
                    request_id = _response_header(response, "x-request-id")
                payload = json.loads(raw_payload)
                completion = self._parse_completion(
                    payload,
                    duration_ms=int((time.perf_counter() - started) * 1000),
                    request_id=request_id,
                )
                log.info(
                    "llm_call provider=%s model=%s duration_ms=%s total_tokens=%s status=success",
                    completion.metadata.provider,
                    completion.metadata.model,
                    completion.metadata.duration_ms,
                    completion.metadata.total_tokens,
                )
                return completion
            except urllib.error.HTTPError as exception:
                detail = _http_error_detail(exception)
                last_error = LLMGatewayError(f"LLM upstream returned HTTP {exception.code}: {detail}")
                retryable = exception.code in self._RETRYABLE_STATUS
            except json.JSONDecodeError as exception:
                last_error = LLMGatewayError("LLM upstream returned invalid JSON response")
                retryable = False
            except (urllib.error.URLError, TimeoutError, OSError) as exception:
                last_error = LLMGatewayError(f"LLM upstream request failed: {exception}")
                retryable = True
            except LLMGatewayError as exception:
                last_error = exception
                retryable = "empty content" in str(exception).lower()

            if not retryable or attempt + 1 >= attempts:
                break
            time.sleep(min(0.25 * (2**attempt), 2.0))

        assert last_error is not None
        log.warning(
            "llm_call provider=%s model=%s status=failed error=%s",
            self.settings.llm_provider,
            self.settings.llm_model,
            last_error,
        )
        raise last_error

    def _parse_completion(
        self,
        payload: dict[str, Any],
        *,
        duration_ms: int,
        request_id: str,
    ) -> LLMJsonCompletion:
        try:
            choice = payload["choices"][0]
            content = choice["message"]["content"]
        except (KeyError, IndexError, TypeError) as exception:
            raise LLMGatewayError("LLM upstream response missing choices[0].message.content") from exception
        if choice.get("finish_reason") == "length":
            raise LLMGatewayError("LLM JSON output was truncated; increase LLM_MAX_OUTPUT_TOKENS")
        if not isinstance(content, str) or not content.strip():
            raise LLMGatewayError("LLM upstream returned empty content")
        try:
            result = json.loads(content)
        except json.JSONDecodeError as exception:
            raise LLMGatewayError("LLM upstream message content is not valid JSON") from exception
        if not isinstance(result, dict):
            raise LLMGatewayError("LLM upstream message content must be a JSON object")

        usage = payload.get("usage") if isinstance(payload.get("usage"), dict) else {}
        prompt_tokens = _as_int(usage.get("prompt_tokens"))
        completion_tokens = _as_int(usage.get("completion_tokens"))
        total_tokens = _as_int(usage.get("total_tokens")) or prompt_tokens + completion_tokens
        return LLMJsonCompletion(
            data=result,
            metadata=LLMCallMetadata(
                provider=self.settings.llm_provider,
                model=str(payload.get("model") or self.settings.llm_model),
                duration_ms=duration_ms,
                prompt_tokens=prompt_tokens,
                completion_tokens=completion_tokens,
                total_tokens=total_tokens,
                request_id=request_id,
            ),
        )

    def _validate_provider_config(self) -> None:
        missing = [
            name
            for name, value in {
                "LLM_PROVIDER": self.settings.llm_provider,
                "LLM_BASE_URL": self.settings.llm_base_url,
                "LLM_MODEL": self.settings.llm_model,
                "LLM_API_KEY": self.settings.llm_api_key,
            }.items()
            if not value
        ]
        if missing:
            raise LLMGatewayError("Incomplete LLM provider configuration: " + ", ".join(missing))


def _response_header(response: object, name: str) -> str:
    headers = getattr(response, "headers", None)
    if headers is None:
        return ""
    value = headers.get(name)
    return "" if value is None else str(value)


def _http_error_detail(exception: urllib.error.HTTPError) -> str:
    try:
        detail = exception.read().decode("utf-8", errors="replace").strip()
    except OSError:
        detail = ""
    return detail[:500] or exception.reason or "upstream error"


def _as_int(value: object) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0
