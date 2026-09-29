from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.rag.corpus_evaluator import evaluate_retrieval, load_retrieval_cases
from app.rag.document_loader import DocumentLoader
from app.rag.retriever import Retriever
from app.rag.vector_store import VectorStore


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate EduPath RAG retrieval against a golden query set.")
    parser.add_argument("--questions", default=str(SERVICE_ROOT / "data/course_corpus/golden_queries.yaml"))
    parser.add_argument("--seed-corpus", default=str(SERVICE_ROOT / "data/seed_corpus/seed_documents.jsonl"))
    parser.add_argument("--report", default=str(SERVICE_ROOT / "data/course_corpus/retrieval_evaluation.json"))
    parser.add_argument("--min-hit-rate", type=float, default=0.85)
    parser.add_argument("--min-mrr", type=float, default=0.75)
    args = parser.parse_args()

    cases = load_retrieval_cases(args.questions)
    loader = DocumentLoader(seed_corpus_path=args.seed_corpus)
    retriever = Retriever(loader=loader, vector_store=VectorStore())
    report = evaluate_retrieval(retriever, cases)
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    summary = report["summary"]
    passed = summary["hit_rate_at_k"] >= args.min_hit_rate and summary["mean_reciprocal_rank"] >= args.min_mrr
    print(json.dumps({"ok": passed, "report": args.report, "summary": summary}, ensure_ascii=False))
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
