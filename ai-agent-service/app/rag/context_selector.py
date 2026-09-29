from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.rag.embeddings import tokenize


@dataclass(frozen=True)
class ContextCandidate:
    chunk_id: str
    title: str
    content: str
    source: str
    relevance_score: float
    knowledge_point_id: int
    anchors: tuple[str, ...] = ()
    heading_path: tuple[str, ...] = ()
    structure_types: tuple[str, ...] = ()


@dataclass(frozen=True)
class ContextSelection:
    selected_ids: tuple[str, ...]
    mmr_scores: dict[str, float]
    context_roles: dict[str, str]
    query_intent: str
    mmr_lambda: float
    candidate_count: int
    selected_count: int
    redundancy_before: float
    diversity_after: float
    context_budget_chars: int
    selected_context_chars: int


class AdaptiveMmrSelector:
    """Select a compact, non-redundant evidence context with an adaptive MMR policy.

    The relevance/diversity trade-off changes with query intent, candidate
    redundancy, requested knowledge points, learner weak points and difficulty.
    This keeps focused questions precise while giving comparative or exploratory
    questions broader evidence coverage.
    """

    def select(
        self,
        candidates: list[ContextCandidate],
        *,
        query: str,
        top_k: int,
        knowledge_point_ids: list[int] | None = None,
        student_profile: dict[str, Any] | None = None,
        difficulty: str | None = None,
    ) -> ContextSelection:
        if not candidates or top_k <= 0:
            return ContextSelection((), {}, {}, "focused", 0.72, len(candidates), 0, 0.0, 1.0, 0, 0)

        profile = student_profile if isinstance(student_profile, dict) else {}
        intent = _query_intent(query)
        redundancy = _average_pairwise_similarity(candidates[: min(8, len(candidates))])
        mmr_lambda = _adaptive_lambda(
            intent=intent,
            redundancy=redundancy,
            has_point_filter=bool(knowledge_point_ids),
            weak_point_count=len(profile.get("weak_points") or []),
            difficulty=difficulty or str(profile.get("effective_difficulty") or ""),
        )
        context_budget = _context_budget(intent, top_k, difficulty, profile)

        remaining = list(candidates)
        selected: list[ContextCandidate] = []
        scores: dict[str, float] = {}
        roles: dict[str, str] = {}
        selected_chars = 0
        while remaining and len(selected) < top_k:
            ranked: list[tuple[float, float, ContextCandidate, str]] = []
            for candidate in remaining:
                max_similarity = max((_candidate_similarity(candidate, item) for item in selected), default=0.0)
                structure_bonus = _structure_novelty(candidate, selected)
                mmr_score = mmr_lambda * candidate.relevance_score - (1.0 - mmr_lambda) * max_similarity
                mmr_score += structure_bonus
                role = _context_role(selected, max_similarity, structure_bonus)
                ranked.append((mmr_score, candidate.relevance_score, candidate, role))
            ranked.sort(key=lambda item: (item[0], item[1]), reverse=True)

            chosen: tuple[float, float, ContextCandidate, str] | None = None
            for item in ranked:
                candidate_chars = len(item[2].content)
                if not selected:
                    # Preserve an indivisible code/table/formula chunk even when it
                    # is larger than the initial budget, and expose the escalation.
                    context_budget = max(context_budget, candidate_chars)
                    chosen = item
                    break
                if selected_chars + candidate_chars <= context_budget:
                    chosen = item
                    break
            if chosen is None:
                break

            mmr_score, _, candidate, role = chosen
            selected.append(candidate)
            remaining = [item for item in remaining if item.chunk_id != candidate.chunk_id]
            scores[candidate.chunk_id] = round(max(0.0, min(0.99, mmr_score)), 4)
            roles[candidate.chunk_id] = role
            selected_chars += len(candidate.content)

        diversity = 1.0 - _average_pairwise_similarity(selected) if len(selected) > 1 else 1.0
        return ContextSelection(
            selected_ids=tuple(item.chunk_id for item in selected),
            mmr_scores=scores,
            context_roles=roles,
            query_intent=intent,
            mmr_lambda=round(mmr_lambda, 4),
            candidate_count=len(candidates),
            selected_count=len(selected),
            redundancy_before=round(redundancy, 4),
            diversity_after=round(max(0.0, min(1.0, diversity)), 4),
            context_budget_chars=context_budget,
            selected_context_chars=selected_chars,
        )


