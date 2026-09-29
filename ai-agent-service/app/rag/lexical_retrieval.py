from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass

from app.rag.embeddings import tokenize


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
