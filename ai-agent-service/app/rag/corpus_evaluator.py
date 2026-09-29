from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

from app.rag.retriever import Retriever


@dataclass(frozen=True)
class RetrievalCase:
    id: str
    course_id: int
    query: str
    expected_knowledge_point_ids: tuple[int, ...]
    top_k: int = 5
    expected_source_contains: str = ""


def load_retrieval_cases(path: str | Path) -> tuple[RetrievalCase, ...]:
    candidate = Path(path)
    raw = yaml.safe_load(candidate.read_text(encoding="utf-8")) or {}
    if not isinstance(raw, dict) or int(raw.get("version") or 0) != 1:
        raise ValueError("黄金检索集 version 必须为 1")
    rows = raw.get("cases")
    if not isinstance(rows, list) or not rows:
        raise ValueError("黄金检索集必须包含非空 cases")

    cases: list[RetrievalCase] = []
    seen_ids: set[str] = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("cases 中每一项必须是对象")
        case_id = str(row.get("id") or "").strip()
        query = str(row.get("query") or "").strip()
        expected = tuple(int(value) for value in row.get("expected_knowledge_point_ids") or [])
        if not case_id or not query or not expected:
            raise ValueError("每个检索用例必须包含 id、query 和 expected_knowledge_point_ids")
        if case_id in seen_ids:
            raise ValueError(f"检索用例 id 重复: {case_id}")
        seen_ids.add(case_id)
        cases.append(
            RetrievalCase(
                id=case_id,
                course_id=int(row.get("course_id") or 0),
                query=query,
                expected_knowledge_point_ids=expected,
                top_k=max(1, min(int(row.get("top_k") or 5), 10)),
                expected_source_contains=str(row.get("expected_source_contains") or "").strip(),
            )
        )
    return tuple(cases)


def evaluate_retrieval(retriever: Retriever, cases: tuple[RetrievalCase, ...]) -> dict[str, Any]:
    if not cases:
        raise ValueError("至少需要一个检索用例")
    results: list[dict[str, Any]] = []
    per_course: dict[int, list[dict[str, Any]]] = {}
    reciprocal_rank_total = 0.0
    passed = 0

    for case in cases:
        hits = retriever.search(course_id=case.course_id, query=case.query, top_k=case.top_k)
        expected_ids = set(case.expected_knowledge_point_ids)
        rank: int | None = None
        for index, hit in enumerate(hits, start=1):
            source_matches = not case.expected_source_contains or case.expected_source_contains in hit.source
            hit_points = {hit.knowledge_point_id, *hit.related_knowledge_point_ids}
            if hit_points & expected_ids and source_matches:
                rank = index
                break
        success = rank is not None
        if success:
            passed += 1
            reciprocal_rank_total += 1.0 / rank
        row = {
            "id": case.id,
            "course_id": case.course_id,
            "query": case.query,
            "passed": success,
            "rank": rank,
            "expected_knowledge_point_ids": list(case.expected_knowledge_point_ids),
            "top_results": [
                {
                    "rank": index,
                    "knowledge_point_id": hit.knowledge_point_id,
                    "knowledge_point": hit.knowledge_point,
                    "related_knowledge_point_ids": list(hit.related_knowledge_point_ids),
                    "title": hit.title,
                    "source": hit.source,
                    "score": hit.score,
                }
                for index, hit in enumerate(hits, start=1)
            ],
        }
        results.append(row)
        per_course.setdefault(case.course_id, []).append(row)

    course_metrics = []
    for course_id, rows in sorted(per_course.items()):
        course_passed = sum(bool(row["passed"]) for row in rows)
        course_metrics.append(
            {
                "course_id": course_id,
                "cases": len(rows),
                "passed": course_passed,
                "hit_rate_at_k": round(course_passed / len(rows), 4),
            }
        )
    return {
        "version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "summary": {
            "cases": len(cases),
            "passed": passed,
            "failed": len(cases) - passed,
            "hit_rate_at_k": round(passed / len(cases), 4),
            "mean_reciprocal_rank": round(reciprocal_rank_total / len(cases), 4),
        },
        "courses": course_metrics,
        "cases": results,
    }
