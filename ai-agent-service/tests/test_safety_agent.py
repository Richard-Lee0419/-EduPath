from app.agents.safety_agent import SafetyAgent
from app.rag.retriever import RagEvidence


def test_safety_agent_flags_numeric_and_definition_claims_without_evidence_overlap():
    evidence = [
        RagEvidence(
            chunk_id="cache_1",
            title="Cache 直接映射",
            content="直接映射需要根据索引定位行，再比较标记位判断是否命中。",
            score=0.91,
            source="cache.md",
            course_id=2,
            knowledge_point_id=251,
            knowledge_point="Cache 映射方式",
        )
    ]

    review = SafetyAgent().review("Cache 命中率一定是 100%，时间复杂度 O(1)。", evidence)

    assert review.passed is False
    assert review.risk_level == "medium"
    assert any("数值或定义" in issue for issue in review.issues)
    assert any("LLM Judge" in suggestion for suggestion in review.suggestions)


def test_safety_agent_marks_low_confidence_when_required_evidence_is_missing():
    review = SafetyAgent().review("二叉树遍历应该先看根节点。", [], require_evidence=True)

    assert review.passed is False
    assert review.confidence < 0.7
    assert any("依据不足" in suggestion for suggestion in review.suggestions)


def test_safety_agent_accepts_cache_claims_supported_by_retrieved_evidence():
    evidence = [
        RagEvidence(
            chunk_id="cache_1",
            title="Cache 直接映射、全相联与组相联",
            content="Cache 直接映射硬件简单但冲突缺失明显；全相联允许放入任意行。",
            score=0.91,
            source="cache.md",
            course_id=2,
            knowledge_point_id=251,
            knowledge_point="Cache 映射方式",
        )
    ]

    review = SafetyAgent().review(
        "Cache 直接映射硬件简单，全相联允许放入任意行。[cache_1]",
        evidence,
    )

    assert review.passed is True
    assert review.risk_level == "low"


def test_safety_agent_accepts_complexity_tradeoff_supported_by_hardware_cost_evidence():
    evidence = [
        RagEvidence(
            chunk_id="cache_cost",
            title="全相联与组相联硬件权衡",
            content="全相联需要更多比较器，比较器成本高；组相联在冲突与硬件之间折中。",
            score=0.88,
            source="cache.md",
            course_id=2,
            knowledge_point_id=251,
            knowledge_point="Cache 映射方式",
        )
    ]

    review = SafetyAgent().review("增加相联路数会提高比较与替换逻辑复杂度。", evidence)

    assert review.passed is True
