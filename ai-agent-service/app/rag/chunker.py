from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha1, sha256

from app.rag.document_loader import SourceDocument
from app.rag.semantic_chunker import SemanticChunker


@dataclass(frozen=True)
class RagChunk:
    chunk_id: str
    course_id: int
    course_code: str
    knowledge_point_id: int
    knowledge_point: str
    title: str
    content: str
    source: str
    anchors: tuple[str, ...] = ()
    roles: tuple[str, ...] = ()
    heading_path: tuple[str, ...] = ()
    semantic_signature: str = ""
    prev_chunk_id: str | None = None
    next_chunk_id: str | None = None
    license: str = ""
    author: str = ""
    source_url: str = ""
    content_hash: str = ""
    license_status: str = "pending"
    related_knowledge_point_ids: tuple[int, ...] = ()
    document_type: str = "lecture"
    language: str = ""
    contains_examples: bool = False


class Chunker:
    """Chunking strategy boundary for course documents."""

    def __init__(self, semantic_chunker: SemanticChunker | None = None) -> None:
        self.semantic_chunker = semantic_chunker or SemanticChunker()

    def chunk_documents(self, documents: list[SourceDocument]) -> list[RagChunk]:
        chunks: list[RagChunk] = []
        for document in documents:
            document_key = _document_key(document)
            chunks.extend(
                self._chunk_document(
                    document,
                    id_prefix=f"ai_chunk_{document.course_id}_{document.knowledge_point_id}_{document_key}",
                )
            )
        return chunks

    def chunk_uploaded_document(self, document: SourceDocument, document_id: int) -> list[RagChunk]:
        return self._chunk_document(document, id_prefix=f"kb_{document_id}")

    def _chunk_document(self, document: SourceDocument, id_prefix: str) -> list[RagChunk]:
        specs = self.semantic_chunker.split(document.title, document.content)
        chunk_ids = [f"{id_prefix}_{index}" for index in range(1, len(specs) + 1)]
        chunks: list[RagChunk] = []
        for index, spec in enumerate(specs):
            title = document.title
            if len(specs) > 1:
                suffix = f" · {spec.title_suffix}" if spec.title_suffix else ""
                title = f"{document.title}{suffix} #{index + 1}"
            chunks.append(
                RagChunk(
                    chunk_id=chunk_ids[index],
                    course_id=document.course_id,
                    course_code=document.course_code,
                    knowledge_point_id=document.knowledge_point_id,
                    knowledge_point=document.knowledge_point,
                    title=title,
                    content=spec.content,
                    source=document.source,
                    anchors=spec.anchors,
                    roles=spec.roles,
                    heading_path=spec.heading_path,
                    semantic_signature=spec.semantic_signature,
                    prev_chunk_id=chunk_ids[index - 1] if index > 0 else None,
                    next_chunk_id=chunk_ids[index + 1] if index + 1 < len(chunk_ids) else None,
                    license=document.license,
                    author=document.author,
                    source_url=document.source_url,
                    content_hash=document.content_hash or _content_hash(document.content),
                    license_status=document.license_status,
                    related_knowledge_point_ids=document.related_knowledge_point_ids,
                    document_type=document.document_type,
                    language=document.language,
                    contains_examples=document.contains_examples or "example" in spec.roles or "exercise" in spec.roles,
                )
            )
        return chunks


def _document_key(document: SourceDocument) -> str:
    content_hash = document.content_hash or _content_hash(document.content)
    identity = f"{document.source}|{document.title}|{content_hash}"
    return sha1(identity.encode("utf-8")).hexdigest()[:10]


def _content_hash(content: str) -> str:
    normalized = " ".join(content.split())
    return sha256(normalized.encode("utf-8")).hexdigest()
