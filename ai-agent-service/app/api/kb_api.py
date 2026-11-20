from base64 import b64decode
from binascii import Error as Base64Error

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.agents.orchestrator_agent import orchestrator
from app.core.config import settings
from app.rag.document_parser import DocumentParseError, DocumentTooLargeError, parse_document_bytes, parse_pdf_pages

router = APIRouter(prefix="/kb", tags=["knowledge-base"])


class KnowledgeSearchRequest(BaseModel):
    course_id: int = 1
    query: str
    top_k: int = Field(default=5, ge=1, le=10)
    local_only: bool = False


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
    document_id: int | None = None
    page: int | None = None


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
    course_code: str | None = None
    require_pdf_pages: bool = False


class KnowledgeIngestResponse(BaseModel):
    task_id: str
    task: dict[str, object]
    document_id: int
    parse_status: str
    index_status: str
    chunks: list[KnowledgeSearchResult]
    safety: dict[str, object]
    safety_status: str
    index_error: str = ""


class KnowledgeVectorDeleteResponse(BaseModel):
    document_id: int
    course_id: int
    deleted_chunks: int


@router.post("/search", response_model=KnowledgeSearchResponse)
def search(request: KnowledgeSearchRequest) -> KnowledgeSearchResponse:
    query = request.query.strip() or "核心知识点"
    evidence = orchestrator.search_knowledge(
        course_id=request.course_id,
        query=query,
        top_k=request.top_k,
        require_relevance=request.local_only,
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
            document_id=item.document_id,
            page=item.page,
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
    text, pages = _decode_document_text(request)
    if not text.strip():
        raise HTTPException(status_code=422, detail="文档未解析到有效文本，无法写入知识库索引")
    return KnowledgeIngestResponse(**orchestrator.ingest_knowledge_document(request, text, pages=pages))


@router.delete("/documents/{document_id}/vectors", response_model=KnowledgeVectorDeleteResponse)
def delete_document_vectors(document_id: int, course_id: int) -> KnowledgeVectorDeleteResponse:
    """Internal Java-to-AI call; delete zero is an idempotent success."""

    try:
        deleted = orchestrator.delete_knowledge_document_vectors(course_id=course_id, document_id=document_id)
    except Exception as exception:
        raise HTTPException(status_code=503, detail="知识库向量撤回失败，请稍后重试") from exception
    return KnowledgeVectorDeleteResponse(document_id=document_id, course_id=course_id, deleted_chunks=deleted)


def _decode_document_text(request: KnowledgeIngestRequest) -> tuple[str, list[tuple[int, str]] | None]:
    try:
        raw = b64decode(request.content_base64.encode("ascii"), validate=True)
    except (Base64Error, UnicodeEncodeError) as exception:
        raise HTTPException(status_code=400, detail="content_base64 不是合法 base64 内容") from exception
    try:
        if len(raw) > settings.max_ingest_bytes:
            raise DocumentTooLargeError(len(raw), settings.max_ingest_bytes)
        if request.require_pdf_pages:
            if not request.filename.lower().endswith(".pdf"):
                raise HTTPException(status_code=422, detail="班级课程需上传 PDF 文档")
            _, pages = parse_pdf_pages(raw, max_pages=100, max_characters=500_000)
            return "\n\n".join(text for _, text in pages), pages
        text = parse_document_bytes(
            raw,
            filename=request.filename,
            content_type=request.content_type,
        )
        return text, None
    except DocumentTooLargeError as exception:
        raise HTTPException(status_code=413, detail=str(exception)) from exception
    except DocumentParseError as exception:
        details = {
            "encrypted": "请先解除 PDF 加密，再上传可读取的文件",
            "page_limit": "PDF 最多支持 100 页，请拆分后上传",
            "text_limit": "PDF 文本量超过处理上限，请拆分后上传",
            "no_text": "PDF 没有可提取的文本，请上传文本版或识别后的 PDF",
            "invalid_pdf": "PDF 无法解析，请换一份可正常打开的 PDF",
        }
        raise HTTPException(status_code=422, detail=details.get(str(exception), str(exception))) from exception
