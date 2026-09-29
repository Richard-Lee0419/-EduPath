from __future__ import annotations

import json
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
from typing import Any

import yaml

from app.rag.document_loader import BUILTIN_DOCUMENTS, SourceDocument
from app.rag.document_parser import DocumentParseError, parse_document_bytes
from app.rag.courseware_transformer import CoursewareTransformer


SUPPORTED_SOURCE_SUFFIXES = {
    ".pdf",
    ".docx",
    ".pptx",
    ".txt",
    ".md",
    ".markdown",
    ".rst",
    ".csv",
    ".json",
    ".jsonl",
}


class CorpusConfigurationError(ValueError):
    """Raised when the curriculum or document catalog is structurally invalid."""


@dataclass(frozen=True)
class CurriculumPoint:
    id: int
    name: str
    required: bool = True
    minimum_documents: int = 1
    parent_id: int | None = None
    aliases: tuple[str, ...] = ()


@dataclass(frozen=True)
class CurriculumCourse:
    id: int
    code: str
    name: str
    knowledge_points: tuple[CurriculumPoint, ...]


@dataclass(frozen=True)
class CorpusIssue:
    severity: str
    code: str
    message: str
    document_path: str = ""


@dataclass(frozen=True)
class CorpusBuildResult:
    seed_documents: tuple[dict[str, Any], ...]
    report: dict[str, Any]
    issues: tuple[CorpusIssue, ...]

    @property
    def has_errors(self) -> bool:
        return any(issue.severity == "error" for issue in self.issues)


def build_course_corpus(
    *,
    curriculum_path: str | Path,
    catalog_path: str | Path,
    source_root: str | Path,
    max_document_bytes: int = 50 * 1024 * 1024,
    minimum_characters: int = 120,
    include_builtin_in_coverage: bool = True,
) -> CorpusBuildResult:
    courses = load_curriculum(curriculum_path)
    course_by_code = {course.code: course for course in courses}
    point_by_id = {
        (course.code, point.id): point
        for course in courses
        for point in course.knowledge_points
    }
    catalog = _load_catalog(catalog_path)
    root = Path(source_root).resolve()

    issues: list[CorpusIssue] = []
    seed_documents: list[dict[str, Any]] = []
    external_documents: list[SourceDocument] = []
    duplicate_count = 0
    seen_hashes: dict[tuple[int, str], str] = {
        (document.course_id, _content_hash(document.content)): document.source
        for document in BUILTIN_DOCUMENTS
    }
    transformer = CoursewareTransformer()

    for index, item in enumerate(catalog, start=1):
        display_path = str(item.get("path") or f"catalog-entry-{index}")
        try:
            entry = _validate_catalog_entry(item, course_by_code, point_by_id)
            document_path = _resolve_source_path(root, entry["path"])
            if document_path.suffix.lower() not in SUPPORTED_SOURCE_SUFFIXES:
                raise CorpusConfigurationError(f"不支持的资料格式: {document_path.suffix or '(none)'}")
            if not document_path.is_file():
                raise CorpusConfigurationError("资料文件不存在")
            content = parse_document_bytes(
                document_path.read_bytes(),
                filename=document_path.name,
                content_type=str(entry.get("content_type") or ""),
                max_bytes=max_document_bytes,
            )
            transformed = transformer.transform(entry["title"], content)
            content = transformed.content
        except (CorpusConfigurationError, DocumentParseError, OSError) as exception:
            issues.append(CorpusIssue("error", "invalid_document", str(exception), display_path))
            continue

        content_hash = _content_hash(content)
        dedupe_key = (entry["course_id"], content_hash)
        if dedupe_key in seen_hashes:
            duplicate_count += 1
            issues.append(
                CorpusIssue(
                    "warning",
                    "duplicate_content",
                    f"内容与 {seen_hashes[dedupe_key]} 重复，已跳过",
                    display_path,
                )
            )
            continue
        seen_hashes[dedupe_key] = display_path

        if len(content) < minimum_characters:
            issues.append(
                CorpusIssue(
                    "warning",
                    "short_document",
                    f"正文仅 {len(content)} 字符，建议补充定义、示例、推导或练习",
                    display_path,
                )
            )

        if entry["license_status"] != "confirmed":
            issues.append(
                CorpusIssue(
                    "warning",
                    "license_not_confirmed",
                    "Source authorization has not been confirmed for competition delivery",
                    display_path,
                )
            )

        relative_path = document_path.relative_to(root).as_posix()
        row = {
            "course_id": entry["course_id"],
            "course_code": entry["course_code"],
            "knowledge_point_id": entry["knowledge_point_id"],
            "knowledge_point": entry["knowledge_point"],
            "title": entry["title"],
            "content": content,
            "source": entry["source"],
            "license": entry["license"],
            "author": entry["author"],
            "source_url": entry["source_url"],
            "content_hash": content_hash,
            "file_path": relative_path,
            "license_status": entry["license_status"],
            "related_knowledge_point_ids": list(entry["related_knowledge_point_ids"]),
            "document_type": entry["document_type"],
            "language": entry["language"],
            "contains_examples": entry["contains_examples"] or transformed.contains_examples,
            "key_terms": list(transformed.key_terms),
            "teaching_roles": list(transformed.teaching_roles),
            "section_count": transformed.section_count,
            "raw_character_count": transformed.raw_character_count,
            "clean_character_count": transformed.clean_character_count,
        }
        seed_documents.append(row)
        external_documents.append(
            SourceDocument(
                course_id=entry["course_id"],
                course_code=entry["course_code"],
                knowledge_point_id=entry["knowledge_point_id"],
                knowledge_point=entry["knowledge_point"],
                title=entry["title"],
                content=content,
                source=entry["source"],
                license=entry["license"],
                author=entry["author"],
                source_url=entry["source_url"],
                content_hash=content_hash,
                license_status=entry["license_status"],
                related_knowledge_point_ids=entry["related_knowledge_point_ids"],
                document_type=entry["document_type"],
                language=entry["language"],
                contains_examples=entry["contains_examples"] or transformed.contains_examples,
            )
        )

    coverage_documents = [*BUILTIN_DOCUMENTS, *external_documents] if include_builtin_in_coverage else external_documents
    report = _build_coverage_report(
        courses=courses,
        coverage_documents=coverage_documents,
        external_documents=external_documents,
        issues=issues,
        duplicate_count=duplicate_count,
    )
    return CorpusBuildResult(tuple(seed_documents), report, tuple(issues))


