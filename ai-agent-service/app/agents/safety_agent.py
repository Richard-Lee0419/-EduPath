from app.rag.retriever import RagEvidence
from app.schemas.resource_schema import SafetyReview


class SafetyAgent:
    """Safety review and anti-hallucination agent boundary."""

    _blocked_terms = ("暴力", "作弊", "攻击", "绕过考试")
    _high_risk_claim_terms = ("%", "O(", "复杂度", "命中率", "一定", "Cache", "cache", "指令周期", "公式")

    def review(
        self,
        output_text: str,
        evidence: list[RagEvidence],
        require_evidence: bool = True,
    ) -> SafetyReview:
        issues: list[str] = []
        suggestions: list[str] = []
        if require_evidence and not evidence:
            issues.append("缺少 RAG 证据，无法完成事实一致性检查")
            suggestions.append("当前答案依据不足，应提示用户补充课程证据后再确认。")
        if any(term in output_text for term in self._blocked_terms):
            issues.append("包含不适合学习资源的风险表达")
        if require_evidence and _has_high_risk_claim(output_text, self._high_risk_claim_terms) and not _claim_supported_by_evidence(
            output_text, evidence, self._high_risk_claim_terms
        ):
            issues.append("数值或定义类重点表述缺少证据直接支持")
            suggestions.append("LLM Judge(local): 建议复核复杂度、Cache 公式、命中率、指令周期等关键结论。")
        if evidence and not _has_evidence_overlap(output_text, evidence):
            suggestions.append("建议增加与证据片段更直接对应的解释。")
        if not issues:
            suggestions.append("已引用 RAG 证据，并保留 SafetyAgent 复核结果。")
        return SafetyReview(
            passed=not issues,
            risk_level="low" if not issues else "medium",
            issues=issues,
            suggestions=suggestions,
            confidence=0.92 if not issues else 0.62,
        )


def _has_evidence_overlap(output_text: str, evidence: list[RagEvidence]) -> bool:
    for item in evidence:
        if item.knowledge_point in output_text or item.title in output_text:
            return True
    return False


def _has_high_risk_claim(output_text: str, terms: tuple[str, ...]) -> bool:
    return any(term in output_text for term in terms)


def _claim_supported_by_evidence(
    output_text: str,
    evidence: list[RagEvidence],
    terms: tuple[str, ...],
) -> bool:
    if not evidence:
        return False
    evidence_text = "\n".join(
        f"{item.knowledge_point}\n{item.title}\n{item.content}" for item in evidence
    ).lower()
    present_terms = {term for term in terms if term in output_text}
    aliases = {
        "%": ("%", "percent", "百分比"),
        "O(": ("o(", "complexity", "复杂度"),
        "复杂度": (
            "复杂度",
            "complexity",
            "big-o",
            "big o",
            "o(",
            "硬件成本",
            "比较器成本",
            "comparator cost",
            "replacement logic",
        ),
        "命中率": ("命中率", "hit rate", "hit-rate"),
        "一定": ("一定", "必然", "always", "guarantee"),
        "Cache": ("cache", "高速缓存"),
        "cache": ("cache", "高速缓存"),
        "指令周期": ("指令周期", "instruction cycle"),
        "公式": ("公式", "formula", "equation", "="),
    }
    return all(
        any(alias.lower() in evidence_text for alias in aliases.get(term, (term,)))
        for term in present_terms
    )
