#!/usr/bin/env python3
"""Run one bounded quiz -> evaluation -> profile -> path replan smoke flow."""

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
    body = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = Request(url, data=body, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{url} returned HTTP {exception.code}: {detail}") from exception
    except URLError as exception:
        raise RuntimeError(f"cannot connect to {url}: {exception.reason}") from exception


def wait_for_task(base_url: str, task_id: str, token: str, timeout_seconds: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        response = request_json("GET", f"{base_url}/api/agent/tasks/{task_id}", token=token)
        task = response.get("data") or {}
        status = task.get("status")
        if status in TERMINAL_STATUSES:
            if status != "success":
                raise AssertionError(task.get("error_message") or f"task ended with {status}")
            return task
        time.sleep(0.6)
    raise TimeoutError(f"task {task_id} did not finish within {timeout_seconds:.0f}s")


def assert_real_runtime(result: dict[str, Any], stage: str) -> dict[str, Any]:
    runtime = result.get("model_runtime") or {}
    if runtime.get("mode") != "real_model" or runtime.get("real_model_used") is not True:
        raise AssertionError(f"{stage}: real model was not used: {runtime}")
    if int(runtime.get("call_count") or 0) < 2:
        raise AssertionError(f"{stage}: expected agent and SafetyAgent calls")
    if (result.get("safety") or {}).get("passed") is not True:
        raise AssertionError(f"{stage}: SafetyAgent did not pass")
    return runtime


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="demo")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--timeout", type=float, default=240)
    args = parser.parse_args()
    base_url = args.backend_url.rstrip("/")

    login = request_json(
        "POST",
        f"{base_url}/api/auth/login",
        {"username": args.username, "password": args.password},
    )
    token = str((login.get("data") or {}).get("token") or "")
    if not token:
        raise AssertionError("login did not return a token")

    created = request_json(
        "POST",
        f"{base_url}/api/quiz/generate",
        {
            "course_id": 1,
            "knowledge_point_ids": [131],
            "difficulty": "basic",
            "question_count": 3,
        },
        token=token,
    )
    generation_task_id = str((created.get("data") or {}).get("task_id") or "")
    generation_task = wait_for_task(base_url, generation_task_id, token, args.timeout)
    quiz = generation_task.get("result") or {}
    questions = quiz.get("questions") or []
    if len(questions) != 3:
        raise AssertionError(f"expected exactly 3 questions, got {len(questions)}")
    if any("answer" in question or "explanation" in question for question in questions):
        raise AssertionError("generation response leaked an answer or explanation")
    generation_runtime = assert_real_runtime(quiz, "quiz generation")
    evidence_ids = {item.get("chunk_id") for item in quiz.get("evidence") or []}
    if not evidence_ids or any(
        not set(question.get("evidence_chunk_ids") or []).issubset(evidence_ids)
        for question in questions
    ):
        raise AssertionError("quiz question evidence is missing or out of retrieval scope")

    submit_created = request_json(
        "POST",
        f"{base_url}/api/quiz/submit",
        {
            "quiz_id": quiz["quiz_id"],
            "answers": [
                {"question_id": question["question_id"], "answer": "__intentional_wrong_answer__"}
                for question in questions
            ],
        },
        token=token,
    )
    submit_task_id = str((submit_created.get("data") or {}).get("task_id") or "")
    submit_task = wait_for_task(base_url, submit_task_id, token, args.timeout)
    result = submit_task.get("result") or {}
    evaluation_runtime = assert_real_runtime(result, "quiz evaluation")
    if result.get("score") != 0 or result.get("correct_count") != 0 or result.get("total_count") != 3:
        raise AssertionError("Java deterministic scoring result is incorrect")
    if (result.get("evaluation") or {}).get("overall_score") != 0:
        raise AssertionError("EvaluationAgent changed the authoritative score")
    expected_weak_points = list(dict.fromkeys(question.get("knowledge_point") for question in questions))
    if set((result.get("evaluation") or {}).get("weak_points") or []) != set(expected_weak_points):
        raise AssertionError("EvaluationAgent weak points do not match the incorrect questions")
    profile_update = result.get("profile_update") or {}
    if profile_update.get("updated") is not True or not profile_update.get("source_task_id"):
        raise AssertionError("profile update did not preserve the evaluation source task")
    path_update = result.get("path_update") or {}
    path_runtime = assert_real_runtime(path_update, "path replan")
    if path_update.get("updated") is not True or path_update.get("trigger") != "quiz_evaluation":
        raise AssertionError("quiz evaluation did not trigger a path version update")
    if path_update.get("source_evaluation_task_id") != result.get("source_task_id"):
        raise AssertionError("path replan does not reference the authoritative evaluation task")
    if len(path_update.get("daily_plan") or []) != 3:
        raise AssertionError("path replan must be limited to the next three days")
    if not path_update.get("changes"):
        raise AssertionError("path replan did not expose structured changes")

    profile = (request_json("GET", f"{base_url}/api/profile/current", token=token).get("data") or {})
    report = (request_json("GET", f"{base_url}/api/evaluation/report", token=token).get("data") or {})
    current_path = (request_json("GET", f"{base_url}/api/path/current", token=token).get("data") or {})
    if profile.get("source_task_id") != profile_update.get("source_task_id"):
        raise AssertionError("persisted profile source task does not match evaluation task")
    if report.get("quiz_id") != quiz.get("quiz_id") or report.get("overall_score") != 0:
        raise AssertionError("persisted evaluation report does not match the latest quiz attempt")
    if current_path.get("path_id") != path_update.get("path_id"):
        raise AssertionError("persisted current path does not match the replanned path version")

    print(json.dumps({
        "status": "passed",
        "question_count": len(questions),
        "quiz_id": quiz.get("quiz_id"),
        "generation_task_id": generation_task_id,
        "submission_task_id": submit_task_id,
        "ai_generation_task_id": quiz.get("source_task_id"),
        "ai_evaluation_task_id": result.get("source_task_id"),
        "score": result.get("score"),
        "weak_points": (result.get("evaluation") or {}).get("weak_points") or [],
        "profile_version": profile_update.get("version"),
        "path_version": path_update.get("version"),
        "previous_path_version": path_update.get("previous_version"),
        "ai_path_task_id": path_update.get("source_task_id"),
        "generation_model": generation_runtime.get("model"),
        "generation_calls": generation_runtime.get("call_count"),
        "generation_tokens": generation_runtime.get("total_tokens"),
        "evaluation_calls": evaluation_runtime.get("call_count"),
        "evaluation_tokens": evaluation_runtime.get("total_tokens"),
        "path_model": path_runtime.get("model"),
        "path_calls": path_runtime.get("call_count"),
        "path_tokens": path_runtime.get("total_tokens"),
        "safety_passed": True,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
