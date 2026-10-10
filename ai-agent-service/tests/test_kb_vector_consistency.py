"""W07-P0-KB-VECTOR-CONSISTENCY：按 document 精确删除 / 替换向量的一致性测试。

覆盖目标：
- 删除只影响指定 document，同课程其他文档不受影响；
- 重建索引被 SafetyAgent 拒绝时，旧向量必须被撤回，Retriever 不得再命中；
- 重建索引通过时是“替换”而不是“叠加”，短版本不得残留尾部旧 chunk；
- 向量删除失败 / 写入失败都必须给出可追溯的失败原因，且不留下半索引状态；
- Chroma 适配器只按精确归属删除，不误删同课程其他文档。

全部使用确定性内存向量库或 fake transport，不连接真实 Chroma，不调用真实模型。
"""

from types import SimpleNamespace

from app.agents.knowledge_agent import KnowledgeAgent
from app.agents.orchestrator_agent import OrchestratorAgent
from app.rag.chunker import Chunker
from app.rag.document_loader import DocumentLoader, SourceDocument
from app.rag.retriever import Retriever
from app.rag.vector_store import VectorStore


class _FakeChromaCollection:
    """最小 fake transport：只记录 get/delete/upsert，用于验证 Chroma 删除筛选是否精确。"""

    def __init__(self, records: list[tuple[str, dict[str, object]]]) -> None:
        self.records = records
        self.deleted: list[list[str]] = []
        self.upserts: list[dict[str, object]] = []

    def get(self, where=None, include=None):  # noqa: ANN001, ARG002 - 对齐 Chroma 客户端签名
        matches = [(chunk_id, metadata) for chunk_id, metadata in self.records if _metadata_matches(metadata, where)]
        return {"ids": [item[0] for item in matches], "metadatas": [item[1] for item in matches]}

    def delete(self, ids=None, where=None):  # noqa: ANN001 - 对齐 Chroma 客户端签名
        if where is not None:  # 不允许退化成按 where 整片删除
            raise AssertionError("删除必须按精确 id 集合执行")
        self.deleted.append(list(ids or []))

    def upsert(self, ids, documents, embeddings, metadatas):  # noqa: ANN001 - 对齐 Chroma 客户端签名
        self.upserts.append({"ids": list(ids), "metadatas": list(metadatas)})


def _metadata_matches(metadata: dict[str, object], where) -> bool:  # noqa: ANN001
    if not where:
        return True
    if "$and" in where:
        return all(_metadata_matches(metadata, clause) for clause in where["$and"])
    return all(metadata.get(key) == value for key, value in where.items())


def _isolated_retriever(tmp_path):
    store = VectorStore()
    retriever = Retriever(
        loader=DocumentLoader(seed_corpus_path=str(tmp_path / "absent-seed.jsonl"), include_builtin=False),
        vector_store=store,
    )
    return retriever, store


def _isolated_orchestrator(retriever: Retriever) -> OrchestratorAgent:
    orchestrator = OrchestratorAgent()
    orchestrator.knowledge_agent = KnowledgeAgent(retriever)
    return orchestrator


def _kb_request(document_id: int, source: str) -> SimpleNamespace:
    return SimpleNamespace(
        document_id=document_id,
        course_id=1,
        filename=source.rsplit("/", 1)[-1],
        content_type="text/markdown",
        knowledge_point_id=131,
        knowledge_point="二叉树递归遍历",
        source=source,
    )


def _ingest_document(retriever: Retriever, *, document_id: int, source: str, content: str):
    return retriever.ingest_uploaded_document(
        document_id=document_id,
        course_id=1,
        course_code="data_structures_algorithms",
        knowledge_point_id=131,
        knowledge_point="二叉树递归遍历",
        title=source.rsplit("/", 1)[-1],
        content=content,
        source=source,
    )


def test_delete_document_chunks_only_removes_target_document(tmp_path):
    retriever, store = _isolated_retriever(tmp_path)
    _ingest_document(
        retriever,
        document_id=9301,
        source="teacher-upload/doc-a.md",
        content="文档A：AVL 四类旋转的触发条件与平衡因子维护说明。",
    )
    _ingest_document(
        retriever,
        document_id=9302,
        source="teacher-upload/doc-b.md",
        content="文档B：堆与优先队列的上浮与下沉操作说明。",
    )

    before = store.search(course_id=1, query="AVL 旋转 平衡因子", top_k=10)
    assert any(hit.chunk_id.startswith("kb_9301_") for hit in before)
    assert any(hit.chunk_id.startswith("kb_9302_") for hit in before)

    removed = retriever.delete_uploaded_document(course_id=1, document_id=9301)

    assert removed >= 1
    after = store.search(course_id=1, query="AVL 旋转 平衡因子", top_k=10)
    assert all(not hit.chunk_id.startswith("kb_9301_") for hit in after)
    # 同课程的其他文档不受影响
    assert any(hit.chunk_id.startswith("kb_9302_") for hit in after)
    assert retriever.search(course_id=1, query="堆 上浮 下沉", top_k=5)


