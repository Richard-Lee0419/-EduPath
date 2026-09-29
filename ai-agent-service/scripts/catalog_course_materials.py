from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml


SERVICE_ROOT = Path(__file__).resolve().parents[1]
COURSE_CODES = {
    "算法与数据结构（一）": "data_structures_algorithms",
    "算法与数据结构（二）": "data_structures_algorithms",
    "计算机结构（一）": "computer_organization",
    "计算机结构(二)": "computer_organization",
}
LICENSE = "User-provided courseware; competition authorization confirmation pending"


def main() -> int:
    parser = argparse.ArgumentParser(description="Create a reviewed EduPath courseware catalog from the inventory.")
    parser.add_argument("--inventory", default=str(SERVICE_ROOT / "data/course_corpus/material_inventory.json"))
    parser.add_argument("--catalog", default=str(SERVICE_ROOT / "data/course_corpus/catalog.yaml"))
    parser.add_argument("--report", default=str(SERVICE_ROOT / "data/course_corpus/material_processing_report.json"))
    args = parser.parse_args()

    inventory = json.loads(Path(args.inventory).read_text(encoding="utf-8"))
    documents: list[dict[str, Any]] = []
    exclusions: list[dict[str, str]] = []
    for row in inventory["files"]:
        path = str(row["path"])
        classification, reason = _classify(path, row)
        if classification is None:
            exclusions.append({"path": path, "reason": reason})
            continue
        documents.append(_catalog_entry(path, row, classification))

    derived_path = "derived_notes/heaps-and-heapsort.md"
    documents.append(
        {
            "path": derived_path,
            "course_code": "data_structures_algorithms",
            "knowledge_point_id": 133,
            "related_knowledge_point_ids": [151, 18],
            "title": "Heaps and Heapsort（图像课件提炼笔记）",
            "source": "derived_notes/heaps-and-heapsort.md (derived from user-provided courseware/算法与数据结构（二）/Heaps and Heapsort.pdf)",
            "license": LICENSE,
            "license_status": "pending",
            "document_type": "derived_note",
            "language": "zh-en",
            "contains_examples": True,
        }
    )
    documents.sort(key=lambda item: str(item["path"]).lower())

    catalog_payload = {"version": 1, "documents": documents}
    catalog_path = Path(args.catalog)
    catalog_path.write_text(
        yaml.safe_dump(catalog_payload, allow_unicode=True, sort_keys=False, width=120),
        encoding="utf-8",
    )
    report = {
        "version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source_inventory": "data/course_corpus/material_inventory.json",
        "summary": {
            "inventory_files": len(inventory["files"]),
            "catalog_documents": len(documents),
            "derived_documents": 1,
            "excluded_files": len(exclusions),
            "ocr_required_files": sum(item["reason"] == "ocr_required_low_text" for item in exclusions),
            "duplicate_formats_skipped": sum(item["reason"] == "duplicate_format_pdf_preferred" for item in exclusions),
            "authorization_status": "pending",
        },
        "exclusions": exclusions,
        "catalog_paths": [item["path"] for item in documents],
        "documents": [
            {
                "path": item["path"],
                "course_code": item["course_code"],
                "knowledge_point_id": item["knowledge_point_id"],
                "related_knowledge_point_ids": item["related_knowledge_point_ids"],
                "document_type": item["document_type"],
                "contains_examples": item["contains_examples"],
            }
            for item in documents
        ],
    }
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"ok": True, **report["summary"], "catalog": args.catalog, "report": args.report}, ensure_ascii=False))
    return 0


def _catalog_entry(path: str, row: dict[str, Any], classification: tuple[int, tuple[int, ...]]) -> dict[str, Any]:
    folder, filename = path.split("/", 1)
    point_id, related_ids = classification
    headings = [str(value) for value in row.get("headings") or []]
    title = _clean_title(filename, headings)
    text_hint = " ".join(headings) + " " + str(row.get("sample") or "")[:1800]
    document_type = "lecture"
    if re.search(r"class\s*test|practice\s*exam", filename, re.I):
        document_type = "solution" if re.search(r"solution|souliton", filename, re.I) else "exam"
    contains_examples = bool(re.search(r"\b(example|exercise|question|practice|test|exam|implementation)\b", text_hint, re.I))
    return {
        "path": path,
        "course_code": COURSE_CODES[folder],
        "knowledge_point_id": point_id,
        "related_knowledge_point_ids": list(related_ids),
        "title": title,
        "source": f"User-provided courseware/{path}",
        "license": LICENSE,
        "license_status": "pending",
        "document_type": document_type,
        "language": "en",
        "contains_examples": contains_examples,
    }


