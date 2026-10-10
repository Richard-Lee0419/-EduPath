"""Regression for real Docker failure: unrelated ANN hits exhausted the budget.

The transport double applies Chroma metadata predicates and ranks distractors
ahead of the relevant chunk; assertions concern returned course evidence.
Actual HTTP compatibility is checked by the development Docker CI.
"""
import pytest

from app.rag.chroma_vector_store import ChromaVectorStore


def matches(metadata, where):
    if "$and" in where:
        return all(matches(metadata, clause) for clause in where["$and"])
    if "$or" in where:
        return any(matches(metadata, clause) for clause in where["$or"])
    for key, value in where.items():
        if isinstance(value, dict) and "$in" in value:
            if metadata.get(key) not in value["$in"]:
                return False
        elif metadata.get(key) != value:
            return False
    return True


class CrowdedCourseCollection:
    def __init__(self):
        self.records = [
            (f"distractor-{index}", {"course_id": 1, "knowledge_point_id": 111,
                                    "related_knowledge_point_ids": "112|113"}, 0.01)
            for index in range(30)
        ] + [
            ("wrong-course", {"course_id": 2, "knowledge_point_id": 131}, 0.02),
            ("tree", {"course_id": 1, "knowledge_point_id": 131,
                      "related_knowledge_point_ids": "139|141"}, 0.4),
            ("graph-related", {"course_id": 1, "knowledge_point_id": 151,
                               "related_knowledge_point_ids": "131|139"}, 0.5),
        ]

    def get(self, *, where, include):
        rows = [row for row in self.records if matches(row[1], where)]
        return {"ids": [row[0] for row in rows], "metadatas": [row[1] for row in rows]}

    def query(self, *, query_embeddings, n_results, where, include):
        rows = sorted((row for row in self.records if matches(row[1], where)), key=lambda row: row[2])[:n_results]
        return {"ids": [[row[0] for row in rows]], "metadatas": [[row[1] for row in rows]],
                "documents": [[f"Course evidence: {row[0]}" for row in rows]],
                "distances": [[row[2] for row in rows]]}


@pytest.mark.parametrize("points", [[131], [139], [131, 139]])
def test_requested_direct_and_related_evidence_survives_nearer_distractors(points):
    store = ChromaVectorStore(collection=CrowdedCourseCollection())
    hits = store.search(course_id=1, query="二叉树递归遍历", top_k=2, knowledge_point_ids=points)
    assert {hit.chunk_id for hit in hits} == {"tree", "graph-related"}
    assert all(hit.course_id == 1 and hit.content for hit in hits)


def test_unknown_point_does_not_return_unrelated_evidence():
    store = ChromaVectorStore(collection=CrowdedCourseCollection())
    assert store.search(course_id=1, query="二叉树", knowledge_point_ids=[999]) == []


def test_course_only_search_keeps_nearest_neighbours_and_course_scope():
    store = ChromaVectorStore(collection=CrowdedCourseCollection())
    hits = store.search(course_id=1, query="基础课程", top_k=2)
    assert [hit.chunk_id for hit in hits] == ["distractor-0", "distractor-1"]
    assert all(hit.course_id == 1 for hit in hits)
