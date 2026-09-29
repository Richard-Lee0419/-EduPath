from app.agents.mindmap_agent import MindmapAgent
from app.rag.retriever import RagEvidence
from app.services.personalization import PersonalizationContext


def _evidence(topic: str, title: str, content: str) -> RagEvidence:
    return RagEvidence(
        chunk_id="algo-rbtree-001",
        title=title,
        content=content,
        score=0.95,
        source="course-corpus",
        course_id=2,
        knowledge_point_id=21,
        knowledge_point=topic,
    )


def test_red_black_tree_mindmap_contains_colored_structure_and_repair_flow() -> None:
    personalization = PersonalizationContext.from_profile(
        {"cognitive_style": ["visual"], "resource_preference": ["mindmap"]},
        "basic",
    )
    resource = MindmapAgent().generate(
        resource_type="mindmap",
        topic="红黑树",
        difficulty="basic",
        knowledge_points=["红黑树"],
        evidence=[
            _evidence(
                "红黑树",
                "红黑树：颜色约束与黑高平衡",
                "根节点为黑色，红色节点的孩子为黑色，插入修复使用旋转和重着色。",
            )
        ],
        personalization=personalization,
    )

    assert resource.content.count("```mermaid") >= 3
    assert "classDef redNode" in resource.content
    assert "旋转" in resource.content
    assert "[algo-rbtree-001]" in resource.content


def test_quick_sort_flowchart_contains_partition_visual() -> None:
    personalization = PersonalizationContext.from_profile({}, "medium")
    resource = MindmapAgent().generate(
        resource_type="flowchart",
        topic="快速排序",
        difficulty="medium",
        knowledge_points=["快速排序"],
        evidence=[_evidence("快速排序", "快速排序分区", "选择枢轴并递归处理左右区间。")],
        personalization=personalization,
    )

    assert "经典图示：快速排序的一次分区" in resource.content
    assert "选择 pivot" in resource.content
    assert "[algo-rbtree-001]" in resource.content
