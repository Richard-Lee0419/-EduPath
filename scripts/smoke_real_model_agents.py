#!/usr/bin/env python3
"""Verify real-model resource generation through AI and Java task boundaries."""

from __future__ import annotations

import argparse
import json
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


TERMINAL_STATUSES = {"success", "failed", "cancelled"}


def request_json(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    token: str = "",
    timeout: float = 30,
) -> dict[str, Any]:
    headers = {"Accept": "application/json"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = Request(url, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{url} returned HTTP {exception.code}: {detail}") from exception
    except URLError as exception:
        raise RuntimeError(f"cannot connect to {url}: {exception.reason}") from exception


def wait_for_task(
    url: str,
    *,
    token: str = "",
    wrapped: bool = False,
    timeout_seconds: float,
    poll_seconds: float,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last_status = ""
    while time.monotonic() < deadline:
        response = request_json("GET", url, token=token)
        task = response.get("data", {}) if wrapped else response
        if not isinstance(task, dict):
            raise AssertionError(f"{url}: task response is not an object")
        status = str(task.get("status") or "")
        if status != last_status:
            print(f"{task.get('task_id', 'task')} -> {status} ({task.get('progress', 0)}%)")
            last_status = status
        if status in TERMINAL_STATUSES:
            if status != "success":
                error = task.get("error_message") or task.get("error") or "unknown error"
                raise AssertionError(f"task ended with {status}: {error}")
            return task
        time.sleep(poll_seconds)
    raise TimeoutError(f"task did not finish within {timeout_seconds:.0f}s: {url}")


def assert_real_model_result(
    result: dict[str, Any],
    *,
    expected_resource_count: int,
    source: str,
) -> dict[str, Any]:
    runtime = result.get("model_runtime") or {}
    if runtime.get("mode") != "real_model" or runtime.get("real_model_used") is not True:
        raise AssertionError(
            f"{source}: expected a proven real_model run, got {runtime.get('mode')!r}"
        )
    expected_calls = expected_resource_count + 1
    if int(runtime.get("call_count") or 0) < expected_calls:
        raise AssertionError(
            f"{source}: expected at least {expected_calls} model calls, got {runtime.get('call_count')}"
        )
    if not runtime.get("provider") or not runtime.get("model"):
        raise AssertionError(f"{source}: provider/model runtime metadata is missing")
    resources = result.get("resources") or []
    if len(resources) != expected_resource_count:
        raise AssertionError(
            f"{source}: expected {expected_resource_count} resources, got {len(resources)}"
        )
    safety = result.get("safety") or {}
    if safety.get("passed") is not True:
        raise AssertionError(f"{source}: SafetyAgent did not pass: {safety.get('issues', [])}")
    return runtime


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ai-url", default="http://127.0.0.1:8000")
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="demo")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--course-id", type=int, default=2)
    parser.add_argument("--knowledge-point", default="Cache 映射方式")
    parser.add_argument("--goal", default="理解 Cache 三种映射方式并能完成基础分析题")
    parser.add_argument("--resource-types", nargs="+", default=["lecture", "quiz"])
    parser.add_argument("--timeout", type=float, default=240)
    parser.add_argument("--poll", type=float, default=0.8)
    parser.add_argument("--skip-ai-direct", action="store_true")
    parser.add_argument("--skip-backend", action="store_true")
    args = parser.parse_args()

    if args.skip_ai_direct and args.skip_backend:
        parser.error("--skip-ai-direct and --skip-backend cannot be used together")

    payload = {
        "course_id": args.course_id,
        "knowledge_point_ids": [],
        "knowledge_points": [args.knowledge_point],
        "goal": args.goal,
        "resource_types": args.resource_types,
        "difficulty": "basic",
        "student_profile": {
            "learning_goal": args.goal,
            "weak_points": [args.knowledge_point],
            "resource_preference": args.resource_types,
            "cognitive_style": ["example_first", "step_by_step"],
            "learning_pace": "每天 30 分钟",
        },
    }
    ai_runtime: dict[str, Any] | None = None
    ai_result: dict[str, Any] | None = None
    if not args.skip_ai_direct:
        ai_base = args.ai_url.rstrip("/")
        created = request_json("POST", f"{ai_base}/resource/tasks", payload)
        ai_task_id = str(created.get("task_id") or "")
        if not ai_task_id:
            raise AssertionError("AI service did not return task_id")
        ai_task = wait_for_task(
            f"{ai_base}/tasks/{ai_task_id}",
            timeout_seconds=args.timeout,
            poll_seconds=args.poll,
        )
        ai_result = ai_task.get("result") or {}
        ai_runtime = assert_real_model_result(
            ai_result,
            expected_resource_count=len(args.resource_types),
            source="AI service",
        )
        for resource in ai_result["resources"]:
            if len(str(resource.get("content") or "").strip()) < 120:
                raise AssertionError("AI service returned an unusably short resource")
            if not resource.get("evidence_chunk_ids"):
                raise AssertionError("AI service resource is missing evidence_chunk_ids")

    java_verified = False
    java_runtime: dict[str, Any] | None = None
    task_center_verified = False
    resource_persistence_verified = False
    if not args.skip_backend:
        backend_base = args.backend_url.rstrip("/")
        login = request_json(
            "POST",
            f"{backend_base}/api/auth/login",
            {"username": args.username, "password": args.password},
        )
        token = str((login.get("data") or {}).get("token") or "")
        if not token:
            raise AssertionError("Java backend login did not return a token")
        java_payload = {key: value for key, value in payload.items() if key != "student_profile"}
        java_created = request_json(
            "POST", f"{backend_base}/api/agent/resource-task", java_payload, token=token
        )
        java_task_id = str((java_created.get("data") or {}).get("task_id") or "")
        if not java_task_id:
            raise AssertionError("Java backend did not return task_id")
        java_task = wait_for_task(
            f"{backend_base}/api/agent/tasks/{java_task_id}",
            token=token,
            wrapped=True,
            timeout_seconds=args.timeout,
            poll_seconds=args.poll,
        )
        java_result = java_task.get("result") or {}
        java_runtime = assert_real_model_result(
            java_result,
            expected_resource_count=len(args.resource_types),
            source="Java backend",
        )
        if ai_runtime is not None and java_runtime.get("provider") != ai_runtime.get("provider"):
            raise AssertionError("Java backend did not preserve model provider metadata")

        task_list = request_json(
            "GET",
            f"{backend_base}/api/agent/tasks?size=20",
            token=token,
        )
        task_items = (task_list.get("data") or {}).get("items") or []
        matching_task = next(
            (item for item in task_items if item.get("task_id") == java_task_id),
            None,
        )
        if not matching_task or matching_task.get("status") != "success":
            raise AssertionError("task center did not expose the completed real-model task")
        if matching_task.get("operation_type") != "resource_task":
            raise AssertionError("task center lost the task operation type")
        task_center_verified = True

        stored_resources = java_result.get("resources") or []
        for resource in stored_resources:
            resource_id = str(resource.get("resource_id") or "")
            if not resource_id:
                raise AssertionError("Java backend result is missing persisted resource_id")
            detail_response = request_json(
                "GET",
                f"{backend_base}/api/resources/{resource_id}",
                token=token,
            )
            detail = detail_response.get("data") or {}
            if detail.get("resource_id") != resource_id:
                raise AssertionError(f"persisted resource mismatch: {resource_id}")
            if detail.get("generation_mode") != "real_model":
                raise AssertionError(f"{resource_id}: persisted generation mode is not real_model")
            if len(str(detail.get("content") or "").strip()) < 120:
                raise AssertionError(f"{resource_id}: persisted content is unusably short")
            if not detail.get("evidence"):
                raise AssertionError(f"{resource_id}: persisted RAG evidence is missing")
            if (detail.get("safety") or {}).get("passed") is not True:
                raise AssertionError(f"{resource_id}: persisted SafetyAgent result did not pass")
            if (detail.get("quality_evaluation") or {}).get("gate_passed") is not True:
                raise AssertionError(f"{resource_id}: persisted quality gate did not pass")
        resource_persistence_verified = True
        java_verified = True

    runtime = java_runtime or ai_runtime
    if runtime is None:
        raise AssertionError("no runtime result was verified")

    print(
        json.dumps(
            {
                "status": "passed",
                "provider": runtime.get("provider"),
                "model": runtime.get("model"),
                "call_count": runtime.get("call_count"),
                "total_tokens": runtime.get("total_tokens"),
                "duration_ms": runtime.get("duration_ms"),
                "resource_count": len(args.resource_types),
                "safety_passed": True,
                "java_backend_verified": java_verified,
                "task_center_verified": task_center_verified,
                "resource_persistence_verified": resource_persistence_verified,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