def load_curriculum(path: str | Path) -> tuple[CurriculumCourse, ...]:
    raw = _load_yaml_mapping(path, "课程清单")
    if int(raw.get("version") or 0) != 1:
        raise CorpusConfigurationError("课程清单 version 必须为 1")
    rows = raw.get("courses")
    if not isinstance(rows, list) or not rows:
        raise CorpusConfigurationError("课程清单必须包含非空 courses")

    courses: list[CurriculumCourse] = []
    course_ids: set[int] = set()
    course_codes: set[str] = set()
    global_point_ids: set[int] = set()
    for row in rows:
        if not isinstance(row, dict):
            raise CorpusConfigurationError("courses 中每一项必须是对象")
        course_id = int(row.get("id") or 0)
        course_code = str(row.get("code") or "").strip()
        course_name = str(row.get("name") or "").strip()
        if course_id <= 0 or not course_code or not course_name:
            raise CorpusConfigurationError("课程必须包含正整数 id、code 和 name")
        if course_id in course_ids or course_code in course_codes:
            raise CorpusConfigurationError(f"课程 id 或 code 重复: {course_code}")
        course_ids.add(course_id)
        course_codes.add(course_code)

        point_rows = row.get("knowledge_points")
        if not isinstance(point_rows, list) or not point_rows:
            raise CorpusConfigurationError(f"课程 {course_code} 缺少 knowledge_points")
        points: list[CurriculumPoint] = []
        for point_row in point_rows:
            if not isinstance(point_row, dict):
                raise CorpusConfigurationError(f"课程 {course_code} 的知识点必须是对象")
            point_id = int(point_row.get("id") or 0)
            name = str(point_row.get("name") or "").strip()
            minimum_documents = int(point_row.get("minimum_documents") or 1)
            if point_id <= 0 or not name or minimum_documents <= 0:
                raise CorpusConfigurationError(f"课程 {course_code} 存在无效知识点")
            if point_id in global_point_ids:
                raise CorpusConfigurationError(f"知识点 id 重复: {point_id}")
            global_point_ids.add(point_id)
            aliases = tuple(str(value).strip() for value in point_row.get("aliases") or [] if str(value).strip())
            parent_id = point_row.get("parent_id")
            points.append(
                CurriculumPoint(
                    id=point_id,
                    name=name,
                    required=bool(point_row.get("required", True)),
                    minimum_documents=minimum_documents,
                    parent_id=int(parent_id) if parent_id is not None else None,
                    aliases=aliases,
                )
            )
        courses.append(CurriculumCourse(course_id, course_code, course_name, tuple(points)))
    return tuple(courses)


