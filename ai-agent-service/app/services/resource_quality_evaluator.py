from __future__ import annotations

from app.rag.retriever import RagEvidence
from app.schemas.resource_schema import (
    GeneratedResource,
    QualityDimensionScore,
    ResourceQualityEvaluation,
    SafetyReview,
)


class ResourceQualityEvaluator:
    """Deterministic and explainable quality gate for generated learning resources."""

    version = "resource-quality-v1"
    weights = {
        "evidence_coverage": 0.30,
        "structural_completeness": 0.20,
        "knowledge_consistency": 0.20,
        "difficulty_alignment": 0.15,
        "safety_review": 0.15,
    }
    dimension_threshold = 60.0
    total_threshold = 75.0

    def evaluate(
        self,
        resource: GeneratedResource,
        evidence: list[RagEvidence],
        safety: SafetyReview,
        expected_difficulty: str,
    ) -> ResourceQualityEvaluation:
        dimensions = {
            "evidence_coverage": self._evidence_coverage(resource, evidence),
            "structural_completeness": self._structural_completeness(resource),
            "knowledge_consistency": self._knowledge_consistency(resource, evidence),
            "difficulty_alignment": self._difficulty_alignment(resource, expected_difficulty),
            "safety_review": self._safety_score(safety),
        }
        total = round(
            sum(item.score * item.weight for item in dimensions.values()),
            2,
        )
        dimensions_passed = all(
            item.score >= self.dimension_threshold for item in dimensions.values()
        )
        gate_passed = safety.passed and total >= self.total_threshold and dimensions_passed
        issues = [finding for item in dimensions.values() if not item.passed for finding in item.findings]
        recommendations = self._recommendations(dimensions, safety)
        return ResourceQualityEvaluation(
            evaluator_version=self.version,
            total_score=total,
            grade=_grade(total),
            gate_passed=gate_passed,
            dimensions=dimensions,
            issues=issues[:8],
            recommendations=recommendations[:6],
        )

    def _dimension(self, name: str, score: float, findings: list[str]) -> QualityDimensionScore:
        normalized = round(max(0.0, min(100.0, score)), 2)
        return QualityDimensionScore(
            score=normalized,
            weight=self.weights[name],
            passed=normalized >= self.dimension_threshold,
            findings=findings,
        )

    def _evidence_coverage(
        self, resource: GeneratedResource, evidence: list[RagEvidence]
    ) -> QualityDimensionScore:
        if not evidence:
            return self._dimension("evidence_coverage", 0, ["未提供可核验的 RAG 证据"])
        available = {item.chunk_id: item for item in evidence}
        valid_ids = list(dict.fromkeys(
            chunk_id for chunk_id in resource.evidence_chunk_ids if chunk_id in available
        ))
        target_count = min(3, len(evidence))
        reference_ratio = min(len(valid_ids) / max(target_count, 1), 1.0)
        cited_count = sum(
            1
            for chunk_id in valid_ids
            if f"[{chunk_id}]" in resource.content
            or available[chunk_id].title in resource.content
        )
        citation_ratio = cited_count / max(len(valid_ids), 1)
        evidence_terms = {
            item.knowledge_point.strip()
            for item in evidence
            if item.knowledge_point and item.knowledge_point.strip()
        }
        overlap = any(term in resource.content for term in evidence_terms)
        score = reference_ratio * 60 + citation_ratio * 25 + (15 if overlap else 0)
        findings: list[str] = []
        if reference_ratio < 1:
            findings.append(f"有效证据引用仅 {len(valid_ids)}/{target_count} 条")
        if valid_ids and citation_ratio < 1:
            findings.append("部分 evidence_chunk_ids 未在正文中形成可见引用")
        if not overlap:
            findings.append("正文与检索证据的知识点名称缺少直接对应")
        return self._dimension("evidence_coverage", score, findings)

    def _structural_completeness(self, resource: GeneratedResource) -> QualityDimensionScore:
        content = resource.content
        requirements = {
            "lecture": (("学习目标",), ("讲解", "步骤"), ("例", "边界"), ("证据", "RAG")),
            "reading": (("目标", "导读"), ("定义", "概念"), ("思考", "延伸"), ("证据", "RAG")),
            "mindmap": (("```mermaid",), ("mindmap", "flowchart"), ("关系", "节点"), ("证据", "RAG")),
            "flowchart": (("```mermaid",), ("flowchart",), ("判断", "步骤"), ("证据", "RAG")),
            "quiz": (("问题",), ("答案",), ("解析", "要点"), ("基础",), ("迁移",), ("综合",)),
            "codelab": (("```",), ("运行",), ("输出", "结果"), ("任务", "观察"), ("边界", "测试")),
            "animation_script": (("镜头", "画面"), ("旁白",), ("交互", "提示"), ("证据", "RAG")),
        }.get(resource.resource_type, (("##",), ("证据", "RAG")))
        matched = sum(1 for alternatives in requirements if any(item in content for item in alternatives))
        score = matched / len(requirements) * 90 + (10 if len(content.strip()) >= 120 else 0)
        findings = [] if matched == len(requirements) else [f"类型结构项满足 {matched}/{len(requirements)}"]
        if len(content.strip()) < 120:
            findings.append("正文过短，难以形成完整学习资源")
        return self._dimension("structural_completeness", score, findings)

    def _knowledge_consistency(
        self, resource: GeneratedResource, evidence: list[RagEvidence]
    ) -> QualityDimensionScore:
        requested = [item.strip() for item in resource.knowledge_points if item.strip()]
        evidence_points = {item.knowledge_point.strip() for item in evidence if item.knowledge_point.strip()}
        if not requested:
            return self._dimension("knowledge_consistency", 0, ["资源未声明对应知识点"])
        supported = sum(1 for point in requested if point in evidence_points or point in resource.content)
        mentioned = sum(1 for point in requested if point in resource.content or point in resource.title)
        score = supported / len(requested) * 65 + mentioned / len(requested) * 35
        findings: list[str] = []
        if supported < len(requested):
            findings.append(f"有 {len(requested) - supported} 个知识点缺少证据或正文支持")
        if mentioned < len(requested):
            findings.append(f"有 {len(requested) - mentioned} 个声明知识点未在标题或正文出现")
        return self._dimension("knowledge_consistency", score, findings)

    def _difficulty_alignment(
        self, resource: GeneratedResource, expected_difficulty: str
    ) -> QualityDimensionScore:
        score = 70 if resource.difficulty == expected_difficulty else 20
        normalized = resource.content.lower()
        difficulty_cues = {
            "basic": ("基础", "定义", "分步", "边界"),
            "medium": ("迁移", "推导", "比较", "边界"),
            "advanced": ("综合", "优化", "证明", "复杂度"),
        }.get(expected_difficulty, ())
        cue_count = sum(1 for cue in difficulty_cues if cue.lower() in normalized)
        score += min(cue_count, 2) * 10
        score += 10 if 120 <= len(resource.content.strip()) <= 12000 else 0
        findings: list[str] = []
        if resource.difficulty != expected_difficulty:
            findings.append(f"资源难度 {resource.difficulty} 与目标 {expected_difficulty} 不一致")
        if cue_count == 0:
            findings.append("正文缺少与目标难度匹配的学习活动或表达线索")
        return self._dimension("difficulty_alignment", score, findings)

    def _safety_score(self, safety: SafetyReview) -> QualityDimensionScore:
        score = (80 if safety.passed else 20) + safety.confidence * 20
        findings = [] if safety.passed else list(safety.issues) or ["SafetyAgent 未通过"]
        return self._dimension("safety_review", score, findings)

    def _recommendations(
        self,
        dimensions: dict[str, QualityDimensionScore],
        safety: SafetyReview,
    ) -> list[str]:
        recommendations: list[str] = []
        mapping = {
            "evidence_coverage": "补充正文中的 chunk_id 引用，并使关键结论可回溯到课程证据。",
            "structural_completeness": "补齐该资源类型必需的学习目标、过程、练习或验证结构。",
            "knowledge_consistency": "删去无证据支持的知识点，或补充对应课程材料。",
            "difficulty_alignment": "按目标难度调整示例、迁移任务和边界测试。",
            "safety_review": "先解决 SafetyAgent 指出的问题，再允许资源发布。",
        }
        for name, item in dimensions.items():
            if not item.passed:
                recommendations.append(mapping[name])
        if not safety.passed:
            recommendations.extend(safety.suggestions[:2])
        return list(dict.fromkeys(recommendations))


def _grade(score: float) -> str:
    if score >= 90:
        return "A"
    if score >= 80:
        return "B"
    if score >= 70:
        return "C"
    return "D"