def test_rejected_new_document_is_never_written_or_retrievable(tmp_path):
    retriever, store = _isolated_retriever(tmp_path)
    orchestrator = _isolated_orchestrator(retriever)

    payload = orchestrator.ingest_knowledge_document(
        _kb_request(document_id=9399, source="teacher-upload/unsafe.md"),
        "AVL 课程资料，附带绕过考试与作弊的方法。",
    )

    assert payload["index_status"] == "failed"
    assert payload["safety_status"] == "review_required"
    assert payload["safety"]["passed"] is False
    assert payload["chunks"] == []
    assert payload["task"]["status"] == "failed"
    assert store.count() == 0
    assert retriever.search(course_id=1, query="AVL", top_k=10) == []


def test_reindex_safety_reject_withdraws_previously_indexed_chunks(tmp_path):
    from app.agents.safety_agent import SafetyAgent
    from app.schemas.resource_schema import SafetyReview

    retriever, store = _isolated_retriever(tmp_path)
    orchestrator = _isolated_orchestrator(retriever)
    request = _kb_request(document_id=9401, source="teacher-upload/reindex-unsafe.md")

    first = orchestrator.ingest_knowledge_document(request, "AVL 四类旋转的触发条件与平衡因子维护说明。")
    assert first["index_status"] == "indexed"
    assert store.count() > 0
    assert retriever.search(course_id=1, query="AVL 旋转 平衡因子", top_k=5)

    class RejectingSafetyAgent(SafetyAgent):
        def review(self, output_text, evidence, require_evidence=True):
            return SafetyReview(
                passed=False,
                risk_level="medium",
                issues=["包含不适合学习资源的风险表达"],
                suggestions=[],
                confidence=0.62,
            )

    orchestrator.safety_agent = RejectingSafetyAgent()
    second = orchestrator.ingest_knowledge_document(request, "AVL 四类旋转的补充说明，附带绕过考试的做法。")

    assert second["index_status"] == "failed"
    assert second["safety_status"] == "review_required"
    assert second["chunks"] == []
    # 当前安全结论优先于历史已索引结果：旧向量必须已经撤回，Retriever 不得再命中
    assert store.count() == 0
    assert store.search(course_id=1, query="AVL 旋转 平衡因子", top_k=5) == []
    assert retriever.search(course_id=1, query="AVL 旋转 平衡因子", top_k=5) == []


def test_reindex_safety_pass_replaces_old_chunks_instead_of_appending(tmp_path):
    retriever, store = _isolated_retriever(tmp_path)
    orchestrator = _isolated_orchestrator(retriever)
    request = _kb_request(document_id=9402, source="teacher-upload/reindex-safe.md")
    new_text = "新版本内容：堆的上浮与下沉操作要点说明。"

    orchestrator.ingest_knowledge_document(request, "旧版本内容：AVL 左旋与右旋的触发条件说明。")
    assert store.search(course_id=1, query="AVL", top_k=10)

    second = orchestrator.ingest_knowledge_document(request, new_text)
    assert second["index_status"] == "indexed"

    expected_chunks = retriever.prepare_uploaded_document(
        document_id=9402,
        course_id=1,
        course_code="data_structures_algorithms",
        knowledge_point_id=131,
        knowledge_point="二叉树递归遍历",
        title="reindex-safe.md",
        content=new_text,
        source="teacher-upload/reindex-safe.md",
    )
    # 替换而不是叠加：向量总数等于新版本的分块数
    assert store.count() == len(expected_chunks)
    contents = " ".join(hit.content for hit in store.search(course_id=1, query="堆 上浮 下沉", top_k=10))
    assert "堆" in contents
    assert "AVL" not in contents


def test_replace_removes_trailing_chunks_when_new_version_is_shorter(tmp_path):
    retriever, store = _isolated_retriever(tmp_path)
    long_text = "\n\n".join(f"## 章节{index}\n" + "AVL 旋转与平衡因子维护的说明文字。" * 20 for index in range(1, 5))
    short_text = "短版本：仅保留一段 AVL 说明。"

    old_chunks = _ingest_document(retriever, document_id=9501, source="teacher-upload/shrink.md", content=long_text)
    assert len(old_chunks) > 1
    assert store.count() == len(old_chunks)

    new_chunks = _ingest_document(retriever, document_id=9501, source="teacher-upload/shrink.md", content=short_text)
    assert len(new_chunks) < len(old_chunks)

    # 尾部旧 chunk 不得残留：剩余 id 必须恰好是新版本次序编号
    remaining_ids = sorted(hit.chunk_id for hit in store.search(course_id=1, query="AVL 旋转 平衡因子", top_k=10))
    assert remaining_ids == [f"kb_9501_{index}" for index in range(1, len(new_chunks) + 1)]
    assert store.count() == len(new_chunks)


