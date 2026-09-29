from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml
import pytest

from app.rag.chunker import Chunker
from app.rag.chroma_vector_store import ChromaVectorStore
from app.rag.corpus_builder import build_course_corpus
from app.rag.corpus_evaluator import RetrievalCase, evaluate_retrieval
from app.rag.courseware_transformer import CoursewareTransformer
from app.rag.document_loader import DocumentLoader, SourceDocument
from app.rag.document_parser import DocumentParseError, DocumentTooLargeError, parse_document_bytes
from app.rag.retriever import Retriever
from app.rag.vector_store import VectorStore


def test_course_corpus_builder_validates_provenance_deduplicates_and_reports_coverage(tmp_path: Path):
    curriculum_path = tmp_path / "curriculum.yaml"
    catalog_path = tmp_path / "catalog.yaml"
    source_root = tmp_path / "source_documents"
    source_root.mkdir()
    curriculum_path.write_text(
        yaml.safe_dump(
            {
                "version": 1,
                "courses": [
                    {
                        "id": 1,
                        "code": "data_structures_algorithms",
                        "name": "数据结构与算法",
                        "knowledge_points": [
                            {"id": 11, "name": "线性表"},
                            {"id": 12, "name": "栈与队列"},
                        ],
                    }
                ],
            },
            allow_unicode=True,
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    content = "线性表可以使用顺序存储或链式存储。" * 12
    (source_root / "linear-list.md").write_text(content, encoding="utf-8")
    (source_root / "duplicate.md").write_text(content, encoding="utf-8")
    catalog_path.write_text(
        yaml.safe_dump(
            {
                "version": 1,
                "documents": [
                    {
                        "path": "linear-list.md",
                        "course_code": "data_structures_algorithms",
                        "knowledge_point_id": 11,
                        "title": "线性表讲义",
                        "source": "unit-test-course",
                        "license": "test-only",
                    },
                    {
                        "path": "duplicate.md",
                        "course_code": "data_structures_algorithms",
                        "knowledge_point_id": 11,
                        "title": "重复讲义",
                        "source": "unit-test-course-copy",
                        "license": "test-only",
                    },
                ],
            },
            allow_unicode=True,
            sort_keys=False,
        ),
        encoding="utf-8",
    )

    result = build_course_corpus(
        curriculum_path=curriculum_path,
        catalog_path=catalog_path,
        source_root=source_root,
        include_builtin_in_coverage=False,
    )

    assert not result.has_errors
    assert len(result.seed_documents) == 1
    assert result.seed_documents[0]["license"] == "test-only"
    assert result.seed_documents[0]["content_hash"]
    assert result.report["summary"]["duplicate_documents_skipped"] == 1
    assert result.report["summary"]["coverage_rate"] == 0.5
    assert result.report["competition_ready"] is False


def test_course_corpus_builder_rejects_missing_license_and_path_traversal(tmp_path: Path):
    curriculum_path = tmp_path / "curriculum.yaml"
    catalog_path = tmp_path / "catalog.yaml"
    source_root = tmp_path / "source_documents"
    source_root.mkdir()
    curriculum_path.write_text(
        "version: 1\ncourses:\n  - id: 1\n    code: data_structures_algorithms\n    name: 数据结构与算法\n    knowledge_points:\n      - id: 11\n        name: 线性表\n",
        encoding="utf-8",
    )
    catalog_path.write_text(
        "version: 1\ndocuments:\n  - path: ../outside.md\n    course_code: data_structures_algorithms\n    knowledge_point_id: 11\n    title: 非法资料\n    source: unit-test\n",
        encoding="utf-8",
    )

    result = build_course_corpus(
        curriculum_path=curriculum_path,
        catalog_path=catalog_path,
        source_root=source_root,
        include_builtin_in_coverage=False,
    )

    assert result.has_errors
    assert not result.seed_documents
    assert "path、course_code、title、source 和 license" in result.issues[0].message

    catalog_path.write_text(
        "version: 1\ndocuments:\n  - path: ../outside.md\n    course_code: data_structures_algorithms\n    knowledge_point_id: 11\n    title: 非法资料\n    source: unit-test\n    license: test-only\n",
        encoding="utf-8",
    )
    traversal_result = build_course_corpus(
        curriculum_path=curriculum_path,
        catalog_path=catalog_path,
        source_root=source_root,
        include_builtin_in_coverage=False,
    )

    assert traversal_result.has_errors
    assert "不允许跳出" in traversal_result.issues[0].message


def test_multiple_documents_for_same_knowledge_point_receive_unique_chunk_ids():
    documents = [
        SourceDocument(1, "data_structures_algorithms", 131, "二叉树递归遍历", "讲义 A", "前序遍历先根后左右。", "a.md"),
        SourceDocument(1, "data_structures_algorithms", 131, "二叉树递归遍历", "讲义 B", "中序遍历先左再根后右。", "b.md"),
    ]

    chunks = Chunker().chunk_documents(documents)

    assert len(chunks) == 2
    assert chunks[0].chunk_id != chunks[1].chunk_id
    assert all(chunk.chunk_id.startswith("ai_chunk_1_131_") for chunk in chunks)


def test_document_parser_normalizes_text_and_enforces_size_limit():
    parsed = parse_document_bytes("第一行\r\n\r\n\r\n第二行".encode(), filename="lesson.md", max_bytes=100)

    assert parsed == "第一行\n\n第二行"
    try:
        parse_document_bytes(b"12345", filename="lesson.md", max_bytes=4)
    except DocumentTooLargeError as exception:
        assert exception.actual_bytes == 5
    else:
        raise AssertionError("expected DocumentTooLargeError")

    try:
        parse_document_bytes(b"   \n\t", filename="empty.md")
    except DocumentParseError:
        pass
    else:
        raise AssertionError("expected DocumentParseError")


def test_retrieval_evaluator_reports_hit_rate_and_mrr():
    retriever = Retriever(vector_store=VectorStore())
    cases = (
        RetrievalCase("tree", 1, "二叉树递归遍历", (131,), 3),
        RetrievalCase("cache", 2, "Cache 组相联映射", (251,), 3),
    )

    report = evaluate_retrieval(retriever, cases)

    assert report["summary"]["hit_rate_at_k"] == 1.0
    assert report["summary"]["mean_reciprocal_rank"] == 1.0


def test_chroma_upserts_full_corpus_in_bounded_batches():
    class FakeEmbeddingClient:
        def embed_dense(self, text: str) -> list[float]:
            return [float(len(text)), 1.0]

    class FakeCollection:
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []

        def upsert(self, **kwargs: Any) -> None:
            self.calls.append(kwargs)

    documents = [
        SourceDocument(
            1,
            "data_structures_algorithms",
            131,
            "二叉树递归遍历",
            f"讲义 {index}",
            f"这是第 {index} 份不同的二叉树课程资料，包含递归遍历示例。",
            f"source-{index}.md",
            license="test-only",
        )
        for index in range(5)
    ]
    chunks = Chunker().chunk_documents(documents)
    store = ChromaVectorStore(embedding_client=FakeEmbeddingClient(), batch_size=2)
    collection = FakeCollection()
    store._collection = collection

    store.upsert_chunks(chunks)

    assert len(collection.calls) == 3
    assert [len(call["ids"]) for call in collection.calls] == [2, 2, 1]
    assert all(metadata["license"] == "test-only" for call in collection.calls for metadata in call["metadatas"])
    assert all(metadata["content_hash"] for call in collection.calls for metadata in call["metadatas"])


def test_courseware_transformer_removes_slide_furniture_and_detects_examples():
    repeated_header = "CS211FZ Course Slides"
    content = "\n".join(
        [
            repeated_header,
            "lecturer@example.edu",
            "1 / 4",
            "Definition: A max heap keeps each parent greater than its children.",
            repeated_header,
            "Worked Example: insert 9 and bubble it up.",
            repeated_header,
            "Algorithm: sift down the root after deletion.",
            repeated_header,
            "Worst-case time complexity is O(log n).",
        ]
    )

    result = CoursewareTransformer().transform("Heap", content)

    assert repeated_header not in result.content
    assert "lecturer@example.edu" not in result.content
    assert "bubble it up" in result.content
    assert result.contains_examples is True
    assert {"definition", "algorithm", "example", "complexity"}.issubset(result.teaching_roles)
    assert result.clean_character_count < result.raw_character_count


def test_related_knowledge_points_count_toward_coverage_and_retrieval():
    document = SourceDocument(
        1,
        "data_structures_algorithms",
        151,
        "排序算法",
        "Heapsort",
        "Build a max heap, swap the root with the last item, and sift down.",
        "heaps.md",
        related_knowledge_point_ids=(133,),
    )
    store = VectorStore()
    store.upsert_chunks(Chunker().chunk_documents([document]))

    hits = store.search(course_id=1, query="heap array", knowledge_point_ids=[133], top_k=1)

    assert hits
    assert hits[0].knowledge_point_id == 151
    assert hits[0].related_knowledge_point_ids == (133,)


def test_real_course_corpus_bootstraps_and_retrieves_external_courseware():
    seed_path = Path(__file__).resolve().parents[1] / "data" / "seed_corpus" / "seed_documents.jsonl"
    if not seed_path.exists():
        pytest.skip("本地未生成真实课程 seed_documents.jsonl")

    retriever = Retriever(
        loader=DocumentLoader(seed_corpus_path=str(seed_path), include_builtin=False),
        vector_store=VectorStore(),
    )
    hits = retriever.search(
        course_id=1,
        query="单链表在表头插入新节点时如何重连 next 指针？",
        top_k=5,
    )

    assert retriever.bootstrap_stats["mode"] == "course_corpus"
    # The distributable open-source corpus contains 108 concise teaching units.
    # Keep a density floor without coupling this check to the longer private courseware pack.
    assert retriever.bootstrap_stats["external_documents"] >= 100
    assert retriever.bootstrap_stats["chunks"] >= 800
    assert hits
    assert any(hit.knowledge_point_id == 9340 for hit in hits)
    assert all(hit.source for hit in hits)
    assert all(hit.license_status for hit in hits)
