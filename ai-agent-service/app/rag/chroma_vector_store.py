from __future__ import annotations

from typing import Any
from urllib.parse import urlparse

from app.rag.chunker import RagChunk
from app.rag.embeddings import EmbeddingClient
from app.rag.vector_store import SearchHit, _indexed_text


class ChromaVectorStore:
    """Chroma HTTP adapter for persistent vector retrieval."""

    def __init__(
        self,
        embedding_client: EmbeddingClient | None = None,
        chroma_url: str = "http://localhost:8001",
        collection_name: str = "edupath_course_chunks",
        batch_size: int = 64,
    ) -> None:
        self.embedding_client = embedding_client or EmbeddingClient()
        self.chroma_url = chroma_url
        self.collection_name = collection_name
        self.batch_size = max(1, batch_size)
        self._collection: Any | None = None

    def upsert_chunks(self, chunks: list[RagChunk]) -> None:
        if not chunks:
            return
        collection = self._get_collection()
        for start in range(0, len(chunks), self.batch_size):
            batch = chunks[start : start + self.batch_size]
            collection.upsert(
                ids=[chunk.chunk_id for chunk in batch],
                documents=[chunk.content for chunk in batch],
                embeddings=[self.embedding_client.embed_dense(_indexed_text(chunk)) for chunk in batch],
                metadatas=[_chunk_metadata(chunk) for chunk in batch],
            )

    def search(
        self,
        course_id: int,
        query: str,
        top_k: int = 5,
        knowledge_point_ids: list[int] | None = None,
    ) -> list[SearchHit]:
        collection = self._get_collection()
        query_embedding = self.embedding_client.embed_dense(query)
        result = collection.query(
            query_embeddings=[query_embedding],
            n_results=max(1, min(top_k * 3, 30)),
            where={"course_id": course_id},
            include=["documents", "metadatas", "distances"],
        )
        requested_points = set(knowledge_point_ids or [])
        hits = self._to_hits(result, requested_points)
        hits.sort(key=lambda item: item.score, reverse=True)
        return hits[: max(1, min(top_k, 10))]

    def _get_collection(self) -> Any:
        if self._collection is not None:
            return self._collection
        try:
            import chromadb
        except ImportError as exception:
            raise RuntimeError(
                "VECTOR_STORE_PROVIDER=chroma requires installing the vector extra: pip install -e '.[vector]'"
            ) from exception

        parsed = urlparse(self.chroma_url)
        host = parsed.hostname or "localhost"
        port = parsed.port or (443 if parsed.scheme == "https" else 8000)
        ssl = parsed.scheme == "https"
        client = chromadb.HttpClient(host=host, port=port, ssl=ssl)
        self._collection = client.get_or_create_collection(
            name=self.collection_name,
            metadata={"hnsw:space": "cosine"},
        )
        return self._collection

    def _to_hits(self, result: dict[str, Any], requested_points: set[int]) -> list[SearchHit]:
        ids = _first(result.get("ids"))
        documents = _first(result.get("documents"))
        metadatas = _first(result.get("metadatas"))
        distances = _first(result.get("distances"))
        hits: list[SearchHit] = []
        for index, chunk_id in enumerate(ids):
            metadata = metadatas[index] or {}
            knowledge_point_id = int(metadata.get("knowledge_point_id") or 0)
            related_points = tuple(
                int(value)
                for value in str(metadata.get("related_knowledge_point_ids") or "").split("|")
                if value
            )
            if requested_points and not ({knowledge_point_id, *related_points} & requested_points):
                continue
            distance = float(distances[index] if index < len(distances) else 1.0)
            base_score = max(0.0, min(0.99, 1.0 - distance))
            if requested_points and ({knowledge_point_id, *related_points} & requested_points):
                base_score = min(0.99, base_score + 0.36)
            hits.append(
                SearchHit(
                    chunk_id=str(chunk_id),
                    course_id=int(metadata.get("course_id") or 0),
                    knowledge_point_id=knowledge_point_id,
                    knowledge_point=str(metadata.get("knowledge_point") or ""),
                    title=str(metadata.get("title") or ""),
                    content=str(documents[index] if index < len(documents) else ""),
                    source=str(metadata.get("source") or ""),
                    score=round(base_score, 2),
                    related_knowledge_point_ids=related_points,
                    author=str(metadata.get("author") or ""),
                    source_url=str(metadata.get("source_url") or ""),
                    license=str(metadata.get("license") or ""),
                    license_status=str(metadata.get("license_status") or "pending"),
                    document_type=str(metadata.get("document_type") or "lecture"),
                    contains_examples=str(metadata.get("contains_examples") or "0") in {"1", "true", "True"},
                    heading_path=tuple(
                        value for value in str(metadata.get("heading_path") or "").split("|") if value
                    ),
                )
            )
        return hits


def _first(value: Any) -> list[Any]:
    if isinstance(value, list) and value:
        first = value[0]
        return first if isinstance(first, list) else value
    return []


def _chunk_metadata(chunk: RagChunk) -> dict[str, str | int]:
    return {
        "course_id": chunk.course_id,
        "course_code": chunk.course_code,
        "knowledge_point_id": chunk.knowledge_point_id,
        "knowledge_point": chunk.knowledge_point,
        "title": chunk.title,
        "source": chunk.source,
        "anchors": "|".join(chunk.anchors),
        "roles": "|".join(chunk.roles),
        "heading_path": "|".join(chunk.heading_path),
        "semantic_signature": chunk.semantic_signature,
        "prev_chunk_id": chunk.prev_chunk_id or "",
        "next_chunk_id": chunk.next_chunk_id or "",
        "license": chunk.license,
        "author": chunk.author,
        "source_url": chunk.source_url,
        "content_hash": chunk.content_hash,
        "license_status": chunk.license_status,
        "related_knowledge_point_ids": "|".join(str(value) for value in chunk.related_knowledge_point_ids),
        "document_type": chunk.document_type,
        "language": chunk.language,
        "contains_examples": int(chunk.contains_examples),
    }
