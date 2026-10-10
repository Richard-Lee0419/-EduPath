#!/usr/bin/env python3
"""Verify knowledge upload, reindex, Safety refusal and deletion over real HTTP.

Use only an isolated local test database with explicit vectors and no model provider.
Fixtures are self-authored, uploaded using the seed teacher, and deleted at exit.
This checks lifecycle consistency, not retrieval quality.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import secrets
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from smoke_learning_flow import local_url, require
from smoke_real_quiz_closure import request_json, wait_for_task


def upload_document(base_url: str, token: str, filename: str, content: str) -> dict:
    boundary = f"edupath-smoke-{secrets.token_hex(12)}"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        "Content-Type: text/markdown\r\n\r\n"
        f"{content}\r\n--{boundary}--\r\n"
    ).encode("utf-8")
    request = Request(
        f"{base_url}/api/kb/upload?{urlencode({'courseId': 1})}",
        data=body,
        method="POST",
        headers={
            "Accept": "application/json",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}",
        },
    )
    with urlopen(request, timeout=30) as response:
        result = json.loads(response.read().decode("utf-8"))
    require(result.get("code") == 0, "upload returned an unsuccessful API response")
    require(isinstance(result.get("data"), dict), "upload response omitted document metadata")
    return result["data"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", type=local_url, default="http://127.0.0.1:8080")
    parser.add_argument("--ai-url", type=local_url, default="http://127.0.0.1:8000")
    parser.add_argument("--confirm-isolated-local", action="store_true",
                        help="confirm the backend uses a disposable local test database")
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--vector-provider", choices=("local", "chroma"), default="local")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.confirm_isolated_local:
        parser.error("--confirm-isolated-local is required; this smoke creates and deletes test documents")
    require(args.timeout > 0, "timeout must be positive")
    started = time.monotonic()

    runtime = request_json("GET", f"{args.ai_url}/runtime")
    llm = runtime.get("llm") or {}
    require(llm.get("configured") is False and not llm.get("provider"),
            "clear LLM configuration/provider before this smoke run")
    require((runtime.get("vector_store") or {}).get("provider") == args.vector_provider,
            "vector provider differs from the explicitly requested provider")
    require((runtime.get("embedding") or {}).get("provider") == "local", "local embeddings are required")
    require(runtime.get("environment") not in {"prod", "production"}, "production runtime is not allowed")

    login = request_json("POST", f"{args.backend_url}/api/auth/login",
                         {"username": "teacher", "password": "123456"})
    require(login.get("code") == 0, "seed teacher login failed")
    session = login.get("data") or {}
    token = str(session.get("token") or "")
    require(bool(token), "seed teacher login did not return a session")
    require((session.get("user") or {}).get("role") == "teacher", "seed teacher role is required")
    created_ids: set[int] = set()
    stages: list[dict] = []

    def api(method: str, path: str, payload: dict | None = None):
        response = request_json(method, f"{args.backend_url}{path}", payload, token=token)
        require(response.get("code") == 0, f"{path}: unsuccessful API response")
        return response.get("data")

    def upload(kind: str, content: str) -> tuple[int, str, dict]:
        filename = f"smoke-kb-{kind}-{secrets.token_hex(8)}.md"
        result = upload_document(args.backend_url, token, filename, f"# {filename}\n\n{content}")
        document_id = int(result.get("document_id") or 0)
        require(document_id > 0, "upload did not return a document ID")
        created_ids.add(document_id)
        return document_id, filename, result

    def search(document_id: int, query: str) -> tuple[set[str], list[dict]]:
        payload = {"course_id": 1, "query": query, "top_k": 10}
        java_result = api("POST", "/api/kb/search", payload) or {}
        ai_result = request_json("POST", f"{args.ai_url}/kb/search", payload)
        java_hits = java_result.get("results") or []
        ai_hits = ai_result.get("results") or []
        require([item.get("chunk_id") for item in java_hits] == [item.get("chunk_id") for item in ai_hits],
                "Java did not preserve AI search chunk identifiers/order")
        prefix = f"kb_{document_id}_"
        owned = [item for item in java_hits if str(item.get("chunk_id") or "").startswith(prefix)]
        ids = {str(item["chunk_id"]) for item in owned}
        require(len(ids) == len(owned), "search returned duplicate chunks for the document")
        return ids, owned

    def assert_indexed(result: dict) -> None:
        require(result.get("parse_status") == "parsed", "upload did not parse the fixture")
        require(result.get("index_status") == "indexed", "upload did not complete the vector index")
        require(result.get("safety_status") == "passed", "upload did not pass Safety review")
        require(not result.get("index_error"), "successful upload retained an index error")

    try:
        target_id, target_query, target = upload("target", "二叉树先序遍历：先访问根结点，再访问左子树和右子树。")
        assert_indexed(target)
        before_ids, before_hits = search(target_id, target_query)
        require(bool(before_ids), "uploaded document is not actually retrievable")
        require(any("先访问根结点" in str(item.get("content") or "") for item in before_hits),
                "retrieval lost the self-authored fixture content")
        stages.append({"stage": "safe_upload_search", "status": "passed", "retrieved_chunks": len(before_ids)})

        kept_id, kept_query, kept = upload("retained", "栈采用后进先出规则，队列采用先进先出规则。")
        assert_indexed(kept)
        kept_ids, _ = search(kept_id, kept_query)
        require(bool(kept_ids), "second document is not retrievable")

        created_task = api("POST", f"/api/kb/documents/{target_id}/reindex") or {}
        task_id = str(created_task.get("task_id") or "")
        require(bool(task_id), "reindex did not return a task ID")
        completed = wait_for_task(args.backend_url, task_id, token, args.timeout)
        assert_indexed(completed.get("result") or {})
        after_ids, _ = search(target_id, target_query)
        require(after_ids == before_ids, "reindex changed or accumulated the unchanged document's chunk IDs")
        require(search(kept_id, kept_query)[0] == kept_ids, "reindex altered an unrelated document")
        stages.append({"stage": "reindex_idempotency_and_isolation", "status": completed["status"],
                       "chunks_before": len(before_ids), "chunks_after": len(after_ids)})

        rejected_id, rejected_query, rejected = upload("refused", "这是拒绝路径测试：作弊。此句仅用于验证禁止索引。")
        require(rejected.get("parse_status") == "parsed", "rejected fixture did not reach Safety review")
        require(rejected.get("index_status") == "failed", "unsafe fixture was incorrectly indexed")
        require(rejected.get("safety_status") == "review_required", "unsafe fixture lacks a refusal status")
        require(bool(rejected.get("index_error")), "unsafe fixture lacks a user-visible refusal reason")
        require(not search(rejected_id, rejected_query)[0], "Safety-refused content is still retrievable")
        require(search(kept_id, kept_query)[0] == kept_ids, "Safety refusal altered an unrelated document")
        stages.append({"stage": "safety_refusal_not_retrievable", "status": "passed", "retrieved_chunks": 0})

        api("DELETE", f"/api/kb/documents/{target_id}")
        created_ids.remove(target_id)
        require(not search(target_id, target_query)[0], "deleted document is still retrievable")
        require(search(kept_id, kept_query)[0] == kept_ids, "deletion altered an unrelated document")
        documents = api("GET", "/api/kb/documents?courseId=1") or []
        require(all(item.get("document_id") != target_id for item in documents), "deleted document remains listed")
        stages.append({"stage": "java_delete_vector_isolation", "status": "passed", "retained_chunks": len(kept_ids)})
    finally:
        cleanup_errors: list[str] = []
        for document_id in sorted(created_ids):
            try:
                api("DELETE", f"/api/kb/documents/{document_id}")
            except Exception as exception:
                cleanup_errors.append(type(exception).__name__)
        require(not cleanup_errors, f"created-document cleanup failed: {cleanup_errors}")

    require(not search(kept_id, kept_query)[0], "retained fixture was not removed during cleanup")
    require(not search(rejected_id, rejected_query)[0], "refused fixture remains after cleanup")
    stages.append({"stage": "fixture_cleanup", "status": "passed", "documents_removed": 3})
    record = {
        "status": "passed", "verified_at_utc": datetime.now(timezone.utc).isoformat(),
        "scope": f"real Java-to-AI HTTP; isolated local database required, {args.vector_provider} vectors verified",
        "vector_provider": args.vector_provider,
        "limitations": ["database backend not independently identified by this script; no cloud deployment verification", "not a retrieval-quality evaluation",
                        "not browser verification", "reindex uses unchanged content; shorter-version replacement is covered by AI regressions"],
        "duration_seconds": round(time.monotonic() - started, 3), "stages": stages,
        "model_calls": 0, "fixtures": "three self-authored synthetic documents; removed after verification",
    }
    output = json.dumps(record, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    print(output, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
