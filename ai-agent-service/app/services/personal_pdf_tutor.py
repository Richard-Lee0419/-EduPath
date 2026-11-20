from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel, Field

from app.agents.safety_agent import SafetyAgent
from app.rag.lexical_retrieval import LexicalDocument, bm25_scores
from app.rag.retriever import RagEvidence


MAX_PDF_PAGES = 100
MAX_PDF_CHARACTERS = 180_000

_QUESTION_FILLER = re.compile(
    r"请问|帮我|请解释|请说明|什么是|是什么|为什么|如何|怎么|有什么|哪些|多少|一下|这份资料|的|吗"
)
_ENGLISH_FILLER = frozenset("a an about and are can do does explain for how in is it me of on the to what why".split())
_TERMS = re.compile(r"[a-z0-9_]+|[\u3400-\u9fff]+")
_SENTENCES = re.compile(r"\n+|(?<=[。！？；])|(?<=[.!?])\s+")
_WINDOW = 480
_OVERLAP = 80


class PdfPage(BaseModel):
    number: int = Field(ge=1, le=MAX_PDF_PAGES)
    text: str = Field(min_length=1, max_length=MAX_PDF_CHARACTERS)


class PdfEvidence(BaseModel):
    page: int
    excerpt: str


class PdfAnswer(BaseModel):
    outcome: Literal["answered", "insufficient_evidence", "review_required"]
    guidance: str
    evidence: list[PdfEvidence] = Field(default_factory=list)


@dataclass(frozen=True)
class Passage:
    page: int
    text: str


def answer_personal_pdf(question: str, pages: list[PdfPage]) -> PdfAnswer:
    query = _QUESTION_FILLER.sub(" ", question.lower())
    terms = _search_terms(query)
    passages = _candidate_passages(terms, pages)
    scores = bm25_scores(query, [LexicalDocument(str(i), part.text) for i, part in enumerate(passages)])
    if not scores:
        return PdfAnswer(
            outcome="insufficient_evidence",
            guidance="这份 PDF 中没有找到足以回答问题的段落。试试讲义中的术语，或选择更相关的资料。",
        )

    evidence: list[PdfEvidence] = []
    reviewer = SafetyAgent()
    ranked = sorted(scores, key=scores.__getitem__, reverse=True)
    for key in ranked:
        part = passages[int(key)]
        if any(item.page == part.page for item in evidence):
            continue
        page_title = f"PDF 第 {part.page} 页"
        source = RagEvidence(key, page_title, part.text, scores[key], page_title, 0, 0, page_title)
        if reviewer.review(part.text, [source]).passed:
            evidence.append(PdfEvidence(page=part.page, excerpt=part.text))
        if len(evidence) == 2:
            break

    if not evidence:
        return PdfAnswer(outcome="review_required", guidance="命中的段落未通过内容审核，请换一个问题或选择其他资料。")

    first = evidence[0].page
    if re.search(r"比较|区别|不同|\bdifference\b|\bcompare\b", question, re.IGNORECASE):
        task = "试着列出资料提及的对象，再用同一个条件比较它们。"
    elif re.search(r"为什么|原因|\bwhy\b", question, re.IGNORECASE):
        task = "试着分清资料里的前提、原因和结果，再用一句话串起来。"
    elif re.search(r"如何|怎么|步骤|\bhow\b", question, re.IGNORECASE):
        task = "先找出初始条件，再按顺序复述资料写到的操作。"
    else:
        task = "试着用自己的话复述段落，再对照原文检查适用条件。"
    return PdfAnswer(outcome="answered", guidance=f"先看第 {first} 页的相关段落。{task}", evidence=evidence)


def _candidate_passages(terms: set[str], pages: list[PdfPage]) -> list[Passage]:
    if not terms:
        return []
    passages: list[Passage] = []
    for page in pages:
        for sentence in _SENTENCES.split(page.text):
            text = " ".join(sentence.split())
            for start in range(0, len(text), _WINDOW - _OVERLAP):
                excerpt = text[start:start + _WINDOW].strip()
                if terms.intersection(_search_terms(excerpt.lower())):
                    passages.append(Passage(page=page.number, text=excerpt))
                if start + _WINDOW >= len(text):
                    break
    return passages


def _search_terms(text: str) -> set[str]:
    terms: set[str] = set()
    for token in _TERMS.findall(text):
        if token.isascii():
            if token not in _ENGLISH_FILLER:
                terms.add(token)
        else:
            terms.update(token[i:i + 2] for i in range(len(token) - 1))
    return terms
