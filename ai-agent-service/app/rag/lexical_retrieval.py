from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass

from app.rag.embeddings import tokenize


_FILLER = re.compile(r"请问|帮我|请解释|请说明|什么是|是什么|为什么|如何|怎么|有什么|哪些|多少|一下|的|吗")
_ENGLISH_FILLER = frozenset("a an about and are can do does explain for how in is it me of on the to what why".split())
_TERM = re.compile(r"[a-z0-9_]+|[\u3400-\u9fff]+")


def has_query_overlap(query: str, text: str) -> bool:
    """Reject unrelated class material rather than trusting a nearest-neighbor score alone."""
    normalized = _FILLER.sub(" ", query.lower())
    query_tokens = _TERM.findall(normalized)
    if len(query_tokens) == 1 and len(query_tokens[0]) == 1 and not query_tokens[0].isascii():
        return query_tokens[0] in text
    return bool(_matchable_terms(normalized) & _matchable_terms(text.lower()))


def _matchable_terms(text: str) -> set[str]:
    terms: set[str] = set()
    for term in _TERM.findall(text):
        if term.isascii():
            if term not in _ENGLISH_FILLER:
                terms.add(term)
        else:
            terms.update(term[index:index + 2] for index in range(len(term) - 1))
    return terms


@dataclass(frozen=True)
class LexicalDocument:
    document_id: str
    text: str


def bm25_scores(query: str, documents: list[LexicalDocument]) -> dict[str, float]:
    """Return normalized BM25 scores without adding a search dependency."""
    query_terms = tokenize(query)
    if not query_terms or not documents:
        return {}

    tokenized = [tokenize(document.text) for document in documents]
    average_length = sum(len(tokens) for tokens in tokenized) / max(len(tokenized), 1)
    document_frequency: Counter[str] = Counter()
    for tokens in tokenized:
        document_frequency.update(set(tokens))

    raw_scores: dict[str, float] = {}
    corpus_size = len(documents)
    k1 = 1.5
    b = 0.75
    for document, tokens in zip(documents, tokenized):
        frequencies = Counter(tokens)
        length_ratio = len(tokens) / max(average_length, 1.0)
        score = 0.0
        for term in query_terms:
            frequency = frequencies.get(term, 0)
            if frequency == 0:
                continue
            frequency_in_corpus = document_frequency.get(term, 0)
            inverse_document_frequency = math.log(
                1.0 + (corpus_size - frequency_in_corpus + 0.5) / (frequency_in_corpus + 0.5)
            )
            denominator = frequency + k1 * (1.0 - b + b * length_ratio)
            score += inverse_document_frequency * (frequency * (k1 + 1.0)) / denominator
        raw_scores[document.document_id] = score

    highest = max(raw_scores.values(), default=0.0)
    if highest <= 0:
        return {}
    return {document_id: score / highest for document_id, score in raw_scores.items() if score > 0}
