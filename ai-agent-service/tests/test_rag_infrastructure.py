import json
import sys

import pytest

from app.rag.chunker import Chunker
from app.rag.document_loader import DocumentLoader, SourceDocument
from app.rag.embeddings import EmbeddingClient
from app.rag.retriever import Retriever
from app.rag.vector_store import VectorStore


def test_local_vector_store_retrieves_builtin_course_chunks():
    retriever = Retriever(vector_store=VectorStore())

    results = retriever.search(course_id=1, query="二叉树递归遍历", top_k=1, knowledge_point_ids=[131])

    assert results
    assert results[0].chunk_id.startswith("ai_chunk_1_131_")
    assert results[0].score > 0.8


def test_embedding_client_exposes_stable_dense_vector_shape():
    vector = EmbeddingClient().embed_dense("二叉树递归遍历")

    assert len(vector) == 384
    assert any(value != 0 for value in vector)


def test_semantic_chunker_adds_concept_role_metadata_and_links():
    document = SourceDocument(
        course_id=1,
        course_code="data_structures_algorithms",
        knowledge_point_id=9101,
        knowledge_point="二叉树与递归遍历",
        title="二叉树专题讲义",
        content=(
            "# 二叉树递归遍历\n"
            "定义：二叉树遍历是指按照前序、中序或后序访问根节点、左子树和右子树。"
            "递归出口是当前节点为空，递归过程需要理解调用栈、返回时机和访问顺序。"
            "例如前序遍历先访问根节点，然后递归左子树和右子树，这个过程可以映射到栈帧展开。\n\n"
            "```python\n"
            "def preorder(node):\n"
            "    if node is None:\n"
            "        return\n"
            "    visit(node)\n"
            "    preorder(node.left)\n"
            "    preorder(node.right)\n"
            "```\n\n"
            "时间复杂度 O(n)，空间复杂度在最坏情况下为 O(n)，在平衡树中为 O(log n)。\n\n"
            "# 图遍历与最短路径\n"
            "广度优先遍历使用队列逐层扩展，适合无权图最短路径；Dijkstra 算法适合非负权图。"
            "图遍历必须维护 visited 集合，避免在环中重复访问。"
        ),
        source="unit-test.md",
    )

    chunks = Chunker().chunk_uploaded_document(document, document_id=42)

    assert len(chunks) >= 2
    assert chunks[0].next_chunk_id == chunks[1].chunk_id
    assert chunks[1].prev_chunk_id == chunks[0].chunk_id
    assert any("二叉树" in anchor for anchor in chunks[0].anchors)
    assert "code" in chunks[0].roles
    assert "complexity" in chunks[0].roles
    assert chunks[0].semantic_signature


def test_document_loader_reads_optional_seed_jsonl(tmp_path):
    corpus_path = tmp_path / "seed_documents.jsonl"
    corpus_path.write_text(
        json.dumps(
            {
                "course_id": 2,
                "course_code": "computer_organization",
                "knowledge_point_id": 9201,
                "knowledge_point": "Cache 映射与局部性",
                "title": "Cache seed",
                "content": "Cache 映射方式包括直接映射、全相联和组相联。",
                "source": "unit-test",
            },
            ensure_ascii=False,
        )
        + "\nnot-json\n",
        encoding="utf-8",
    )

    documents = DocumentLoader().load_seed_documents(str(corpus_path))

    assert len(documents) == 1
    assert documents[0].course_code == "computer_organization"
    assert documents[0].knowledge_point == "Cache 映射与局部性"


def test_huggingface_embedding_falls_back_when_optional_dependency_missing(monkeypatch):
    monkeypatch.setitem(sys.modules, "sentence_transformers", None)

    vector = EmbeddingClient(provider="huggingface", model="BAAI/bge-m3", strict=False).embed_dense("Cache 映射")

    assert len(vector) == 384
    assert any(value != 0 for value in vector)


def test_huggingface_embedding_strict_mode_reports_missing_dependency(monkeypatch):
    monkeypatch.setitem(sys.modules, "sentence_transformers", None)

    with pytest.raises(RuntimeError):
        EmbeddingClient(provider="huggingface", model="BAAI/bge-m3", strict=True).embed_dense("Cache 映射")