def write_seed_jsonl(result: CorpusBuildResult, output_path: str | Path) -> bool:
    if not result.seed_documents:
        return False
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = "\n".join(json.dumps(row, ensure_ascii=False) for row in result.seed_documents) + "\n"
    _atomic_write(path, payload)
    return True


def write_coverage_report(result: CorpusBuildResult, output_path: str | Path) -> None:
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    _atomic_write(path, json.dumps(result.report, ensure_ascii=False, indent=2) + "\n")


def _load_catalog(path: str | Path) -> list[dict[str, Any]]:
    raw = _load_yaml_mapping(path, "资料目录")
    if int(raw.get("version") or 0) != 1:
        raise CorpusConfigurationError("资料目录 version 必须为 1")
    documents = raw.get("documents")
    if not isinstance(documents, list):
        raise CorpusConfigurationError("资料目录 documents 必须是数组")
    if not all(isinstance(item, dict) for item in documents):
        raise CorpusConfigurationError("资料目录 documents 中每一项必须是对象")
    return documents


def _validate_catalog_entry(
    item: dict[str, Any],
    course_by_code: dict[str, CurriculumCourse],
    point_by_id: dict[tuple[str, int], CurriculumPoint],
) -> dict[str, Any]:
    path = str(item.get("path") or "").strip()
    course_code = str(item.get("course_code") or "").strip()
    title = str(item.get("title") or "").strip()
    source = str(item.get("source") or "").strip()
    license_name = str(item.get("license") or "").strip()
    if not path or not course_code or not title or not source or not license_name:
        raise CorpusConfigurationError("资料必须填写 path、course_code、title、source 和 license")
    course = course_by_code.get(course_code)
    if course is None:
        raise CorpusConfigurationError(f"未知课程: {course_code}")
    try:
        point_id = int(item.get("knowledge_point_id") or 0)
    except (TypeError, ValueError) as exception:
        raise CorpusConfigurationError("knowledge_point_id 必须是整数") from exception
    point = point_by_id.get((course_code, point_id))
    if point is None:
        raise CorpusConfigurationError(f"知识点 {point_id} 不属于课程 {course_code}")
    related_ids: list[int] = []
    for raw_related_id in item.get("related_knowledge_point_ids") or []:
        try:
            related_id = int(raw_related_id)
        except (TypeError, ValueError) as exception:
            raise CorpusConfigurationError("related_knowledge_point_ids must contain integers") from exception
        if related_id == point_id:
            continue
        if (course_code, related_id) not in point_by_id:
            raise CorpusConfigurationError(
                f"Related knowledge point {related_id} does not belong to course {course_code}"
            )
        if related_id not in related_ids:
            related_ids.append(related_id)

    license_status = str(item.get("license_status") or "pending").strip().lower()
    if license_status not in {"pending", "confirmed", "restricted"}:
        raise CorpusConfigurationError("license_status must be pending, confirmed, or restricted")
    return {
        "path": path,
        "course_id": course.id,
        "course_code": course.code,
        "knowledge_point_id": point.id,
        "knowledge_point": point.name,
        "title": title,
        "source": source,
        "license": license_name,
        "author": str(item.get("author") or "").strip(),
        "source_url": str(item.get("source_url") or "").strip(),
        "content_type": str(item.get("content_type") or "").strip(),
        "license_status": license_status,
        "related_knowledge_point_ids": tuple(related_ids),
        "document_type": str(item.get("document_type") or "lecture").strip(),
        "language": str(item.get("language") or "").strip(),
        "contains_examples": bool(item.get("contains_examples", False)),
    }


