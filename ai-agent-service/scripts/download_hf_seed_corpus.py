#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


HF_API = "https://datasets-server.huggingface.co"


@dataclass(frozen=True)
class TopicPlan:
    course_id: int
    course_code: str
    knowledge_point_id: int
    knowledge_point: str
    dataset: str
    config: str
    split: str
    query: str
    source_kind: str
    license: str


TOPIC_PLANS: tuple[TopicPlan, ...] = (
    TopicPlan(1, "data_structures_algorithms", 9101, "二叉树与递归遍历", "codeparrot/apps", "all", "train", "binary tree traversal recursion", "dataset-viewer-search", "unknown"),
    TopicPlan(1, "data_structures_algorithms", 9102, "图遍历与最短路径", "codeparrot/apps", "all", "train", "graph shortest path bfs dijkstra", "dataset-viewer-search", "unknown"),
    TopicPlan(1, "data_structures_algorithms", 9103, "动态规划与复杂度分析", "codeparrot/apps", "all", "train", "dynamic programming time complexity", "dataset-viewer-search", "unknown"),
    TopicPlan(2, "computer_organization", 9201, "Cache 映射与局部性", "HuggingFaceFW/fineweb-edu", "sample-10BT", "train", "cache memory mapping locality computer architecture", "dataset-viewer-search", "ODC-By"),
    TopicPlan(2, "computer_organization", 9202, "CPU 流水线与冒险", "HuggingFaceFW/fineweb-edu", "sample-10BT", "train", "CPU pipeline hazards forwarding branch prediction", "dataset-viewer-search", "ODC-By"),
    TopicPlan(2, "computer_organization", 9203, "虚拟存储、页表与 TLB", "HuggingFaceFW/fineweb-edu", "sample-10BT", "train", "virtual memory page table TLB address translation", "dataset-viewer-search", "ODC-By"),
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Download EduPath seed corpus rows from Hugging Face Dataset Viewer.")
    parser.add_argument("--output-dir", default="data/seed_corpus", help="Output directory relative to ai-agent-service.")
    parser.add_argument("--max-per-topic", type=int, default=6, help="Maximum rows to keep for each topic plan.")
    parser.add_argument("--timeout", type=float, default=30.0, help="HTTP timeout in seconds.")
    parser.add_argument("--token", default="", help="Optional Hugging Face token for gated/rate-limited datasets.")
    parser.add_argument("--offline-sample", action="store_true", help="Write a tiny local sample instead of calling Hugging Face.")
    args = parser.parse_args()

    service_root = Path(__file__).resolve().parents[1]
    output_dir = (service_root / args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    if args.offline_sample:
        documents, errors = _offline_sample(), []
    else:
        documents, errors = download_seed_documents(
            max_per_topic=max(1, args.max_per_topic),
            timeout=args.timeout,
            token=args.token,
        )

    corpus_path = output_dir / "seed_documents.jsonl"
    with corpus_path.open("w", encoding="utf-8") as handle:
        for document in documents:
            handle.write(json.dumps(document, ensure_ascii=False) + "\n")

    manifest = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "embedding_model": "BAAI/bge-m3",
        "documents_written": len(documents),
        "corpus_path": str(corpus_path),
        "download_status": "success" if documents and not errors else "partial" if documents else "failed",
        "datasets": sorted({plan.dataset for plan in TOPIC_PLANS}),
        "topics": [plan.__dict__ for plan in TOPIC_PLANS],
        "errors": errors,
    }
    (output_dir / "hf_seed_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    _write_readme(output_dir)

    if errors:
        print("Completed with download errors:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
    print(f"Wrote {len(documents)} seed documents to {corpus_path}")
    return 0 if documents else 1


def download_seed_documents(*, max_per_topic: int, timeout: float, token: str) -> tuple[list[dict[str, Any]], list[str]]:
    documents: list[dict[str, Any]] = []
    errors: list[str] = []
    for plan in TOPIC_PLANS:
        try:
            rows = _search_rows(plan, max_per_topic=max_per_topic, timeout=timeout, token=token)
        except Exception as exception:
            errors.append(f"{plan.dataset} / {plan.knowledge_point}: {exception}")
            continue
        for row_index, row in enumerate(rows, start=1):
            text = _row_text(row)
            if not _looks_relevant(text, plan.query):
                continue
            documents.append(_to_document(plan, row_index, row, text))
    return documents, errors


def _search_rows(plan: TopicPlan, *, max_per_topic: int, timeout: float, token: str) -> list[dict[str, Any]]:
    params = {
        "dataset": plan.dataset,
        "config": plan.config,
        "split": plan.split,
        "query": plan.query,
    }
    payload = _request_json(f"{HF_API}/search?{urllib.parse.urlencode(params)}", timeout=timeout, token=token)
    rows = payload.get("rows") or payload.get("search") or []
    if not rows:
        rows = _fallback_rows(plan, max_per_topic=max_per_topic, timeout=timeout, token=token)
    normalized: list[dict[str, Any]] = []
    for item in rows[: max_per_topic * 3]:
        row = item.get("row") if isinstance(item, dict) else None
        if isinstance(row, dict):
            normalized.append(row)
        elif isinstance(item, dict):
            normalized.append(item)
        if len(normalized) >= max_per_topic:
            break
    return normalized


def _fallback_rows(plan: TopicPlan, *, max_per_topic: int, timeout: float, token: str) -> list[dict[str, Any]]:
    params = {
        "dataset": plan.dataset,
        "config": plan.config,
        "split": plan.split,
        "offset": 0,
        "length": max(20, max_per_topic * 4),
    }
    payload = _request_json(f"{HF_API}/rows?{urllib.parse.urlencode(params)}", timeout=timeout, token=token)
    rows = payload.get("rows") or []
    normalized = []
    for item in rows:
        row = item.get("row") if isinstance(item, dict) else None
        if isinstance(row, dict):
            normalized.append(row)
    return normalized


def _request_json(url: str, *, timeout: float, token: str) -> dict[str, Any]:
    headers = {"User-Agent": "EduPath seed corpus downloader"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="ignore")[:300]
        raise RuntimeError(f"HTTP {exception.code} from Hugging Face Dataset Viewer: {detail}") from exception


def _row_text(row: dict[str, Any]) -> str:
    candidates = [
        row.get("text"),
        row.get("problem"),
        row.get("question"),
        row.get("description"),
        row.get("solutions"),
        row.get("input_output"),
    ]
    parts: list[str] = []
    for value in candidates:
        if value is None:
            continue
        if isinstance(value, (dict, list)):
            value = json.dumps(value, ensure_ascii=False)
        text = str(value).strip()
        if text:
            parts.append(text)
    return "\n\n".join(parts)


def _looks_relevant(text: str, query: str) -> bool:
    if not text.strip():
        return False
    lowered = text.lower()
    terms = [term.lower() for term in query.split() if len(term) >= 3]
    return any(term in lowered for term in terms)


def _to_document(plan: TopicPlan, row_index: int, row: dict[str, Any], text: str) -> dict[str, Any]:
    title = str(row.get("title") or row.get("name") or f"{plan.knowledge_point} seed {row_index}").strip()
    return {
        "course_id": plan.course_id,
        "course_code": plan.course_code,
        "knowledge_point_id": plan.knowledge_point_id,
        "knowledge_point": plan.knowledge_point,
        "title": title[:120],
        "content": _trim_text(text),
        "source": f"https://huggingface.co/datasets/{plan.dataset}",
        "dataset": plan.dataset,
        "dataset_config": plan.config,
        "split": plan.split,
        "license": plan.license,
        "downloaded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }


def _trim_text(text: str, max_chars: int = 3600) -> str:
    normalized = "\n".join(line.strip() for line in text.splitlines() if line.strip())
    return normalized[:max_chars]


def _offline_sample() -> list[dict[str, Any]]:
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    return [
        {
            "course_id": 1,
            "course_code": "data_structures_algorithms",
            "knowledge_point_id": 9101,
            "knowledge_point": "二叉树与递归遍历",
            "title": "Offline sample: binary tree traversal",
            "content": "二叉树遍历可以按前序、中序、后序组织递归。递归出口是当前节点为空，时间复杂度 O(n)，空间复杂度取决于树高。",
            "source": "offline-sample",
            "dataset": "offline-sample",
            "license": "local-demo-only",
            "downloaded_at": now,
        },
        {
            "course_id": 2,
            "course_code": "computer_organization",
            "knowledge_point_id": 9201,
            "knowledge_point": "Cache 映射与局部性",
            "title": "Offline sample: cache mapping",
            "content": "Cache 映射把主存块放入缓存行。直接映射硬件简单但冲突缺失多，组相联在硬件成本和命中率之间折中。",
            "source": "offline-sample",
            "dataset": "offline-sample",
            "license": "local-demo-only",
            "downloaded_at": now,
        },
    ]


def _write_readme(output_dir: Path) -> None:
    readme = """# EduPath Hugging Face Seed Corpus

`seed_documents.jsonl` is generated by `scripts/download_hf_seed_corpus.py` and loaded automatically by `DocumentLoader` when present.

Default source candidates:

- `codeparrot/apps`: algorithmic programming problems for data structures and algorithms.
- `HuggingFaceFW/fineweb-edu`: education-filtered web text for computer organization and architecture concepts.
- `BAAI/bge-m3`: recommended open-source embedding model for multilingual dense retrieval.

Run from `ai-agent-service`:

```bash
python scripts/download_hf_seed_corpus.py --max-per-topic 6
```

If Hugging Face is unavailable but a demo corpus is needed:

```bash
python scripts/download_hf_seed_corpus.py --offline-sample
```
"""
    (output_dir / "README.md").write_text(readme, encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