def _query_intent(query: str) -> str:
    normalized = (query or "").lower()
    if any(term in normalized for term in ("比较", "区别", "对比", "异同", "versus", " vs ")):
        return "comparative"
    if any(term in normalized for term in ("如何", "怎么", "步骤", "实现", "代码", "推导", "过程")):
        return "procedural"
    if any(term in normalized for term in ("综合", "全景", "体系", "有哪些", "相关知识", "总结")):
        return "exploratory"
    return "focused"


def _adaptive_lambda(
    *,
    intent: str,
    redundancy: float,
    has_point_filter: bool,
    weak_point_count: int,
    difficulty: str,
) -> float:
    value = {
        "focused": 0.78,
        "procedural": 0.7,
        "comparative": 0.6,
        "exploratory": 0.56,
    }[intent]
    if has_point_filter:
        value += 0.05
    if redundancy >= 0.62:
        value -= 0.09
    elif redundancy >= 0.45:
        value -= 0.05
    if weak_point_count >= 3:
        value -= 0.03
    if difficulty == "basic":
        value += 0.03
    elif difficulty == "advanced":
        value -= 0.03
    return max(0.5, min(0.86, value))


def _context_budget(intent: str, top_k: int, difficulty: str | None, profile: dict[str, Any]) -> int:
    per_item = {
        "focused": 620,
        "procedural": 760,
        "comparative": 820,
        "exploratory": 880,
    }[intent]
    budget = max(1800, min(4800, top_k * per_item))
    effective_difficulty = difficulty or str(profile.get("effective_difficulty") or "")
    if effective_difficulty == "basic":
        budget = min(budget, 2600)
    elif effective_difficulty == "advanced":
        budget = min(4800, budget + 600)
    pace = str(profile.get("learning_pace") or "")
    if "20" in pace or "慢" in pace:
        budget = min(budget, 2200)
    return budget


def _context_role(selected: list[ContextCandidate], max_similarity: float, structure_bonus: float) -> str:
    if not selected:
        return "primary_relevance"
    if structure_bonus >= 0.035:
        return "structural_complement"
    if max_similarity < 0.32:
        return "diversity_expansion"
    return "supporting_evidence"


def _structure_novelty(candidate: ContextCandidate, selected: list[ContextCandidate]) -> float:
    if not selected:
        return 0.0
    seen_types = {item for selected_item in selected for item in selected_item.structure_types}
    new_types = set(candidate.structure_types) - seen_types
    seen_sections = {item.heading_path for item in selected}
    section_is_new = bool(candidate.heading_path and candidate.heading_path not in seen_sections)
    return min(0.06, (0.035 if new_types else 0.0) + (0.02 if section_is_new else 0.0))


def _average_pairwise_similarity(candidates: list[ContextCandidate]) -> float:
    if len(candidates) < 2:
        return 0.0
    values = [
        _candidate_similarity(left, right)
        for index, left in enumerate(candidates)
        for right in candidates[index + 1 :]
    ]
    return sum(values) / len(values) if values else 0.0


def _candidate_similarity(left: ContextCandidate, right: ContextCandidate) -> float:
    left_tokens = set(tokenize(f"{left.title} {left.content}"))
    right_tokens = set(tokenize(f"{right.title} {right.content}"))
    lexical = _jaccard(left_tokens, right_tokens)
    anchors = _jaccard(set(left.anchors), set(right.anchors))
    same_section = 1.0 if left.heading_path and left.heading_path == right.heading_path else 0.0
    same_source = 1.0 if left.source and left.source == right.source else 0.0
    return min(1.0, 0.66 * lexical + 0.17 * anchors + 0.1 * same_section + 0.07 * same_source)


def _jaccard(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)