def _resolve_source_path(root: Path, raw_path: str) -> Path:
    candidate = Path(raw_path)
    if candidate.is_absolute():
        raise CorpusConfigurationError("资料 path 必须相对于 source_documents 目录")
    resolved = (root / candidate).resolve()
    try:
        resolved.relative_to(root)
    except ValueError as exception:
        raise CorpusConfigurationError("资料 path 不允许跳出 source_documents 目录") from exception
    return resolved


def _build_coverage_report(
    *,
    courses: tuple[CurriculumCourse, ...],
    coverage_documents: list[SourceDocument],
    external_documents: list[SourceDocument],
    issues: list[CorpusIssue],
    duplicate_count: int,
) -> dict[str, Any]:
    counts: Counter[tuple[str, int]] = Counter()
    for document in coverage_documents:
        for point_id in {document.knowledge_point_id, *document.related_knowledge_point_ids}:
            counts[(document.course_code, point_id)] += 1
    course_reports: list[dict[str, Any]] = []
    total_required = 0
    total_covered = 0
    for course in courses:
        required_points = [point for point in course.knowledge_points if point.required]
        covered_points = [
            point
            for point in required_points
            if counts[(course.code, point.id)] >= point.minimum_documents
        ]
        uncovered_points = [
            {
                "id": point.id,
                "name": point.name,
                "documents": counts[(course.code, point.id)],
                "minimum_documents": point.minimum_documents,
            }
            for point in required_points
            if counts[(course.code, point.id)] < point.minimum_documents
        ]
        total_required += len(required_points)
        total_covered += len(covered_points)
        course_documents = [document for document in coverage_documents if document.course_code == course.code]
        external_course_documents = [document for document in external_documents if document.course_code == course.code]
        course_reports.append(
            {
                "course_id": course.id,
                "course_code": course.code,
                "course_name": course.name,
                "required_knowledge_points": len(required_points),
                "covered_knowledge_points": len(covered_points),
                "coverage_rate": _rate(len(covered_points), len(required_points)),
                "document_count": len(course_documents),
                "external_document_count": len(external_course_documents),
                "character_count": sum(len(document.content) for document in course_documents),
                "uncovered_knowledge_points": uncovered_points,
            }
        )

    error_count = sum(issue.severity == "error" for issue in issues)
    warning_count = sum(issue.severity == "warning" for issue in issues)
    pending_license_count = sum(document.license_status != "confirmed" for document in external_documents)
    example_document_count = sum(document.contains_examples for document in external_documents)
    coverage_rate = _rate(total_covered, total_required)
    content_ready = error_count == 0 and total_required > 0 and total_covered == total_required
    competition_ready = content_ready and pending_license_count == 0
    status = "competition_ready" if competition_ready else "incomplete"
    if content_ready and pending_license_count:
        status = "content_ready_license_pending"
    return {
        "version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "ready_for_index": error_count == 0 and bool(external_documents),
        "competition_ready": competition_ready,
        "summary": {
            "required_knowledge_points": total_required,
            "covered_knowledge_points": total_covered,
            "coverage_rate": coverage_rate,
            "builtin_documents": len(BUILTIN_DOCUMENTS),
            "external_documents": len(external_documents),
            "duplicate_documents_skipped": duplicate_count,
            "error_count": error_count,
            "warning_count": warning_count,
            "pending_license_documents": pending_license_count,
            "example_documents": example_document_count,
            "content_ready": content_ready,
        },
        "courses": course_reports,
        "issues": [asdict(issue) for issue in issues],
    }


def _load_yaml_mapping(path: str | Path, label: str) -> dict[str, Any]:
    candidate = Path(path)
    if not candidate.is_file():
        raise CorpusConfigurationError(f"{label}不存在: {candidate}")
    try:
        raw = yaml.safe_load(candidate.read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as exception:
        raise CorpusConfigurationError(f"{label}无法读取: {exception}") from exception
    if not isinstance(raw, dict):
        raise CorpusConfigurationError(f"{label}顶层必须是对象")
    return raw


def _content_hash(content: str) -> str:
    normalized = " ".join(content.split())
    return sha256(normalized.encode("utf-8")).hexdigest()


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 4) if denominator else 0.0


def _atomic_write(path: Path, content: str) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)
