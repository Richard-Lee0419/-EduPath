from __future__ import annotations

from dataclasses import dataclass, replace

from app.core.config import settings
from app.rag.chunker import Chunker, RagChunk
from app.rag.chroma_vector_store import ChromaVectorStore
from app.rag.document_loader import DocumentLoader, SourceDocument
from app.rag.lexical_retrieval import has_query_overlap
from app.rag.vector_store import SearchHit, VectorStore


@dataclass(frozen=True)
class RagEvidence:
    chunk_id: str
    title: str
    content: str
    score: float
    source: str
    course_id: int
    knowledge_point_id: int
    knowledge_point: str
    related_knowledge_point_ids: tuple[int, ...] = ()
    author: str = ""
    source_url: str = ""
    license: str = ""
    license_status: str = "pending"
    document_type: str = "lecture"
    contains_examples: bool = False
    heading_path: tuple[str, ...] = ()
    document_id: int | None = None
    page: int | None = None


class Retriever:
    """RAG retrieval pipeline boundary."""

    def __init__(
        self,
        loader: DocumentLoader | None = None,
        chunker: Chunker | None = None,
        vector_store: VectorStore | None = None,
    ) -> None:
        self.loader = loader or DocumentLoader(seed_corpus_path=settings.seed_corpus_path)
        self.chunker = chunker or Chunker()
        self.vector_store = vector_store or _build_vector_store()
        self.bootstrap_stats: dict[str, int | str] = {
            "documents": 0,
            "external_documents": 0,
            "chunks": 0,
            "mode": "builtin_demo",
        }
        self._bootstrap()

    def search(
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
        limit = max(1, min(top_k, 10))
        point_filter = set(knowledge_point_ids or []) if require_knowledge_points else set()
        candidate_points = knowledge_point_ids
        if point_filter:
            candidate_points = sorted(point_filter | {0})
        hits = self.vector_store.search(
            course_id=course_id,
            query=query,
            top_k=10 if require_relevance or point_filter else limit,
            knowledge_point_ids=candidate_points,
            require_knowledge_points=bool(point_filter),
        )
        if require_relevance:
            hits = [hit for hit in hits if has_query_overlap(query, hit.content)]
        if point_filter:
            topic_query = knowledge_point_query.strip() or query
            hits = [
                hit for hit in hits
                if point_filter.intersection((hit.knowledge_point_id, *hit.related_knowledge_point_ids))
                or (hit.knowledge_point_id == 0 and hit.page is not None and has_query_overlap(topic_query, hit.content))
            ]
        return [self._to_evidence(hit) for hit in hits[:limit]]

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
        """Index already-approved input; the upload API reviews prepared chunks first."""

        chunks = self.prepare_uploaded_document(
            document_id=document_id,
            course_id=course_id,
            course_code=course_code,
            knowledge_point_id=knowledge_point_id,
            knowledge_point=knowledge_point,
            title=title,
            content=content,
            source=source,
        )
        self.replace_uploaded_document_chunks(course_id=course_id, document_id=document_id, chunks=chunks)
        return self.uploaded_document_evidence(chunks)

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
        """Parse/chunk without touching the vector store."""

        document = SourceDocument(
            course_id=course_id,
            course_code=course_code,
            knowledge_point_id=knowledge_point_id,
            knowledge_point=knowledge_point,
            title=title,
            content=content,
            source=source,
        )
        if pages is None:
            return self.chunker.chunk_uploaded_document(document, document_id)
        return [
            chunk
            for page, page_content in pages
            for chunk in self.chunker.chunk_uploaded_document(
                replace(document, content=page_content), document_id, page=page
            )
        ]

    def replace_uploaded_document_chunks(self, *, course_id: int, document_id: int, chunks: list[RagChunk]) -> int:
        return self.vector_store.replace_document_chunks(course_id, document_id, chunks)

    def delete_uploaded_document(self, *, course_id: int, document_id: int) -> int:
        return self.vector_store.delete_document_chunks(course_id, document_id)

    def uploaded_document_evidence(self, chunks: list[RagChunk]) -> list[RagEvidence]:
        return [
            RagEvidence(
                chunk_id=chunk.chunk_id,
                title=chunk.title,
                content=chunk.content,
                score=1.0,
                source=chunk.source,
                course_id=chunk.course_id,
                knowledge_point_id=chunk.knowledge_point_id,
                knowledge_point=chunk.knowledge_point,
                related_knowledge_point_ids=chunk.related_knowledge_point_ids,
                author=chunk.author,
                source_url=chunk.source_url,
                license=chunk.license,
                license_status=chunk.license_status,
                document_type=chunk.document_type,
                contains_examples=chunk.contains_examples,
                heading_path=chunk.heading_path,
                document_id=chunk.document_id,
                page=chunk.page,
            )
            for chunk in chunks
        ]

    def _bootstrap(self) -> None:
        external_documents = self.loader.load_seed_documents()
        documents = self.loader.load_builtin_documents()
        chunks = self.chunker.chunk_documents(documents)
        self.vector_store.upsert_chunks(chunks)
        self.bootstrap_stats = {
            "documents": len(documents),
            "external_documents": len(external_documents),
            "chunks": len(chunks),
            "mode": "course_corpus" if external_documents else "builtin_demo",
        }

    def corpus_summary(self) -> dict[str, int | str]:
        return {
            **self.bootstrap_stats,
            "vector_store": settings.vector_store_provider,
            "embedding": settings.embedding_model,
        }

    def _to_evidence(self, hit: SearchHit) -> RagEvidence:
        return RagEvidence(
            chunk_id=hit.chunk_id,
            title=hit.title,
            content=hit.content,
            score=hit.score,
            source=hit.source,
            course_id=hit.course_id,
            knowledge_point_id=hit.knowledge_point_id,
            knowledge_point=hit.knowledge_point,
            related_knowledge_point_ids=hit.related_knowledge_point_ids,
            author=hit.author,
            source_url=hit.source_url,
            license=hit.license,
            license_status=hit.license_status,
            document_type=hit.document_type,
            contains_examples=hit.contains_examples,
            heading_path=hit.heading_path,
            document_id=hit.document_id,
            page=hit.page,
        )

def _build_vector_store() -> VectorStore | ChromaVectorStore:
    if settings.vector_store_provider == "chroma":
        return ChromaVectorStore(
            chroma_url=settings.chroma_url,
            collection_name=settings.chroma_collection,
        )
    return VectorStore()


retriever = Retriever()
