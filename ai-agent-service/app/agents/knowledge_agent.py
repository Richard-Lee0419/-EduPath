from app.rag.chunker import RagChunk
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
        *,
        require_relevance: bool = False,
        require_knowledge_points: bool = False,
        knowledge_point_query: str = "",
    ) -> list[RagEvidence]:
        return self.retriever.search(
            course_id=course_id,
            query=query,
            top_k=top_k,
            knowledge_point_ids=knowledge_point_ids,
            require_relevance=require_relevance,
            require_knowledge_points=require_knowledge_points,
            knowledge_point_query=knowledge_point_query,
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

    def prepare_uploaded_document(
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
        pages: list[tuple[int, str]] | None = None,
    ) -> list[RagChunk]:
        return self.retriever.prepare_uploaded_document(
            document_id=document_id, course_id=course_id, course_code=course_code,
            knowledge_point_id=knowledge_point_id, knowledge_point=knowledge_point,
            title=title, content=content, source=source, pages=pages,
        )

    def uploaded_chunk_evidence(self, chunks: list[RagChunk]) -> list[RagEvidence]:
        return self.retriever.uploaded_document_evidence(chunks)

    def replace_uploaded_chunks(self, *, course_id: int, document_id: int, chunks: list[RagChunk]) -> int:
        return self.retriever.replace_uploaded_document_chunks(
            course_id=course_id, document_id=document_id, chunks=chunks,
        )

    def delete_uploaded_chunks(self, *, course_id: int, document_id: int) -> int:
        return self.retriever.delete_uploaded_document(course_id=course_id, document_id=document_id)
