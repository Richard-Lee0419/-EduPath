#!/usr/bin/env python3
"""Verify the real course-corpus retrieval chain through AI and Java APIs."""

from __future__ import annotations

import argparse
import json
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def post_json(url: str, payload: dict[str, Any], token: str = "") -> dict[str, Any]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{url} returned HTTP {exception.code}: {detail}") from exception
    except URLError as exception:
        raise RuntimeError(f"cannot connect to {url}: {exception.reason}") from exception


def assert_real_search(payload: dict[str, Any], source: str) -> dict[str, Any]:
    data = payload.get("data", payload)
    corpus = data.get("corpus") or {}
    results = data.get("results") or []
    if corpus.get("mode") != "course_corpus":
        raise AssertionError(f"{source}: expected course_corpus, got {corpus.get('mode')!r}")
    if int(corpus.get("external_documents") or 0) <= 0:
        raise AssertionError(f"{source}: no external course documents are indexed")
    if not results:
        raise AssertionError(f"{source}: retrieval returned no evidence")
    first = results[0]
    required = ("chunk_id", "knowledge_point_id", "knowledge_point", "content", "source", "license_status")
    missing = [field for field in required if first.get(field) in (None, "")]
    if missing:
        raise AssertionError(f"{source}: evidence is missing provenance fields {missing}")
    return data


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ai-url", default="http://127.0.0.1:8000")
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    parser.add_argument("--course-id", type=int, default=2)
    parser.add_argument("--query", default="Cache 直接映射、全相联和组相联有什么区别")
    parser.add_argument("--top-k", type=int, default=3)
    parser.add_argument("--username", default="demo")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--skip-backend", action="store_true")
    args = parser.parse_args()

    request_payload = {"course_id": args.course_id, "query": args.query, "top_k": args.top_k}
    ai_data = assert_real_search(
        post_json(f"{args.ai_url.rstrip('/')}/kb/search", request_payload),
        "AI service",
    )

    backend_data: dict[str, Any] | None = None
    if not args.skip_backend:
        login = post_json(
            f"{args.backend_url.rstrip('/')}/api/auth/login",
            {"username": args.username, "password": args.password},
        )
        token = ((login.get("data") or {}).get("token") or "").strip()
        if not token:
            raise AssertionError("Java backend login did not return a token")
        backend_data = assert_real_search(
            post_json(
                f"{args.backend_url.rstrip('/')}/api/kb/search",
                request_payload,
                token,
            ),
            "Java backend",
        )
        if backend_data["results"][0]["chunk_id"] != ai_data["results"][0]["chunk_id"]:
            raise AssertionError("Java backend did not preserve the first AI evidence chunk")

    print(
        json.dumps(
            {
                "status": "passed",
                "course_id": args.course_id,
                "query": args.query,
                "corpus": ai_data["corpus"],
                "first_chunk_id": ai_data["results"][0]["chunk_id"],
                "knowledge_point": ai_data["results"][0]["knowledge_point"],
                "java_backend_verified": backend_data is not None,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
