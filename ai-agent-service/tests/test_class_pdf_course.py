from base64 import b64encode
from io import BytesIO

import pytest
from fastapi.testclient import TestClient
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

from app.main import app
from app.services.llm_gateway import LLMGateway


def _lesson_pdf(*lines: str) -> bytes:
    writer = PdfWriter()
    writer.add_blank_page(612, 792)
    lesson = writer.add_blank_page(612, 792)
    lesson[NameObject("/Resources")] = DictionaryObject({
        NameObject("/Font"): DictionaryObject({
            NameObject("/F1"): DictionaryObject({
                NameObject("/Type"): NameObject("/Font"),
                NameObject("/Subtype"): NameObject("/Type1"),
                NameObject("/BaseFont"): NameObject("/Helvetica"),
            })
        })
    })
    stream = DecodedStreamObject()
    text = " 0 -20 Td ".join(f"({line}) Tj" for line in lines)
    stream.set_data(f"BT /F1 12 Tf 50 682 Td {text} ET".encode("ascii"))
    lesson[NameObject("/Contents")] = stream
    result = BytesIO()
    writer.write(result)
    return result.getvalue()


def test_class_pdf_grounds_learning_flows_with_an_enabled_model_that_never_receives_material(monkeypatch):
    def unexpected_remote_call(*args, **kwargs):
        pytest.fail("class course data was passed to the external model gateway")

    monkeypatch.setattr(LLMGateway, "is_configured", property(lambda _: True))
    monkeypatch.setattr(LLMGateway, "complete_json_with_metadata", unexpected_remote_call)
    client = TestClient(app)
    course_id, document_id, point_id = 4321, 9381, 100901
    general_id, unrelated_id = 9382, 9383
    ingest = {
        "course_id": course_id,
        "document_id": document_id,
        "course_code": "optics_course",
        "knowledge_point_id": point_id,
        "knowledge_point": "Spectral calibration",
        "source": "Optics.pdf",
        "filename": "Optics.pdf",
        "content_type": "application/pdf",
        "require_pdf_pages": True,
        "content_base64": b64encode(_lesson_pdf(
            "Spectral calibration uses a reference lamp.",
            "A diffraction grating separates incoming wavelengths.",
        )).decode("ascii"),
    }
    assert client.post("/kb/ingest", json={**ingest, "content_base64": "ZmFrZSBQREY="}).status_code == 422
    response = client.post("/kb/ingest", json=ingest)
    assert response.status_code == 200, response.text
    assert response.json()["index_status"] == "indexed", response.text
    chunks = response.json()["chunks"]
    assert chunks and all(chunk["document_id"] == document_id and chunk["page"] == 2 for chunk in chunks)

    try:
        scope = {"course_id": course_id, "local_only": True}
        query = {**scope, "query": "Spectral calibration"}
        hit = client.post("/kb/search", json=query).json()["results"][0]
        assert (hit["document_id"], hit["page"], hit["knowledge_point_id"]) == (document_id, 2, point_id)
        assert client.post("/kb/search", json={**query, "course_id": 4322}).json()["results"] == []
        assert client.post("/kb/search", json={**query, "query": "Tectonic fault"}).json()["results"] == []

        tutor = client.post("/tutor/chat", json={**scope, "question": "What does spectral calibration use?"})
        assert tutor.status_code == 200, tutor.text
        assert "reference lamp" in tutor.json()["answer"]
        assert tutor.json()["evidence"][0]["page"] == 2

        generated = client.post("/resource/generate", json={**scope, "resource_types": ["lecture", "codelab"]})
        assert generated.status_code == 200, generated.text
        resources = generated.json()["resources"]
        assert {resource["resource_type"] for resource in resources} == {"lecture", "codelab"}
        assert all("第 2 页" in resource["content"] for resource in resources)
        assert not any("def preorder" in resource["content"] for resource in resources)

        quiz = client.post("/quiz/generate", json={**scope, "question_count": 2})
        assert quiz.status_code == 200, quiz.text
        questions = quiz.json()["quiz"]["questions"]
        assert [question["answer"] for question in questions] == ["B", "C"]
        assert all(question["evidence_chunk_ids"] and "第 2 页" in question["explanation"] for question in questions)

        path = client.post("/path/generate", json={"course_ids": [course_id], "days": 2, "local_only": True})
        assert path.status_code == 200, path.text
        assert path.json()["path"]["course_ids"] == [course_id]
        assert all(evidence["document_id"] == document_id for evidence in path.json()["evidence"])

        first = questions[0]
        evaluation = client.post("/evaluation/analyze", json={
            **scope,
            "quiz_id": 1,
            "score": 0,
            "question_results": [{
                "question_id": 1,
                "knowledge_point": first["knowledge_point"],
                "stem": first["stem"],
                "submitted_answer": "A",
                "correct_answer": first["answer"],
                "explanation": first["explanation"],
                "correct": False,
            }],
        })
        assert evaluation.status_code == 200, evaluation.text
        report = evaluation.json()["evaluation"]
        replan = client.post("/path/replan", json={
            "course_ids": [course_id],
            "local_only": True,
            "current_path": path.json()["path"],
            "evaluation": {
                "course_id": course_id,
                "quiz_id": 1,
                "overall_score": report["overall_score"],
                "weak_points": report["weak_points"],
                "mistake_patterns": report["mistake_patterns"],
                "next_actions": report["next_actions"],
                "source_task_id": evaluation.json()["task_id"],
            },
        })
        assert replan.status_code == 200, replan.text
        assert replan.json()["path"]["daily_plan"][0]["theme"] == first["knowledge_point"]
        for response in (tutor, generated, quiz, path, evaluation, replan):
            assert response.json()["model_runtime"]["call_count"] == 0

        general = client.post("/kb/ingest", json={
            **ingest,
            "document_id": general_id,
            "filename": "Course outline.pdf",
            "knowledge_point_id": None,
            "knowledge_point": None,
        })
        assert general.status_code == 200, general.text
        assert all(chunk["knowledge_point_id"] == 0 and chunk["page"] == 2 for chunk in general.json()["chunks"])
        unrelated = client.post("/kb/ingest", json={
            **ingest,
            "document_id": unrelated_id,
            "filename": "Thermal overview.pdf",
            "knowledge_point_id": None,
            "knowledge_point": None,
            "content_base64": b64encode(_lesson_pdf("Thermal conduction transfers energy through a material.")).decode("ascii"),
        })
        assert unrelated.status_code == 200, unrelated.text
        selected = {
            **scope,
            "knowledge_point_ids": [point_id + 1],
            "knowledge_points": ["Spectral calibration"],
            "student_profile": {"weak_points": ["Thermal conduction"]},
        }
        selected_quiz = client.post("/quiz/generate", json={**selected, "question_count": 1})
        selected_resources = client.post("/resource/generate", json={
            **selected, "resource_types": ["lecture"], "goal": "Thermal conduction",
        })
        for response in (selected_quiz, selected_resources):
            assert response.status_code == 200, response.text
            assert {item["document_id"] for item in response.json()["evidence"]} == {general_id}
            assert all(item["page"] == 2 for item in response.json()["evidence"])
    finally:
        for uploaded_id in (document_id, general_id, unrelated_id):
            assert client.delete(f"/kb/documents/{uploaded_id}/vectors", params={"course_id": course_id}).status_code == 200
