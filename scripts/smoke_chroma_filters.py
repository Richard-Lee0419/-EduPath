#!/usr/bin/env python3
"""Exercise Chroma candidate filtering in an ephemeral, self-authored collection.

Run inside the development AI container; never calls an LLM or changes the
course collection. The transport and ANN index are real, not mocked.
"""
from dataclasses import replace
from datetime import datetime, timezone
import json
import os
from urllib.parse import urlsplit
from uuid import uuid4

import chromadb

from app.rag.chunker import Chunker
from app.rag.chroma_vector_store import ChromaVectorStore
from app.rag.document_loader import SourceDocument
from app.rag.embeddings import EmbeddingClient


def main():
    assert os.getenv("EDUPATH_ENV") == "dev", "development runtime is required"
    assert os.getenv("EMBEDDING_PROVIDER") == "local", "local embeddings are required"
    url = urlsplit(os.environ["CHROMA_URL"])
    assert url.scheme == "http" and url.hostname in {"chroma", "127.0.0.1", "localhost"}
    client = chromadb.HttpClient(host=url.hostname, port=url.port or 8000)
    name = f"edupath-filter-smoke-{uuid4().hex}"
    collection = client.create_collection(name=name, metadata={"hnsw:space": "cosine"})
    stages = []
    try:
        def chunk(identifier, course, point, related, content):
            document = SourceDocument(course_id=course, course_code="synthetic",
                                      knowledge_point_id=point, knowledge_point="fixture",
                                      title=identifier, source=f"self-authored/{identifier}", content=content)
            original = Chunker().chunk_documents([document])[0]
            return replace(original, chunk_id=identifier, related_knowledge_point_ids=tuple(related))

        rows = [chunk(f"distractor-{index}", 1, 111, [112, 113], "DISTRACTOR vector fixture")
                for index in range(30)] + [
            chunk("tree", 1, 131, [139, 141], "Tree recursive traversal evidence."),
            chunk("graph-related", 1, 151, [131, 139], "Related recursion evidence."),
            chunk("wrong-course", 2, 131, [139], "Other course evidence."),
        ]
        store = ChromaVectorStore(embedding_client=EmbeddingClient(provider="local"), collection=collection)
        store.upsert_chunks(rows)
        for points in ([131], [139], [131, 139]):
            hits = store.search(course_id=1, query="DISTRACTOR vector fixture", top_k=2, knowledge_point_ids=points)
            assert {hit.chunk_id for hit in hits} == {"tree", "graph-related"}, "relevant evidence was lost"
            assert all(hit.course_id == 1 for hit in hits), "course isolation failed"
            stages.append({"knowledge_point_ids": points, "returned_chunks": 2, "status": "passed"})
        assert store.search(course_id=1, query="fixture", knowledge_point_ids=[999]) == []
        nearest = store.search(course_id=1, query="DISTRACTOR vector fixture", top_k=2)
        assert len(nearest) == 2 and all(hit.chunk_id.startswith("distractor-") for hit in nearest)
    finally:
        client.delete_collection(name)
    print(json.dumps({"status": "passed", "verified_at_utc": datetime.now(timezone.utc).isoformat(),
                      "scope": "real Chroma HTTP/ANN; 30 nearer unrelated fixtures plus direct/related evidence",
                      "stages": stages, "unknown_point_rejected": True, "course_only_search_preserved": True,
                      "fixture_collection_removed": True, "model_calls": 0,
                      "limitations": ["synthetic functional regression; not semantic retrieval quality"]}, indent=2))


if __name__ == "__main__":
    main()
