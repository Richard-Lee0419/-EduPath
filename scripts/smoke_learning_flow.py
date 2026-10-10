#!/usr/bin/env python3
"""Exercise the real Java -> AI HTTP boundary using an isolated local database.

This is deterministic fallback verification, not a model-quality experiment.
It creates a synthetic account and learning records. Never run against production.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import secrets
import time
from urllib.parse import urlsplit

from smoke_real_quiz_closure import request_json, wait_for_task


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def local_url(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise argparse.ArgumentTypeError("only local HTTP URLs are accepted; use an isolated test database")
    if parsed.username or parsed.password or parsed.path not in {"", "/"}:
        raise argparse.ArgumentTypeError("provide a local service origin without credentials or path")
    return value.rstrip("/")


def check_fallback(result: dict, stage: str) -> None:
    require(result.get("generation_mode") == "deterministic_fallback", f"{stage}: unexpected generation mode")
    require((result.get("safety") or {}).get("passed") is True, f"{stage}: Safety did not pass")
    runtime = result.get("model_runtime") or {}
    require(runtime.get("mode") == "deterministic_fallback", f"{stage}: missing fallback runtime provenance")
    require(runtime.get("real_model_used") is False and runtime.get("configured") is False,
            f"{stage}: missing or unexpected model configuration")
    require(runtime.get("call_count") == 0, f"{stage}: missing or unexpected model calls")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", type=local_url, default="http://127.0.0.1:8080")
    parser.add_argument("--ai-url", type=local_url, default="http://127.0.0.1:8000")
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--vector-provider", choices=("local", "chroma"), default="local")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    require(args.timeout > 0, "timeout must be positive")
    started = time.monotonic()
    runtime = request_json("GET", f"{args.ai_url}/runtime")
    require(not (runtime.get("llm") or {}).get("configured"), "clear LLM configuration before this smoke run")
    require(not (runtime.get("llm") or {}).get("provider"), "clear LLM_PROVIDER before this smoke run")
    require((runtime.get("vector_store") or {}).get("provider") == args.vector_provider,
            "vector provider differs from the explicitly requested provider")
    stages: list[dict] = []
    token = ""

    def api(method: str, path: str, payload: dict | None = None) -> dict:
        response = request_json(method, f"{args.backend_url}{path}", payload, token=token)
        require(response.get("code") == 0, f"{path}: unsuccessful API response")
        return response.get("data") or {}

    def task(stage: str, path: str, payload: dict) -> dict:
        task_id = str(api("POST", path, payload).get("task_id") or "")
        require(bool(task_id), f"{stage}: missing task ID")
        completed = wait_for_task(args.backend_url, task_id, token, args.timeout)
        result = completed.get("result") or {}
        check_fallback(result, stage)
        stages.append({"stage": stage, "task_id": task_id, "status": completed["status"],
                       "generation_mode": result["generation_mode"]})
        return result

    email = f"smoke-{secrets.token_hex(6)}@example.test"
    password = secrets.token_urlsafe(18)
    account = api("POST", "/api/auth/register", {"email": email, "password": password})
    username = (account.get("user") or {}).get("username")
    require(bool(username), "registration did not return a username")
    session = api("POST", "/api/auth/login", {"username": username, "password": password})
    token = str(session.get("token") or "")
    require(bool(token), "login did not return a session")
    require(len(api("GET", "/api/courses")) > 0, "course data unavailable")

    profile_result = task("profile", "/api/profile/chat", {
        "message": "我在学习算法与数据结构，递归调用栈不熟悉，希望练习二叉树遍历，每天可以学习30分钟。",
        "course_ids": [1],
    })
    require(bool(profile_result.get("profile")), "profile extraction returned no profile")
    profile_version = int(profile_result["version"])

    resources_result = task("resources", "/api/agent/resource-task", {
        "course_id": 1, "knowledge_point_ids": [131], "difficulty": "basic",
        "resource_types": ["lecture", "mindmap", "quiz"],
        "goal": "理解二叉树递归遍历和调用栈，并完成基础练习",
    })
    resources = resources_result.get("resources") or []
    require({item.get("resource_type") for item in resources} == {"lecture", "mindmap", "quiz"},
            "resource package did not preserve requested types")
    for resource in resources:
        detail = api("GET", f"/api/resources/{resource['resource_id']}")
        require(len(str(detail.get("content") or "")) > 100, "persisted resource content is too short")
        require(bool(detail.get("evidence")), "persisted resource has no RAG evidence")
        require((detail.get("safety") or {}).get("passed") is True, "persisted resource lacks Safety approval")
        require((detail.get("quality_evaluation") or {}).get("gate_passed") is True,
                "resource quality gate failed; inspect the draft rather than counting this flow as passed")

    initial_path = task("path", "/api/path/generate", {
        "course_ids": [1], "target": "掌握二叉树递归遍历", "days": 3, "daily_minutes": 30,
    })
    require(len(initial_path.get("daily_plan") or []) == 3, "initial path must contain three days")
    for day in initial_path["daily_plan"]:
        tasks = day.get("tasks") or []
        require(bool(tasks) and all("estimated_minutes" in item for item in tasks), "path omitted task time estimates")
        minutes = sum(int(item["estimated_minutes"]) for item in tasks)
        require(0 < minutes <= 30, "path exceeded daily learning budget")

    quiz = task("quiz", "/api/quiz/generate", {
        "course_id": 1, "knowledge_point_ids": [131], "difficulty": "basic", "question_count": 3,
    })
    questions = quiz.get("questions") or []
    require(len(questions) == 3, "quiz must return exactly three requested questions")
    evidence_ids = {item.get("chunk_id") for item in quiz.get("evidence") or []}
    require(bool(evidence_ids), "quiz has no retrieved evidence")
    for question in questions:
        require("answer" not in question and "explanation" not in question, "quiz leaked answers before submission")
        refs = set(question.get("evidence_chunk_ids") or [])
        require(bool(refs) and refs.issubset(evidence_ids), "question cited evidence outside its retrieval result")

    # Incomplete submission exercises deterministic grading and remediation.
    evaluation = task("evaluation", "/api/quiz/submit", {
        "quiz_id": quiz["quiz_id"],
        "answers": [{"question_id": item["question_id"], "answer": ""} for item in questions],
    })
    require(evaluation.get("score") == 0 and evaluation.get("correct_count") == 0
            and evaluation.get("total_count") == 3, "unanswered questions were graded incorrectly")
    require((evaluation.get("evaluation") or {}).get("overall_score") == 0, "AI changed Java's authoritative score")
    profile_update = evaluation.get("profile_update") or {}
    path_update = evaluation.get("path_update") or {}
    require(profile_update.get("updated") is True and int(profile_update.get("version") or 0) > profile_version,
            "evaluation did not create a new profile version")
    require(path_update.get("updated") is True and path_update.get("trigger") == "quiz_evaluation",
            "evaluation did not trigger path remediation")
    require(int(path_update.get("version") or 0) > int(initial_path["version"]), "path version did not advance")
    require(path_update.get("source_evaluation_task_id") == evaluation.get("source_task_id"),
            "path lost evaluation provenance")
    check_fallback(path_update, "path replan")

    current_profile = api("GET", "/api/profile/current")
    current_path = api("GET", "/api/path/current")
    report = api("GET", "/api/evaluation/report")
    require(current_profile.get("version") == profile_update["version"], "profile update not persisted")
    require(current_path.get("path_id") == path_update.get("path_id"), "path replan not persisted")
    require(report.get("quiz_id") == quiz["quiz_id"] and report.get("overall_score") == 0,
            "evaluation report disagrees with submitted quiz")
    record = {
        "status": "passed", "verified_at_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "real Java-to-AI HTTP, deterministic fallback; isolated local database required",
        "vector_provider": args.vector_provider,
        "limitations": ["not a real-model quality evaluation", "not browser verification",
                        "database backend not independently identified by this script; no cloud deployment verification"],
        "duration_seconds": round(time.monotonic() - started, 3), "stages": stages,
        "resource_count": len(resources), "question_count": len(questions), "score": evaluation["score"],
        "profile_version_before": profile_version, "profile_version_after": profile_update["version"],
        "path_version_before": initial_path["version"], "path_version_after": path_update["version"],
        "model_calls": 0,
    }
    output = json.dumps(record, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    print(output, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
