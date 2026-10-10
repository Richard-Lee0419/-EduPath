#!/usr/bin/env python3
"""Run at most two paid tasks on isolated localhost services; no automatic retry.

This verifies model integration and provenance, not teaching effectiveness.
Credentials belong in the AI service environment, never in this script/report.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import secrets
import time

from smoke_learning_flow import local_url, require
from smoke_real_quiz_closure import request_json


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", type=local_url, required=True)
    parser.add_argument("--ai-url", type=local_url, required=True)
    parser.add_argument("--confirm-paid-isolated-run", action="store_true")
    parser.add_argument("--timeout", type=float, default=480)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.confirm_paid_isolated_run:
        parser.error("explicit paid-call and isolated-database confirmation is required")
    require(args.timeout > 0, "timeout must be positive")
    runtime = request_json("GET", f"{args.ai_url}/runtime")
    require((runtime.get("llm") or {}).get("configured") is True, "model is not configured")
    require(runtime.get("environment") not in {"prod", "production"}, "production is not allowed")
    account = request_json("POST", f"{args.backend_url}/api/auth/register", {
        "email": f"model-sample-{secrets.token_hex(6)}@example.test",
        "password": secrets.token_urlsafe(18),
    })
    token = str((account.get("data") or {}).get("token") or "")
    require(bool(token), "registration did not return a session")
    record = {
        "verified_at_utc": datetime.now(timezone.utc).isoformat(),
        "status": "running", "maximum_tasks": 2, "stages": [],
        "limitations": ["two synthetic tasks; no user study or model-quality benchmark",
                        "local hash embeddings and local vectors; no live embedding verification",
                        "not a complete real-model quiz/evaluation learning loop"],
    }

    def save() -> None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    tasks = [
        ("profile", "/api/profile/chat", {
            "message": "我学习算法与数据结构，递归调用栈不熟悉，希望理解二叉树遍历，偏好图示和短讲义，每天学习30分钟。",
            "course_ids": [1],
        }),
        ("lecture_resource", "/api/agent/resource-task", {
            "course_id": 1, "knowledge_point_ids": [131], "difficulty": "basic",
            "resource_types": ["lecture"], "goal": "理解二叉树前序遍历的递归调用栈，使用一个简短例子",
        }),
    ]
    save()
    for stage, path, payload in tasks:
        started = time.monotonic()
        entry = {"stage": stage, "status": "running"}
        record["stages"].append(entry)
        save()
        try:
            created = request_json("POST", f"{args.backend_url}{path}", payload, token=token)
            require(created.get("code") == 0, "task submission failed")
            task_id = str((created.get("data") or {}).get("task_id") or "")
            require(bool(task_id), "task ID is missing")
            entry["task_id"] = task_id
            deadline = started + args.timeout
            while time.monotonic() < deadline:
                response = request_json("GET", f"{args.backend_url}/api/agent/tasks/{task_id}", token=token)
                task = response.get("data") or {}
                if task.get("status") in {"success", "failed", "cancelled"}:
                    break
                time.sleep(1)
            else:
                raise TimeoutError("task exceeded the bounded wait; it has not been resubmitted")
            entry["task_status"] = task.get("status")
            result = task.get("result") or {}
            model = result.get("model_runtime") or {}
            # Whitelist metrics; exclude prompts, credentials and provider error bodies.
            entry["model_runtime"] = {key: model.get(key) for key in (
                "mode", "provider", "model", "configured", "real_model_used", "call_count",
                "total_tokens", "prompt_tokens", "completion_tokens", "failed_call_count",
            ) if key in model}
            safety = result.get("safety")
            entry["safety_passed"] = safety.get("passed") is True if isinstance(safety, dict) else None
            require(task.get("status") == "success", "task did not succeed")
            require(model.get("mode") == "real_model" and model.get("real_model_used") is True,
                    "task did not prove real model use")
            require(int(model.get("call_count") or 0) >= 2, "agent and Safety calls were not recorded")
            require(entry["safety_passed"], "Safety review did not pass")
            if stage == "profile":
                require(bool(result.get("profile")), "profile is missing")
                entry["profile_version"] = result.get("version")
            else:
                resources = result.get("resources") or []
                require(len(resources) == 1 and resources[0].get("resource_type") == "lecture",
                        "requested lecture is missing")
                resource = resources[0]
                resource_id = resource.get("resource_id") or resource.get("id")
                require(bool(resource_id), "persisted resource ID is missing")
                stored = request_json("GET", f"{args.backend_url}/api/resources/{resource_id}", token=token)
                require(stored.get("code") == 0, "persisted resource cannot be read")
                entry["resource_id"] = resource_id
                entry["evidence_count"] = len(result.get("evidence") or resource.get("evidence") or [])
                require(entry["evidence_count"] > 0, "RAG evidence is missing")
                entry["quality_gate_passed"] = ((stored.get("data") or {}).get("quality_evaluation") or {}).get("gate_passed")
                entry["persisted_status"] = (stored.get("data") or {}).get("status")
            entry["status"] = "passed"
        except Exception as exception:
            entry["status"] = "failed"
            entry["error_type"] = type(exception).__name__
            # Assertion/timeout messages are ours; omit network errors that can contain remote bodies.
            if isinstance(exception, (AssertionError, TimeoutError)):
                entry["reason"] = str(exception)
        entry["duration_seconds"] = round(time.monotonic() - started, 3)
        save()
        print(json.dumps(entry, ensure_ascii=False), flush=True)
        # A timeout may leave a billable task running. Never start another task in that case.
        if entry.get("error_type") == "TimeoutError":
            break
    record["status"] = "passed" if len(record["stages"]) == 2 and all(
        item["status"] == "passed" for item in record["stages"]) else "partial_or_failed"
    save()
    return 0 if record["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
