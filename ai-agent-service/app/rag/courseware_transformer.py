from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass

from app.rag.semantic_chunker import SemanticChunker


@dataclass(frozen=True)
class CoursewareTransformResult:
    """Normalized courseware plus lightweight pedagogical metadata."""

    content: str
    key_terms: tuple[str, ...]
    teaching_roles: tuple[str, ...]
    section_count: int
    contains_examples: bool
    raw_character_count: int
    clean_character_count: int


class CoursewareTransformer:
    """Turn slide/document extraction into retrieval-friendly course text.

    The transformation is deliberately conservative: it removes obvious slide
    furniture but keeps definitions, formulas, pseudocode, worked examples and
    exercises verbatim so that the RAG layer can cite the original material.
    """

    def __init__(self, semantic_chunker: SemanticChunker | None = None) -> None:
        self.semantic_chunker = semantic_chunker or SemanticChunker()

    def transform(self, title: str, content: str) -> CoursewareTransformResult:
        raw = _normalize_newlines(content)
        lines = [line.strip() for line in raw.splitlines()]
        boilerplate = _repeated_boilerplate(lines)
        cleaned_lines: list[str] = []
        for line in lines:
            if not line:
                if cleaned_lines and cleaned_lines[-1]:
                    cleaned_lines.append("")
                continue
            if line in boilerplate or _is_slide_furniture(line):
                continue
            cleaned_lines.append(line)

        clean = "\n".join(cleaned_lines).strip()
        clean = re.sub(r"\n{3,}", "\n\n", clean)
        if not clean:
            clean = raw.strip()

        specs = self.semantic_chunker.split(title, clean)
        term_counter: Counter[str] = Counter()
        roles: list[str] = []
        for spec in specs:
            term_counter.update(spec.anchors)
            for role in spec.roles:
                if role not in roles and role != "concept":
                    roles.append(role)

        for role, pattern in _ROLE_PATTERNS:
            if pattern.search(clean) and role not in roles:
                roles.append(role)

        contains_examples = any(role in {"example", "exercise"} for role in roles)
        return CoursewareTransformResult(
            content=clean,
            key_terms=tuple(term for term, _ in term_counter.most_common(24)),
            teaching_roles=tuple(roles),
            section_count=max(1, len(specs)),
            contains_examples=contains_examples,
            raw_character_count=len(raw),
            clean_character_count=len(clean),
        )


_ROLE_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("definition", re.compile(r"\b(?:definition|define[ds]?|is called|refers to|property|invariant)\b", re.I)),
    ("algorithm", re.compile(r"\b(?:algorithm|pseudocode|procedure|steps?|recurr?ence|iterate|traversal)\b", re.I)),
    ("example", re.compile(r"\b(?:worked\s+example|examples?|case\s+study|sample)\b|例题|示例", re.I)),
    ("exercise", re.compile(r"\b(?:exercises?|practice|quiz|question|class\s+test|exam)\b|练习|习题", re.I)),
    ("complexity", re.compile(r"\b(?:time|space)\s+complexity\b|\b(?:best|average|worst)[ -]case\b|O\([^)]+\)", re.I)),
    ("equation", re.compile(r"(?:^|\s)[A-Za-z][A-Za-z0-9_]*\s*=|[≤≥∑√]|\\(?:frac|sum|sqrt)", re.I)),
)

_PAGE_NUMBER_RE = re.compile(r"^(?:page|slide)?\s*\d+(?:\s*(?:/|of)\s*\d+)?$", re.I)
_COPYRIGHT_RE = re.compile(r"^(?:©|copyright\b|all rights reserved\b)", re.I)


def _normalize_newlines(content: str) -> str:
    return (content or "").replace("\r\n", "\n").replace("\r", "\n")


def _repeated_boilerplate(lines: list[str]) -> set[str]:
    counts = Counter(line for line in lines if line and len(line) <= 160)
    return {
        line
        for line, count in counts.items()
        if count >= 4 and (_COPYRIGHT_RE.search(line) or count >= 8 or len(line) <= 40)
    }


def _is_slide_furniture(line: str) -> bool:
    if _PAGE_NUMBER_RE.fullmatch(line):
        return True
    if "@" in line and re.search(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b", line):
        return True
    return bool(_COPYRIGHT_RE.search(line) and len(line) <= 160)
