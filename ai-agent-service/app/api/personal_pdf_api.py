from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from app.rag.document_parser import DocumentParseError, parse_pdf_pages
from app.services.personal_pdf_tutor import (
    MAX_PDF_CHARACTERS,
    MAX_PDF_PAGES,
    PdfAnswer,
    PdfPage,
    answer_personal_pdf,
)


router = APIRouter(prefix="/internal/personal-pdfs", include_in_schema=False)
_MAX_BYTES = 8 * 1024 * 1024


class PdfIndex(BaseModel):
    page_count: int = 0
    pages: list[PdfPage] = Field(default_factory=list)
    error: str | None = None


class PdfQuestion(BaseModel):
    question: str = Field(min_length=2, max_length=500)
    pages: list[PdfPage] = Field(min_length=1, max_length=MAX_PDF_PAGES)


@router.post("/index", response_model=PdfIndex)
async def index_pdf(request: Request) -> PdfIndex:
    if request.headers.get("content-type") != "application/pdf":
        raise HTTPException(status_code=415)
    raw = bytearray()
    async for chunk in request.stream():
        if len(raw) + len(chunk) > _MAX_BYTES:
            raise HTTPException(status_code=413)
        raw.extend(chunk)
    try:
        count, pages = await run_in_threadpool(
            parse_pdf_pages, bytes(raw), max_pages=MAX_PDF_PAGES, max_characters=MAX_PDF_CHARACTERS
        )
    except DocumentParseError as exception:
        return PdfIndex(error=str(exception))
    return PdfIndex(page_count=count, pages=[PdfPage(number=number, text=text) for number, text in pages])


@router.post("/ask", response_model=PdfAnswer)
def ask_pdf(request: PdfQuestion) -> PdfAnswer:
    if sum(len(page.text) for page in request.pages) > MAX_PDF_CHARACTERS:
        raise HTTPException(status_code=413)
    return answer_personal_pdf(request.question, request.pages)
