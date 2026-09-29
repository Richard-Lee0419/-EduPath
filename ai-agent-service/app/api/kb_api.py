from base64 import b64decode
from binascii import Error as Base64Error

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.agents.orchestrator_agent import orchestrator
from app.core.config import settings
from app.rag.document_parser import DocumentParseError, DocumentTooLargeError, parse_document_bytes

router = APIRouter(prefix="/kb", tags=["knowledge-base"])


class KnowledgeSearchRequest(BaseModel):
    course_id: int = 1
    query: str
    top_k: int = Field(default=5, ge=1, le=10)


class KnowledgeSearchResult(BaseModel):
    chunk_id: str
    course_id: int
    knowledge_point_id: int
    knowledge_point: str
    related_knowledge_point_ids: list[int]
    title: str
    content: str
    score: float
    source: str
    author: str = ""
    source_url: str = ""
    license: str = ""
    license_status: str = "pending"
    document_type: str = "lecture"
    contains_examples: bool = False
    heading_path: list[str] = Field(default_factory=list)


class KnowledgeSearchResponse(BaseModel):
    course_id: int
    query: str
    top_k: int
    corpus: dict[str, int | str]
    results: list[KnowledgeSearchResult]


class KnowledgeIngestRequest(BaseModel):
    document_id: int
    course_id: int = 1
    filename: str
    content_type: str | None = None
    content_base64: str
    knowledge_point_id: int | None = None
    knowledge_point: str | None = None
    source: str | None = None


class KnowledgeIngestResponse(BaseModel):
    task_id: str
    task: dict[str, object]
    document_id: int
    parse_status: str
    index_status: str
    chunks: list[KnowledgeSearchResult]
    safety: dict[str, object]


@router.post("/search", response_model=KnowledgeSearchResponse)
def search(request: KnowledgeSearchRequest) -> KnowledgeSearchResponse:
    query = request.query.strip() or "核心知识点"
    evidence = orchestrator.search_knowledge(
        course_id=request.course_id,
        query=query,
        top_k=request.top_k,
    )
    results = [
        KnowledgeSearchResult(
            chunk_id=item.chunk_id,
            course_id=item.course_id,
            knowledge_point_id=item.knowledge_point_id,
            knowledge_point=item.knowledge_point,
            related_knowledge_point_ids=list(item.related_knowledge_point_ids),
            title=item.title,
            content=item.content,
            score=item.score,
            source=item.source,
            author=item.author,
            source_url=item.source_url,
            license=item.license,
            license_status=item.license_status,
            document_type=item.document_type,
            contains_examples=item.contains_examples,
            heading_path=list(item.heading_path),
        )
        for item in evidence
    ]
    return KnowledgeSearchResponse(
        course_id=request.course_id,
        query=query,
        top_k=request.top_k,
        corpus=orchestrator.knowledge_agent.retriever.corpus_summary(),
        results=results,
    )


@router.post("/ingest", response_model=KnowledgeIngestResponse)
def ingest(request: KnowledgeIngestRequest) -> KnowledgeIngestResponse:
    text = _decode_document_text(request.content_base64, request.content_type, request.filename)
    if not text.strip():
        raise HTTPException(status_code=422, detail="文档未解析到有效文本，无法写入知识库索引")
    return KnowledgeIngestResponse(**orchestrator.ingest_knowledge_document(request, text))


def _decode_document_text(content_base64: str, content_type: str | None, filename: str) -> str:
    try:
        raw = b64decode(content_base64.encode("ascii"), validate=True)
    except (Base64Error, UnicodeEncodeError) as exception:
        raise HTTPException(status_code=400, detail="content_base64 不是合法 base64 内容") from exception
    try:
        return parse_document_bytes(
            raw,
            filename=filename,
            content_type=content_type,
            max_bytes=settings.max_ingest_bytes,
        )
    except DocumentTooLargeError as exception:
        raise HTTPException(status_code=413, detail=str(exception)) from exception
    except DocumentParseError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