def test_vector_delete_failure_does_not_write_new_version(tmp_path):
    class DeleteFailingStore(VectorStore):
        def delete_document_chunks(self, course_id: int, document_id: int) -> int:
            raise RuntimeError("向量库删除不可用")

    store = DeleteFailingStore()
    retriever = Retriever(
        loader=DocumentLoader(seed_corpus_path=str(tmp_path / "absent-seed.jsonl"), include_builtin=False),
        vector_store=store,
    )
    orchestrator = _isolated_orchestrator(retriever)

    payload = orchestrator.ingest_knowledge_document(
        _kb_request(document_id=9601, source="teacher-upload/delete-fail.md"),
        "AVL 旋转说明。",
    )

    assert payload["index_status"] == "failed"
    assert "向量索引写入失败" in payload["index_error"]
    # 删除失败时禁止继续写入新版本
    assert store.count() == 0
    assert payload["task"]["status"] == "failed"
    assert not any(event["event"] == "task_completed" for event in payload["task"]["events"])


def test_vector_upsert_failure_is_reported_and_partial_writes_are_cleaned(tmp_path):
    class PartialUpsertStore(VectorStore):
        def upsert_chunks(self, chunks):
            if not chunks:  # 建库引导阶段可能是空列表，不属于本次故障场景
                return
            super().upsert_chunks(chunks[:1])  # 模拟半索引：只落了一部分就中断
            raise RuntimeError("Embedding 服务中断")

    store = PartialUpsertStore()
    retriever = Retriever(
        loader=DocumentLoader(seed_corpus_path=str(tmp_path / "absent-seed.jsonl"), include_builtin=False),
        vector_store=store,
    )
    orchestrator = _isolated_orchestrator(retriever)

    payload = orchestrator.ingest_knowledge_document(
        _kb_request(document_id=9602, source="teacher-upload/upsert-fail.md"),
        "AVL 旋转说明。",
    )

    assert payload["index_status"] == "failed"
    assert "向量索引写入失败" in payload["index_error"]
    # 半成品必须被清理，不能被 Retriever 当成正常证据
    assert store.count() == 0
    assert retriever.search(course_id=1, query="AVL 旋转", top_k=5) == []


def test_chroma_delete_is_scoped_to_target_document():
    from app.rag.chroma_vector_store import ChromaVectorStore

    collection = _FakeChromaCollection(
        [
            ("kb_11_1", {"course_id": 1, "document_id": 11}),
            ("kb_11_2", {"course_id": 1, "document_id": 11}),
            ("kb_12_1", {"course_id": 1, "document_id": 12}),
            ("kb_13_1", {"course_id": 1, "document_id": 13}),
            ("kb_110_1", {"course_id": 1}),  # 形近前缀干扰项
            ("kb_11_7", {"course_id": 1}),  # 本次修复前写入、没有 document_id 的历史向量
            ("kb_11_9", {"course_id": 2, "document_id": 11}),  # 同 id、不同课程
            ("kb_11_8", {"course_id": 1, "document_id": 88}),  # 显式归属优先于旧 ID 规约
            ("ai_chunk_1_131_abc_1", {"course_id": 1}),  # 内置课程语料
        ]
    )
    store = ChromaVectorStore(collection=collection)

    removed = store.delete_document_chunks(course_id=1, document_id=11)

    assert removed == 3
    deleted = set(collection.deleted[-1])
    assert deleted == {"kb_11_1", "kb_11_2", "kb_11_7"}
    assert not deleted & {"kb_12_1", "kb_13_1", "kb_110_1", "kb_11_9", "kb_11_8", "ai_chunk_1_131_abc_1"}


def test_chroma_metadata_records_document_id_for_uploaded_chunks():
    from app.rag.chroma_vector_store import ChromaVectorStore

    collection = _FakeChromaCollection([])
    store = ChromaVectorStore(collection=collection)
    uploaded = Chunker().chunk_uploaded_document(
        _source_document(title="uploaded.md", source="teacher-upload/uploaded.md"),
        document_id=21,
    )
    builtin = Chunker().chunk_documents([_source_document(title="builtin.md", source="data-structures/builtin.md")])

    assert all(chunk.document_id == 21 for chunk in uploaded)
    assert all(chunk.document_id is None for chunk in builtin)

    store.upsert_chunks(uploaded)
    assert collection.upserts[-1]["metadatas"][0]["document_id"] == 21

    store.upsert_chunks(builtin)
    assert "document_id" not in collection.upserts[-1]["metadatas"][0]


