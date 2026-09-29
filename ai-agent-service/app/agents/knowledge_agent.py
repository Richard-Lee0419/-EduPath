from app.rag.retriever import RagEvidence, Retriever, retriever


class KnowledgeAgent:
    name = "KnowledgeAgent"

    def __init__(self, rag_retriever: Retriever | None = None) -> None:
        self.retriever = rag_retriever or retriever

    def retrieve(
        self,
        course_id: int,
        query: str,
        top_k: int = 5,
        knowledge_point_ids: list[int] | None = None,
    ) -> list[RagEvidence]:
        return self.retriever.search(
            course_id=course_id,
            query=query,
            top_k=top_k,
            knowledge_point_ids=knowledge_point_ids,
        )

    def topic_names(self, evidence: list[RagEvidence]) -> list[str]:
        names: list[str] = []
        for item in evidence:
            if item.knowledge_point not in names:
                names.append(item.knowledge_point)
        return names or ["核心知识点"]

    def ingest_uploaded_document(
        self,
        *,
        document_id: int,
        course_id: int,
        course_code: str,
        knowledge_point_id: int,
        knowledge_point: str,
        title: str,
        content: str,
        source: str,
    ) -> list[RagEvidence]:
        return self.retriever.ingest_uploaded_document(
            document_id=document_id,
            course_id=course_id,
            course_code=course_code,
            knowledge_point_id=knowledge_point_id,
            knowledge_point=knowledge_point,
            title=title,
            content=content,
            source=source,
        )
