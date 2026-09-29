from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.rag.corpus_builder import (
    CorpusConfigurationError,
    build_course_corpus,
    write_coverage_report,
    write_seed_jsonl,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build and validate the EduPath two-course RAG corpus.")
    parser.add_argument("--curriculum", default=str(SERVICE_ROOT / "data/course_corpus/curriculum.yaml"))
    parser.add_argument("--catalog", default=str(SERVICE_ROOT / "data/course_corpus/catalog.yaml"))
    parser.add_argument("--source-dir", default=str(SERVICE_ROOT / "data/course_corpus/source_documents"))
    parser.add_argument("--output", default=str(SERVICE_ROOT / "data/seed_corpus/seed_documents.jsonl"))
    parser.add_argument("--report", default=str(SERVICE_ROOT / "data/course_corpus/coverage_report.json"))
    parser.add_argument("--strict-coverage", action="store_true")
    args = parser.parse_args()

    try:
        result = build_course_corpus(
            curriculum_path=args.curriculum,
            catalog_path=args.catalog,
            source_root=args.source_dir,
        )
    except CorpusConfigurationError as exception:
        print(json.dumps({"ok": False, "error": str(exception)}, ensure_ascii=False))
        return 1

    write_coverage_report(result, args.report)
    corpus_written = write_seed_jsonl(result, args.output)
    receipt = {
        "ok": not result.has_errors,
        "corpus_written": corpus_written,
        "output": args.output if corpus_written else None,
        "report": args.report,
        "summary": result.report["summary"],
        "competition_ready": result.report["competition_ready"],
    }
    print(json.dumps(receipt, ensure_ascii=False))
    if result.has_errors:
        return 1
    if args.strict_coverage and not result.report["competition_ready"]:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
