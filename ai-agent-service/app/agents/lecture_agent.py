from app.rag.retriever import RagEvidence
from app.schemas.resource_schema import GeneratedResource
from app.services.personalization import PersonalizationContext


class LectureAgent:
    name = "LectureAgent"

    def generate(
        self,
        resource_type: str,
        topic: str,
        difficulty: str,
        knowledge_points: list[str],
        evidence: list[RagEvidence],
        personalization: PersonalizationContext,
    ) -> GeneratedResource:
        is_reading = resource_type == "reading"
        title = f"{topic}拓展阅读" if is_reading else f"{topic}个性化讲义"
        summary = (
            f"围绕 {topic} 的教材证据、例题线索和延伸阅读建议。"
            if is_reading
            else personalization.reason_for(resource_type, topic)
        )
        evidence_lines = "\n".join(
            f"- [{item.title}]({item.source})：{item.content[:72]}..." for item in evidence[:3]
        )
        content = (
            f"## {title}\n\n"
            f"### 学习目标\n"
            f"- 用自己的话解释 {topic} 的核心定义。\n"
            f"- 能把关键步骤迁移到一道新题或一个小实验。\n\n"
            f"### 分步讲解\n"
            f"> 个性化安排：{personalization.learning_instruction()}。\n\n"
            f"1. 先定位概念边界：{_first_sentence(evidence)}\n"
            f"2. 再拆解执行过程，标出输入、状态变化和结束条件。\n"
            f"3. 最后用一道反例或边界样例检查理解是否完整。\n\n"
            f"### RAG 证据\n{evidence_lines}\n\n"
            f"### 难度适配\n当前难度为 `{difficulty}`，优先保留必要定义，再逐步增加迁移题。"
        )
        return GeneratedResource(
            title=title,
            resource_type=resource_type,
            content_format="markdown",
            content=content,
            summary=summary,
            difficulty=difficulty,
            knowledge_points=knowledge_points,
            personalized_reason=personalization.reason_for(resource_type, topic),
            estimated_minutes=personalization.estimated_minutes,
            profile_fingerprint=personalization.fingerprint,
        )


def _first_sentence(evidence: list[RagEvidence]) -> str:
    if not evidence:
        return "围绕课程核心知识点建立定义、过程和例题之间的联系。"
    return evidence[0].content.split("。")[0] + "。"
