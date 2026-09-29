from __future__ import annotations

from dataclasses import dataclass

from app.rag.chunker import RagChunk
from app.rag.embeddings import EmbeddingClient, cosine_similarity


@dataclass(frozen=True)
class SearchHit:
    chunk_id: str
    course_id: int
    knowledge_point_id: int
    knowledge_point: str
    title: str
    content: str
    source: str
    score: float
    related_knowledge_point_ids: tuple[int, ...] = ()
    author: str = ""
    source_url: str = ""
    license: str = ""
    license_status: str = "pending"
    document_type: str = "lecture"
    contains_examples: bool = False
    heading_path: tuple[str, ...] = ()


@dataclass(frozen=True)
class VectorRecord:
    chunk: RagChunk
    embedding: dict[str, float]
    dense_embedding: list[float] | None = None


class VectorStore:
    """Vector database adapter boundary."""

    def __init__(self, embedding_client: EmbeddingClient | None = None) -> None:
        self.embedding_client = embedding_client or EmbeddingClient()
        self._records: dict[str, VectorRecord] = {}

    def upsert_chunks(self, chunks: list[RagChunk]) -> None:
        for chunk in chunks:
            indexed_text = _indexed_text(chunk)
            dense_embedding = self.embedding_client.embed_dense(indexed_text) if self.embedding_client.prefers_dense else None
            self._records[chunk.chunk_id] = VectorRecord(
                chunk=chunk,
                embedding=self.embedding_client.embed(indexed_text),
                dense_embedding=dense_embedding,
            )

    def search(
        self,
        course_id: int,
        query: str,
        top_k: int = 5,
        knowledge_point_ids: list[int] | None = None,
    ) -> list[SearchHit]:
        query_embedding = self.embedding_client.embed(query)
        query_dense = self.embedding_client.embed_dense(query) if self._uses_dense_records() else None
        requested_points = set(knowledge_point_ids or [])
        hits: list[SearchHit] = []
        for record in self._records.values():
            chunk = record.chunk
            if chunk.course_id != course_id:
                continue
            if query_dense is not None and record.dense_embedding is not None:
                score = _dense_cosine(query_dense, record.dense_embedding)
            else:
                score = cosine_similarity(query_embedding, record.embedding)
            chunk_points = {chunk.knowledge_point_id, *chunk.related_knowledge_point_ids}
            if requested_points and chunk_points & requested_points:
                score += 0.72
            if chunk.knowledge_point and chunk.knowledge_point in (query or ""):
                score += 0.28
            if set(chunk.anchors) & set(query_embedding):
                score += 0.12
            if not (query or "").strip():
                score += 0.48
            if score <= 0 and requested_points:
                continue
            hits.append(
                SearchHit(
                    chunk_id=chunk.chunk_id,
                    course_id=chunk.course_id,
                    knowledge_point_id=chunk.knowledge_point_id,
                    knowledge_point=chunk.knowledge_point,
                    title=chunk.title,
                    content=chunk.content,
                    source=chunk.source,
                    score=round(min(score, 0.99), 2),
                    related_knowledge_point_ids=chunk.related_knowledge_point_ids,
                    author=chunk.author,
                    source_url=chunk.source_url,
                    license=chunk.license,
                    license_status=chunk.license_status,
                    document_type=chunk.document_type,
                    contains_examples=chunk.contains_examples,
                    heading_path=chunk.heading_path,
                )
            )
        hits.sort(key=lambda item: item.score, reverse=True)
        return hits[: max(1, min(top_k, 10))]

    def _uses_dense_records(self) -> bool:
        return any(record.dense_embedding is not None for record in self._records.values())

    def count(self) -> int:
        return len(self._records)


def _indexed_text(chunk: RagChunk) -> str:
    return " ".join(
        value
        for value in (
            chunk.title,
            chunk.knowledge_point,
            " ".join(str(value) for value in chunk.related_knowledge_point_ids),
            " ".join(chunk.heading_path),
            " ".join(chunk.anchors),
            " ".join(chunk.roles),
            chunk.content,
        )
        if value
    )


def _dense_cosine(left: list[float], right: list[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    return sum(l_value * r_value for l_value, r_value in zip(left, right))