def _clean_title(filename: str, headings: list[str]) -> str:
    stem = Path(filename).stem
    stem = re.sub(r"^(?:\d+[_ -]+|lec(?:ture)?\s*\d*[_ -]*)", "", stem, flags=re.I)
    stem = re.sub(r"(?:\[COF\]\[PDF\]|_?202[4-6](?:_final[a-z]?)?|\(xdu miec 2024\))", "", stem, flags=re.I)
    stem = re.sub(r"[_ -]+", " ", stem).strip()
    meaningful = next((heading for heading in headings if len(heading) >= 5 and "LECTURER" not in heading.upper()), "")
    return stem or meaningful or filename


def _classify(path: str, row: dict[str, Any]) -> tuple[tuple[int, tuple[int, ...]] | None, str]:
    folder, filename = path.split("/", 1)
    name = filename.lower()
    characters = int(row.get("text_characters") or 0)
    if row.get("status") != "parsed":
        return None, "invalid_or_unreadable_file"
    if name.endswith(".pptx"):
        return None, "duplicate_format_pdf_preferred"
    if "heaps and heapsort" in name:
        return None, "replaced_by_visually_verified_derived_note"
    if characters < 1000:
        return None, "ocr_required_low_text"

    if folder == "算法与数据结构（一）":
        if "history of programming" in name or name == "lecture 4.pdf" or name == "lecture 5.pdf":
            return None, "programming_prerequisite_not_core_course_knowledge"
        if "circular queue" in name:
            return (12, ()), ""
        if "big o" in name:
            return (18, (20,)), ""
        if "linked list" in name:
            return (11, ()), ""
        if "sorting" in name and "quick" not in name:
            return (151, (18,)), ""
        if "queue" in name or "stack" in name:
            return (12, ()), ""
        if "recursion" in name:
            return (16, (18,)), ""
        if "quick sort" in name:
            return (151, (16, 18)), ""
        if name.startswith("lecture 02"):
            return (20, (18,)), ""
        if name.startswith("lecture 1"):
            return (20, (11, 18)), ""
        if name == "lecture 6.pdf":
            return (11, ()), ""
        if name == "lecture 7.pdf":
            return (11, (20, 152)), ""
        if name == "lecture 8.pdf":
            return (152, (11, 20)), ""

    if folder == "算法与数据结构（二）":
        if name.startswith("00 -"):
            return None, "module_orientation_not_core_course_knowledge"
        if "foundations2026_finalb" in name:
            return (18, (11, 20)), ""
        if "foundations2026_finalc" in name:
            return (18, (16, 17)), ""
        if "search_trees" in name:
            return (132, (131,)), ""
        if "merge sort" in name:
            return (151, (16, 18)), ""
        if "quicksort" in name:
            return (151, (16, 18)), ""
        if "hash tables" in name:
            return (152, (18,)), ""
        if "graph theory part 1" in name:
            return (141, ()), ""
        if "graph theory part 2" in name:
            return (19, (141, 142)), ""
        if name.startswith("06 -"):
            return (17, (19,)), ""
        if name.startswith("07 -"):
            return (19, (17,)), ""
        if "cryptography" in name:
            return (201, (18,)), ""
        if name == "basic.pdf":
            return (20, (18,)), ""
        if name == "dynamic programming.pdf":
            return (17, (16, 18)), ""

    if folder == "计算机结构（一）":
        chapter_rules = (
            ("01_cs220", (211, ())),
            ("02_cs220", (211, (212,))),
            ("03_cs220", (222, ())),
            ("04_cs220", (222, ())),
            ("05_cs220", (222, (225,))),
            ("06_cs220", (222, (223,))),
            ("07_cs220", (223, (221,))),
            ("08_cs220", (223, ())),
            ("09_cs220", (224, ())),
            ("10_cs220", (224, (232,))),
            ("11_cs220", (224, ())),
            ("12_cs220", (221, (224,))),
            ("13_cs220", (212, (221,))),
            ("14_cs220", (253, (254,))),
            ("15_cs220", (253, (254,))),
            ("16_cs220", (254, (253,))),
            ("17_cs220", (254, (253,))),
        )
        for prefix, points in chapter_rules:
            if name.startswith(prefix):
                return points, ""

    if folder == "计算机结构(二)":
        if "class" in name and "test" in name:
            return (29, (211, 221, 224, 231, 232, 261)), ""
        if "practice exam" in name:
            return (29, (212, 224, 231, 232, 261)), ""
        if "l11 12" in name:
            return (253, (251, 254)), ""
        if "l14" in name:
            return (242, (231, 232, 28)), ""
        if "l15" in name:
            return (27, (261,)), ""
        if "l16" in name:
            return (254, (253,)), ""
        if "_l1." in name:
            return (241, (28, 232)), ""
        if "_l2." in name:
            return (232, (231,)), ""
        if "_l3." in name:
            return (231, (232,)), ""
        if "_l4." in name:
            return (231, (232,)), ""
        if "_l6." in name:
            return (225, (222,)), ""
        if "_l7." in name or "_l8." in name:
            return (225, (222,)), ""

    return None, "manual_classification_required"


if __name__ == "__main__":
    raise SystemExit(main())
