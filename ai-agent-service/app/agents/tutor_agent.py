from app.rag.retriever import RagEvidence
from app.schemas.tutor_schema import TutorAnswerDraft, TutorCitation


class TutorAgent:
    name = "TutorAgent"

    def answer(self, question: str, answer_mode: str, evidence: list[RagEvidence]) -> TutorAnswerDraft:
        mode_prefix = {
            "hint": "先给一个提示，不直接给完整答案：",
            "socratic": "用追问方式推进：",
            "step_by_step": "按步骤解释：",
            "summary": "先给出简洁结论：",
            "code_first": "先从可执行步骤入手：",
        }.get(answer_mode, "按步骤解释：")
        first = evidence[0] if evidence else None
        concept = first.knowledge_point if first else "当前问题"
        answer = (
            f"{mode_prefix}这个问题属于 {concept}。"
            f"先回到定义，再跟踪执行过程，最后用边界样例验证。"
            f"证据要点：{first.content[:86] if first else '当前未检索到课程证据，建议补充知识库文档。'}"
        )
        citations = []
        if first:
            citations.append(
                TutorCitation(
                    answer_fragment=f"这个问题属于 {concept}",
                    evidence_chunk_ids=[first.chunk_id],
                )
            )
        return TutorAnswerDraft(
            answer_markdown=answer,
            steps=["定位问题概念", "检索 RAG 证据", "分步解释", "给出练习建议"],
            citations=citations,
            confidence=0.78 if first else 0.2,
        )