def _ingest_via_api(client, *, document_id: int, course_id: int, filename: str, text: str) -> dict:
    from base64 import b64encode

    response = client.post(
        "/kb/ingest",
        json={
            "document_id": document_id,
            "course_id": course_id,
            "filename": filename,
            "content_type": "text/markdown",
            "content_base64": b64encode(text.encode("utf-8")).decode("ascii"),
            "knowledge_point_id": 131,
            "knowledge_point": "二叉树递归遍历",
            "source": f"teacher-upload/{filename}",
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def _search_chunk_ids(client, query: str, course_id: int = 1) -> set[str]:
    response = client.post("/kb/search", json={"course_id": course_id, "query": query, "top_k": 5})
    assert response.status_code == 200, response.text
    return {item["chunk_id"] for item in response.json()["results"]}


def test_delete_document_vectors_endpoint_scopes_to_target_document():
    """Java 删除文档前的向量撤回接口：只影响目标 document（KB search 间接验证）。"""

    from fastapi.testclient import TestClient

    from app.main import app

    client = TestClient(app)
    target_document_id = 9711
    kept_document_id = 9712
    target_text = "ZQX 目标资料：AVL 四类旋转的触发条件与平衡因子维护。"
    kept_text = "ZQX 保留资料：堆与优先队列的上浮与下沉操作要点。"

    _ingest_via_api(client, document_id=target_document_id, course_id=1, filename="delete-target.md", text=target_text)
    _ingest_via_api(client, document_id=kept_document_id, course_id=1, filename="delete-kept.md", text=kept_text)

    target_prefix = f"kb_{target_document_id}_"
    kept_prefix = f"kb_{kept_document_id}_"

    # 课程不匹配时不得删除任何向量（严格按 (course_id, document_id) 定位）
    wrong_course = client.delete(f"/kb/documents/{target_document_id}/vectors", params={"course_id": 2})
    assert wrong_course.status_code == 200
    assert wrong_course.json()["deleted_chunks"] == 0
    assert any(chunk_id.startswith(target_prefix) for chunk_id in _search_chunk_ids(client, target_text))

    response = client.delete(f"/kb/documents/{target_document_id}/vectors", params={"course_id": 1})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["document_id"] == target_document_id
    assert body["course_id"] == 1
    assert body["deleted_chunks"] >= 1

    # 目标文档：KB search 不再命中；同课程其他文档：仍然命中
    assert not any(chunk_id.startswith(target_prefix) for chunk_id in _search_chunk_ids(client, target_text))
    assert any(chunk_id.startswith(kept_prefix) for chunk_id in _search_chunk_ids(client, kept_text))

    # 幂等：再次撤回返回 0，且不影响其他文档
    again = client.delete(f"/kb/documents/{target_document_id}/vectors", params={"course_id": 1})
    assert again.status_code == 200
    assert again.json()["deleted_chunks"] == 0
    assert any(chunk_id.startswith(kept_prefix) for chunk_id in _search_chunk_ids(client, kept_text))

    # 清理本次测试写入共享进程内向量库的内容
    assert (
        client.delete(f"/kb/documents/{kept_document_id}/vectors", params={"course_id": 1}).json()["deleted_chunks"] >= 1
    )


def test_delete_document_vectors_is_idempotent_without_vectors():
    from fastapi.testclient import TestClient

    from app.main import app

    client = TestClient(app)
    response = client.delete("/kb/documents/9799/vectors", params={"course_id": 1})

    assert response.status_code == 200, response.text
    assert response.json() == {"document_id": 9799, "course_id": 1, "deleted_chunks": 0}


def test_delete_endpoint_does_not_report_success_when_vector_store_fails(monkeypatch):
    from fastapi.testclient import TestClient

    from app.api.kb_api import orchestrator
    from app.main import app

    def unavailable(**kwargs):
        raise RuntimeError("vector store unavailable")

    monkeypatch.setattr(orchestrator, "delete_knowledge_document_vectors", unavailable)
    response = TestClient(app).delete("/kb/documents/9798/vectors", params={"course_id": 1})
    assert response.status_code == 503
    assert "deleted_chunks" not in response.json()


def _source_document(*, title: str, source: str) -> SourceDocument:
    return SourceDocument(
        course_id=1,
        course_code="data_structures_algorithms",
        knowledge_point_id=131,
        knowledge_point="二叉树递归遍历",
        title=title,
        content="课程资料正文内容。",
        source=source,
    )
