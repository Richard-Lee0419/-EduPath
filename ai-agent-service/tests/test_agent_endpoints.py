from base64 import b64encode
from io import BytesIO
import json
import time

import pytest
from fastapi.testclient import TestClient

from app.api.kb_api import _decode_document_text
from app.agents.orchestrator_agent import orchestrator
from app.core.config import Settings
from app.main import app
from app.services.llm_gateway import LLMGateway


client = TestClient(app)


def test_quiz_evaluation_closure_is_grounded_and_limited_to_three_questions():
    quiz_response = client.post(
        "/quiz/generate",
        json={
            "course_id": 1,
            "knowledge_point_ids": [131],
            "knowledge_points": ["二叉树递归遍历", "Cache 映射方式"],
            "difficulty": "basic",
            "question_count": 3,
            "student_profile": {"weak_points": ["递归边界条件"]},
        },
    )

    assert quiz_response.status_code == 200, quiz_response.text
    quiz_payload = quiz_response.json()
    assert len(quiz_payload["quiz"]["questions"]) == 3
    assert quiz_payload["planned_agents"] == ["KnowledgeAgent", "QuizAgent", "SafetyAgent"]
    assert quiz_payload["generation_mode"] == "deterministic_fallback"
    assert quiz_payload["safety"]["passed"] is True
    assert "Cache" not in quiz_payload["quiz"]["title"]
    evidence_ids = {item["chunk_id"] for item in quiz_payload["evidence"]}
    assert all(
        set(question["evidence_chunk_ids"]).issubset(evidence_ids)
        for question in quiz_payload["quiz"]["questions"]
    )

    question_results = [
        {
            "question_id": index,
            "knowledge_point": question["knowledge_point"],
            "stem": question["stem"],
            "submitted_answer": "故意错误选项",
            "correct_answer": question["options"][ord(question["answer"]) - ord("A")],
            "explanation": question["explanation"],
            "correct": False,
        }
        for index, question in enumerate(quiz_payload["quiz"]["questions"], start=1)
    ]
    evaluation_response = client.post(
        "/evaluation/analyze",
        json={
            "quiz_id": 1,
            "course_id": 1,
            "score": 0,
            "question_results": question_results,
            "student_profile": {"email": "private@example.com"},
        },
    )

    assert evaluation_response.status_code == 200, evaluation_response.text
    evaluation_payload = evaluation_response.json()
    assert evaluation_payload["evaluation"]["overall_score"] == 0
    assert evaluation_payload["evaluation"]["weak_points"] == list(
        dict.fromkeys(item["knowledge_point"] for item in question_results)
    )
    assert evaluation_payload["planned_agents"] == ["KnowledgeAgent", "EvaluationAgent", "SafetyAgent"]
    assert evaluation_payload["generation_mode"] == "deterministic_fallback"
    assert evaluation_payload["safety"]["passed"] is True


def test_real_quiz_and_evaluation_agents_repair_invalid_outputs_and_hide_identifiers(monkeypatch):
    captured_bodies: list[dict[str, object]] = []
    quiz_attempts = 0
    evaluation_attempts = 0

    class FakeResponse:
        headers = {"x-request-id": "quiz-evaluation-request-id"}

        def __init__(self, payload: dict[str, object]):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")

    def fake_urlopen(request, timeout):
        nonlocal quiz_attempts, evaluation_attempts
        body = json.loads(request.data.decode("utf-8"))
        captured_bodies.append(body)
        prompt = body["messages"][1]["content"]
        context = json.loads(body["messages"][2]["content"])
        if prompt.startswith("你是 QuizAgent"):
            quiz_attempts += 1
            quiz = json.loads(json.dumps(context["baseline"], ensure_ascii=False))
            if quiz_attempts == 1:
                quiz["questions"][0]["evidence_chunk_ids"] = ["invented_chunk"]
            content = {"quiz": quiz}
        elif prompt.startswith("你是 EvaluationAgent"):
            evaluation_attempts += 1
            evaluation = json.loads(json.dumps(context["baseline"], ensure_ascii=False))
            if evaluation_attempts == 1:
                evaluation["overall_score"] = 100
            content = {"evaluation": evaluation}
        else:
            content = {
                "review": {
                    "passed": True,
                    "risk_level": "low",
                    "issues": [],
                    "suggestions": ["已核对确定性结果与课程证据"],
                    "confidence": 0.96,
                }
            }
        return FakeResponse(
            {
                "model": "fake-quiz-evaluation-model",
                "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content, ensure_ascii=False)}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150},
            }
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-quiz-evaluation-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    gateway = LLMGateway(runtime)
    monkeypatch.setattr(orchestrator.quiz_generation_pipeline, "llm_gateway", gateway)
    monkeypatch.setattr(orchestrator.evaluation_pipeline, "llm_gateway", gateway)

    quiz_response = client.post(
        "/quiz/generate",
        json={
            "course_id": 1,
            "knowledge_point_ids": [131],
            "knowledge_points": ["二叉树递归遍历"],
            "difficulty": "basic",
            "question_count": 1,
            "student_profile": {
                "student_id": "private-quiz-student",
                "email": "private@example.com",
                "weak_points": ["递归边界条件"],
            },
        },
    )

    assert quiz_response.status_code == 200, quiz_response.text
    quiz_payload = quiz_response.json()
    assert quiz_payload["generation_mode"] == "real_model"
    assert quiz_payload["model_runtime"]["call_count"] == 3
    assert [call["agent"] for call in quiz_payload["model_runtime"]["calls"]] == [
        "QuizAgent",
        "QuizAgent",
        "SafetyAgent",
    ]
    question = quiz_payload["quiz"]["questions"][0]

    evaluation_response = client.post(
        "/evaluation/analyze",
        json={
            "quiz_id": 7,
            "course_id": 1,
            "score": 0,
            "question_results": [
                {
                    "question_id": 1,
                    "knowledge_point": question["knowledge_point"],
                    "stem": question["stem"],
                    "submitted_answer": "private@example.com",
                    "correct_answer": question["options"][ord(question["answer"]) - ord("A")],
                    "explanation": question["explanation"],
                    "correct": False,
                }
            ],
            "student_profile": {"student_id": "private-quiz-student"},
        },
    )

    assert evaluation_response.status_code == 200, evaluation_response.text
    evaluation_payload = evaluation_response.json()
    assert evaluation_payload["evaluation"]["overall_score"] == 0
    assert evaluation_payload["generation_mode"] == "real_model"
    assert evaluation_payload["model_runtime"]["call_count"] == 3
    assert [call["agent"] for call in evaluation_payload["model_runtime"]["calls"]] == [
        "EvaluationAgent",
        "EvaluationAgent",
        "SafetyAgent",
    ]
    outbound = json.dumps(captured_bodies, ensure_ascii=False)
    assert "invented_chunk" not in json.dumps(quiz_payload, ensure_ascii=False)
    assert "private-quiz-student" not in outbound
    assert "private@example.com" not in outbound


def test_real_quiz_falls_back_to_grounded_baseline_after_repeated_invalid_model_output(monkeypatch):
    class FakeResponse:
        headers = {"x-request-id": "quiz-fallback-request-id"}

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(
                {
                    "model": "fake-quiz-fallback-model",
                    "choices": [
                        {
                            "finish_reason": "stop",
                            "message": {
                                "content": json.dumps(
                                    {
                                        "quiz": {
                                            "title": "不合格模型题目",
                                            "questions": [
                                                {
                                                    "question_order": 1,
                                                    "type": "single_choice",
                                                    "difficulty": "basic",
                                                    "knowledge_point": "二叉树递归遍历",
                                                    "stem": "证据没有支持的题目",
                                                    "options": ["A项", "B项", "C项", "D项"],
                                                    "answer": "A",
                                                    "explanation": "证据没有支持的解析",
                                                    "evidence_chunk_ids": ["invented_chunk"],
                                                }
                                            ],
                                        }
                                    },
                                    ensure_ascii=False,
                                )
                            },
                        }
                    ],
                    "usage": {"prompt_tokens": 80, "completion_tokens": 40, "total_tokens": 120},
                },
                ensure_ascii=False,
            ).encode("utf-8")

    monkeypatch.setattr("urllib.request.urlopen", lambda request, timeout: FakeResponse())
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-quiz-fallback-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(orchestrator.quiz_generation_pipeline, "llm_gateway", LLMGateway(runtime))

    response = client.post(
        "/quiz/generate",
        json={
            "course_id": 1,
            "knowledge_point_ids": [131],
            "knowledge_points": ["二叉树递归遍历"],
            "difficulty": "basic",
            "question_count": 1,
            "student_profile": {"weak_points": ["递归边界条件"]},
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["generation_mode"] == "deterministic_fallback"
    assert payload["model_runtime"]["real_model_used"] is True
    assert payload["model_runtime"]["call_count"] == 2
    assert payload["safety"]["passed"] is True
    evidence_ids = {item["chunk_id"] for item in payload["evidence"]}
    assert all(
        set(question["evidence_chunk_ids"]).issubset(evidence_ids)
        for question in payload["quiz"]["questions"]
    )
    assert "invented_chunk" not in json.dumps(payload, ensure_ascii=False)


def test_resource_generate_returns_structured_agent_plan():
    response = client.post(
        "/resource/generate",
        json={
            "course_id": 1,
            "knowledge_point_ids": [131],
            "resource_types": ["lecture", "quiz"],
            "difficulty": "basic",
            "student_profile": {"student_id": "demo_student"},
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["task_id"].startswith("ai_resource_")
    assert payload["status"] == "success"
    assert payload["planned_agents"][0] == "ProfileAgent"
    assert payload["planned_agents"][-1] == "SafetyAgent"
    assert payload["safety_status"] == "passed"
    assert payload["resources"][0]["resource_type"] == "lecture"
    assert payload["resources"][0]["content"]
    assert payload["resources"][0]["quality_evaluation"]["total_score"] >= 75
    assert payload["resources"][0]["quality_evaluation"]["gate_passed"] is True
    assert set(payload["resources"][0]["quality_evaluation"]["dimensions"]) == {
        "evidence_coverage",
        "structural_completeness",
        "knowledge_consistency",
        "difficulty_alignment",
        "safety_review",
    }
    assert payload["evidence"][0]["chunk_id"].startswith("ai_chunk_1_")
    assert payload["safety"]["passed"] is True


def test_resource_generation_changes_for_different_student_profiles():
    common = {
        "course_id": 1,
        "knowledge_point_ids": [131],
        "resource_types": ["lecture", "mindmap", "codelab"],
        "difficulty": "medium",
    }
    visual = client.post(
        "/resource/generate",
        json={
            **common,
            "student_profile": {
                "student_id": "visual_beginner",
                "learning_goal": "通过期末考试",
                "weak_points": ["递归调用栈"],
                "resource_preference": ["mindmap", "animation_script"],
                "cognitive_style": ["visual", "step_by_step"],
                "knowledge_base": {"algorithm": "beginner"},
                "latest_quiz_score": 45,
                "learning_pace": "每天 30 分钟",
            },
        },
    )
    practical = client.post(
        "/resource/generate",
        json={
            **common,
            "student_profile": {
                "student_id": "practical_advanced",
                "learning_goal": "完成竞赛项目",
                "weak_points": ["递归边界条件"],
                "resource_preference": ["codelab", "quiz"],
                "cognitive_style": ["example_first"],
                "knowledge_base": {"algorithm": "advanced"},
                "latest_quiz_score": 90,
                "learning_pace": "每天 60 分钟",
            },
        },
    )

    assert visual.status_code == practical.status_code == 200
    visual_resources = visual.json()["resources"]
    practical_resources = practical.json()["resources"]
    assert visual_resources[0]["profile_fingerprint"] != practical_resources[0]["profile_fingerprint"]
    assert visual_resources[0]["personalized_reason"] != practical_resources[0]["personalized_reason"]
    assert visual_resources[0]["difficulty"] == "basic"
    assert practical_resources[0]["difficulty"] == "advanced"
    assert visual_resources[0]["estimated_minutes"] != practical_resources[0]["estimated_minutes"]
    assert visual_resources[0]["content"] != practical_resources[0]["content"]


def test_knowledge_search_returns_rag_evidence():
    response = client.post("/kb/search", json={"course_id": 1, "query": "二叉树递归遍历", "top_k": 2})

    assert response.status_code == 200
    payload = response.json()
    assert len(payload["results"]) == 2
    assert payload["results"][0]["chunk_id"].startswith("ai_chunk_1_")
    assert payload["results"][0]["score"] > 0.8
    assert payload["course_id"] == 1
    assert payload["query"] == "二叉树递归遍历"
    assert payload["corpus"]["mode"] in {"builtin_demo", "course_corpus"}
    assert payload["corpus"]["chunks"] >= 8
    assert payload["results"][0]["knowledge_point_id"] == 131
    assert payload["results"][0]["knowledge_point"] == "二叉树递归遍历"
    assert "license_status" in payload["results"][0]


def test_uploaded_document_is_ingested_into_rag_search():
    unique_text = "AVL 调整实验要求记录 LL、RR、LR、RL 四类旋转触发条件。"
    response = client.post(
        "/kb/ingest",
        json={
            "document_id": 9001,
            "course_id": 1,
            "filename": "avl-rotation.md",
            "content_type": "text/markdown",
            "content_base64": b64encode(unique_text.encode("utf-8")).decode("ascii"),
            "knowledge_point_id": 133,
            "knowledge_point": "堆与优先队列",
            "source": "teacher-upload/avl-rotation.md",
        },
    )

    assert response.status_code == 200
    ingest = response.json()
    assert ingest["parse_status"] == "parsed"
    assert ingest["index_status"] == "indexed"
    assert ingest["chunks"][0]["chunk_id"].startswith("kb_9001_")
    assert ingest["task"]["steps"][-1]["agent"] == "SafetyAgent"
    assert ingest["task"]["progress"] == 100

    search_response = client.post("/kb/search", json={"course_id": 1, "query": "AVL 四类旋转触发条件", "top_k": 1})
    assert search_response.status_code == 200
    result = search_response.json()["results"][0]
    assert result["chunk_id"].startswith("kb_9001_")
    assert "AVL" in result["content"]


def test_pptx_document_text_is_extracted_for_ingest():
    pptx = pytest.importorskip("pptx")
    presentation = pptx.Presentation()
    slide = presentation.slides.add_slide(presentation.slide_layouts[5])
    slide.shapes.title.text = "递归调用栈课件"
    text_box = slide.shapes.add_textbox(0, 0, 4000000, 1000000)
    text_box.text = "每次递归调用都会创建新的栈帧。"
    buffer = BytesIO()
    presentation.save(buffer)

    text = _decode_document_text(
        b64encode(buffer.getvalue()).decode("ascii"),
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "recursion-stack.pptx",
    )

    assert "递归调用栈课件" in text
    assert "新的栈帧" in text


def test_kb_ingest_rejects_malformed_base64():
    response = client.post(
        "/kb/ingest",
        json={
            "document_id": 9002,
            "course_id": 1,
            "filename": "broken.md",
            "content_type": "text/markdown",
            "content_base64": "not-base64-@@",
        },
    )

    assert response.status_code == 400
    assert "base64" in response.json()["detail"]


def test_kb_ingest_rejects_empty_parsed_text():
    response = client.post(
        "/kb/ingest",
        json={
            "document_id": 9003,
            "course_id": 1,
            "filename": "empty.md",
            "content_type": "text/markdown",
            "content_base64": b64encode(b"   \n\t").decode("ascii"),
        },
    )

    assert response.status_code == 422
    assert "有效文本" in response.json()["detail"]


def test_kb_ingest_rejects_oversized_documents(monkeypatch):
    monkeypatch.setattr(
        "app.api.kb_api.settings",
        Settings(
            vector_store_provider="local",
            chroma_url="",
            chroma_collection="",
            llm_provider="",
            llm_base_url="",
            llm_model="",
            llm_api_key="",
            llm_timeout_seconds=30,
            embedding_provider="local",
            embedding_model="local-hash-v1",
            embedding_api_key="",
            max_ingest_bytes=4,
        ),
    )
    response = client.post(
        "/kb/ingest",
        json={
            "document_id": 9004,
            "course_id": 1,
            "filename": "too-large.md",
            "content_type": "text/markdown",
            "content_base64": b64encode(b"12345").decode("ascii"),
        },
    )

    assert response.status_code == 413
    assert "大小超过限制" in response.json()["detail"]


def test_profile_path_and_tutor_return_structured_payloads():
    profile_response = client.post(
        "/profile/extract",
        json={"student_id": "demo_student", "message": "我想补齐递归和 Cache", "course_ids": [1, 2]},
    )
    assert profile_response.status_code == 200
    profile = profile_response.json()
    assert profile["task_id"].startswith("ai_profile_")
    assert profile["profile"]["student_id"] == "demo_student"
    assert profile["extracted"]["safety_status"] == "passed"
    assert len(profile["dimensions"]) >= 8
    assert "learning_pace" in profile["profile"]
    assert profile["generation_mode"] == "deterministic_fallback"
    assert profile["model_runtime"]["call_count"] == 0
    assert profile["safety"]["passed"] is True

    path_response = client.post(
        "/path/generate",
        json={"course_ids": [1, 2], "target": "两周补强", "days": 3, "daily_minutes": 40},
    )
    assert path_response.status_code == 200
    path = path_response.json()
    assert path["path"]["path_id"].startswith("path_ai_")
    assert len(path["path"]["daily_plan"]) == 3
    assert path["path"]["path_title"] == "3 天两周补强"
    assert path["generation_mode"] == "deterministic_fallback"
    assert path["model_runtime"]["call_count"] == 0
    assert path["safety"]["passed"] is True
    assert all(day["evidence_chunk_ids"] for day in path["path"]["daily_plan"])
    assert all(
        sum(task["estimated_minutes"] for task in day["tasks"]) <= 40
        for day in path["path"]["daily_plan"]
    )

    explicit_days_response = client.post(
        "/path/generate",
        json={"course_ids": [1], "target": "7 天掌握递归调用栈", "days": 7, "daily_minutes": 40},
    )
    assert explicit_days_response.status_code == 200
    assert explicit_days_response.json()["path"]["path_title"] == "7 天掌握递归调用栈"

    visual_path = client.post(
        "/path/generate",
        json={
            "course_ids": [1],
            "target": "巩固算法",
            "days": 2,
            "daily_minutes": 30,
            "student_profile": {
                "student_id": "visual_student",
                "weak_points": ["最短路径"],
                "resource_preference": ["mindmap"],
                "latest_quiz_score": 50,
            },
        },
    ).json()["path"]
    code_path = client.post(
        "/path/generate",
        json={
            "course_ids": [1],
            "target": "巩固算法",
            "days": 2,
            "daily_minutes": 60,
            "student_profile": {
                "student_id": "code_student",
                "weak_points": ["递归调用栈"],
                "resource_preference": ["codelab"],
                "latest_quiz_score": 85,
            },
        },
    ).json()["path"]
    assert visual_path["daily_plan"][0]["theme"] == "最短路径"
    assert code_path["daily_plan"][0]["theme"] == "递归调用栈"
    assert visual_path["daily_plan"][0]["tasks"][0]["type"] == "mindmap"
    assert code_path["daily_plan"][0]["tasks"][0]["type"] == "codelab"
    assert visual_path["personalization_summary"] != code_path["personalization_summary"]
    assert visual_path["daily_plan"][0]["reason"] != code_path["daily_plan"][0]["reason"]

    tutor_response = client.post(
        "/tutor/chat",
        json={"course_id": 1, "question": "前序遍历为什么先访问根节点？", "answer_mode": "step_by_step"},
    )
    assert tutor_response.status_code == 200
    tutor = tutor_response.json()
    assert tutor["task_id"].startswith("ai_tutor_")
    assert tutor["safety"]["passed"] is True
    assert tutor["evidence"][0]["source"]


def test_real_path_agents_retry_invalid_budget_deidentify_profile_and_expose_runtime(monkeypatch):
    captured_bodies: list[dict[str, object]] = []
    path_attempts = 0

    class FakeResponse:
        headers = {"x-request-id": "path-request-id"}

        def __init__(self, payload: dict[str, object]):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")

    def fake_urlopen(request, timeout):
        nonlocal path_attempts
        body = json.loads(request.data.decode("utf-8"))
        captured_bodies.append(body)
        prompt = body["messages"][1]["content"]
        context = json.loads(body["messages"][2]["content"])
        if prompt.startswith("你是独立的 SafetyAgent"):
            content = {
                "review": {
                    "passed": True,
                    "risk_level": "low",
                    "issues": [],
                    "suggestions": ["路径时间预算与证据引用有效"],
                    "confidence": 0.95,
                }
            }
        else:
            path_attempts += 1
            path = context["baseline"]
            path["path_title"] = "3 天递归调用栈补强路径"
            path["daily_plan"][0]["reason"] = "优先补强递归调用栈，并采用分步骤代码练习降低理解负担。"
            if path_attempts == 1:
                path["daily_plan"][0]["tasks"][0]["estimated_minutes"] = 31
            content = {"path": path}
        return FakeResponse(
            {
                "model": "fake-path-model",
                "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content, ensure_ascii=False)}}],
                "usage": {"prompt_tokens": 120, "completion_tokens": 80, "total_tokens": 200},
            }
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-path-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(orchestrator.path_generation_pipeline, "llm_gateway", LLMGateway(runtime))

    response = client.post(
        "/path/generate",
        json={
            "course_ids": [1],
            "target": "我的名字是王小明，3 天补强递归调用栈",
            "days": 3,
            "daily_minutes": 30,
            "student_profile": {
                "student_id": "private-path-student",
                "learning_goal": "alice@example.com 希望通过分步骤练习理解递归",
                "weak_points": ["递归调用栈"],
                "resource_preference": ["codelab"],
                "latest_quiz_score": 55,
            },
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["generation_mode"] == "real_model"
    assert payload["model_runtime"]["call_count"] == 3
    assert payload["model_runtime"]["total_tokens"] == 600
    assert [call["agent"] for call in payload["model_runtime"]["calls"]] == [
        "PathAgent",
        "PathAgent",
        "SafetyAgent",
    ]
    assert payload["safety"]["passed"] is True
    assert len(payload["path"]["daily_plan"]) == 3
    valid_ids = {item["chunk_id"] for item in payload["evidence"]}
    assert all(
        set(day["evidence_chunk_ids"]).issubset(valid_ids)
        for day in payload["path"]["daily_plan"]
    )
    assert all(
        sum(task["estimated_minutes"] for task in day["tasks"]) <= 30
        for day in payload["path"]["daily_plan"]
    )
    outbound = json.dumps(captured_bodies, ensure_ascii=False)
    assert "private-path-student" not in outbound
    assert "王小明" not in outbound
    assert "alice@example.com" not in outbound


def test_deterministic_resource_types_all_pass_quality_gate():
    response = client.post(
        "/resource/generate",
        json={
            "course_id": 1,
            "knowledge_point_ids": [131],
            "knowledge_points": ["二叉树递归遍历"],
            "resource_types": [
                "lecture",
                "mindmap",
                "quiz",
                "codelab",
                "animation_script",
                "flowchart",
                "reading",
            ],
            "difficulty": "basic",
            "student_profile": {},
        },
    )

    assert response.status_code == 200
    resources = response.json()["resources"]
    runtime = response.json()["model_runtime"]
    assert len(resources) == 7
    assert runtime["quality_feedback_revision_rounds"] == 0
    assert runtime["quality_revised_resource_count"] == 0
    assert all(item["quality_evaluation"]["gate_passed"] for item in resources)
    assert all(
        min(dimension["score"] for dimension in item["quality_evaluation"]["dimensions"].values()) >= 60
        for item in resources
    )


def test_evaluation_driven_path_replan_is_bounded_grounded_and_prioritizes_weak_point():
    response = client.post(
        "/path/replan",
        json={
            "course_ids": [1],
            "target": "根据二叉树小测调整后续学习",
            "days": 3,
            "daily_minutes": 30,
            "student_profile": {
                "student_id": "private-replan-student",
                "resource_preference": ["mindmap"],
                "weak_points": ["旧画像弱点"],
                "latest_quiz_score": 90,
            },
            "current_path": {
                "path_id": "path_previous",
                "path_title": "原学习路径",
                "target": "掌握二叉树",
                "daily_minutes": 30,
                "daily_plan": [{"day": 1, "theme": "图的遍历", "tasks": []}],
            },
            "evaluation": {
                "quiz_id": 7,
                "course_id": 1,
                "overall_score": 0,
                "weak_points": ["二叉树递归遍历"],
                "mistake_patterns": ["concept_confusion"],
                "next_actions": ["先复盘递归出口，再完成一次补救小测"],
                "source_task_id": "ai_evaluation_007",
            },
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["trigger"] == "quiz_evaluation"
    assert payload["previous_path_id"] == "path_previous"
    assert payload["evaluation_task_id"] == "ai_evaluation_007"
    assert payload["path"]["daily_plan"][0]["theme"] == "二叉树递归遍历"
    assert len(payload["path"]["daily_plan"]) == 3
    assert payload["changes"][0]["action"] == "insert_remediation"
    assert payload["changes"][0]["knowledge_point"] == "二叉树递归遍历"
    assert payload["generation_mode"] == "deterministic_fallback"
    assert payload["safety"]["passed"] is True
    valid_ids = {item["chunk_id"] for item in payload["evidence"]}
    assert all(
        set(day["evidence_chunk_ids"]).issubset(valid_ids)
        for day in payload["path"]["daily_plan"]
    )


def test_real_path_replan_shares_trusted_trigger_and_falls_back_after_repeated_safety_feedback(monkeypatch):
    captured_safety_contexts: list[dict[str, object]] = []
    safety_attempts = 0

    class FakeResponse:
        headers = {"x-request-id": "path-replan-safety-revision"}

        def __init__(self, payload: dict[str, object]):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")

    def fake_urlopen(request, timeout):
        nonlocal safety_attempts
        body = json.loads(request.data.decode("utf-8"))
        prompt = body["messages"][1]["content"]
        context = json.loads(body["messages"][2]["content"])
        if prompt.startswith("你是独立的 SafetyAgent"):
            captured_safety_contexts.append(context)
            safety_attempts += 1
            content = {
                "review": {
                    "passed": False,
                    "risk_level": "medium",
                    "issues": ["个性化理由未明确关联可信测验与学习偏好上下文"],
                    "suggestions": ["依据 authoritative_trigger 和 learner_context 修订理由"],
                    "confidence": 0.62,
                }
            }
        else:
            content = {"path": context["baseline"]}
        return FakeResponse(
            {
                "model": "fake-path-replan-model",
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": json.dumps(content, ensure_ascii=False)},
                    }
                ],
                "usage": {"prompt_tokens": 120, "completion_tokens": 80, "total_tokens": 200},
            }
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-path-replan-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(orchestrator.path_generation_pipeline, "llm_gateway", LLMGateway(runtime))

    response = client.post(
        "/path/replan",
        json={
            "course_ids": [1],
            "target": "根据二叉树测验调整后续学习",
            "days": 3,
            "daily_minutes": 30,
            "student_profile": {"resource_preference": ["mindmap"]},
            "current_path": {
                "path_id": "path_before_high_score",
                "path_title": "原学习路径",
                "daily_minutes": 30,
                "daily_plan": [],
            },
            "evaluation": {
                "quiz_id": 8,
                "course_id": 1,
                "overall_score": 100,
                "weak_points": [],
                "mistake_patterns": [],
                "next_actions": ["完成迁移巩固并持续复测"],
                "source_task_id": "ai_evaluation_high_score_008",
            },
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["generation_mode"] == "deterministic_fallback"
    assert payload["model_runtime"]["call_count"] == 4
    assert payload["model_runtime"]["safety_feedback_revision_rounds"] == 1
    assert [call["agent"] for call in payload["model_runtime"]["calls"]] == [
        "PathAgent",
        "SafetyAgent",
        "PathAgent",
        "SafetyAgent",
    ]
    assert payload["safety"]["passed"] is True
    assert any("安全路径" in item for item in payload["safety"]["suggestions"])
    assert len(captured_safety_contexts) == 2
    trusted = captured_safety_contexts[0]["trusted_context"]
    assert trusted["authoritative_trigger"]["overall_score"] == 100
    assert trusted["learner_context"]["latest_quiz_score"] == 100
    assert trusted["learner_context"]["resource_preference"] == ["mindmap"]


def test_path_generation_ignores_profile_weak_points_outside_retrieved_course_evidence():
    response = client.post(
        "/path/generate",
        json={
            "course_ids": [1],
            "target": "巩固二叉树递归遍历",
            "days": 3,
            "daily_minutes": 30,
            "student_profile": {
                "weak_points": ["Cache 映射方式"],
                "resource_preference": ["mindmap"],
                "latest_quiz_score": 100,
            },
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["safety"]["passed"] is True
    assert all(
        "Cache" not in day["theme"]
        and "Cache" not in day["reason"]
        and "Cache" not in day["expected_outcome"]
        for day in payload["path"]["daily_plan"]
    )


def test_behavior_signal_driven_replan_preserves_trigger_and_prioritizes_low_mastery_point():
    response = client.post(
        "/path/replan",
        json={
            "trigger": "behavior_signal",
            "course_ids": [1],
            "target": "根据持续学习行为调整未来三天",
            "days": 3,
            "daily_minutes": 30,
            "student_profile": {"resource_preference": ["mindmap"]},
            "current_path": {
                "path_id": "path_behavior_previous",
                "path_title": "原学习路径",
                "daily_minutes": 40,
                "daily_plan": [],
            },
            "behavior_signal": {
                "trigger_key": "behavior_replan:test:path:v1:signal001",
                "risk_score": 5,
                "reasons": ["最近连续 3 次有效学习证据低于 65 分"],
                "weak_points": ["二叉树递归遍历"],
                "metrics": {"consecutive_low_evidence": 3},
                "pacing": {
                    "action": "reduce",
                    "current_daily_minutes": 40,
                    "recommended_daily_minutes": 30,
                },
            },
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["trigger"] == "behavior_signal"
    assert payload["behavior_trigger_key"] == "behavior_replan:test:path:v1:signal001"
    assert payload["evaluation_task_id"] == ""
    assert payload["previous_path_id"] == "path_behavior_previous"
    assert payload["path"]["daily_plan"][0]["theme"] == "二叉树递归遍历"
    assert payload["path"]["daily_minutes"] == 30
    assert payload["changes"][0]["action"] == "insert_remediation"
    assert payload["safety"]["passed"] is True


def test_real_tutor_agents_retry_invalid_citation_deidentify_question_and_expose_runtime(monkeypatch):
    captured_bodies: list[dict[str, object]] = []
    tutor_attempts = 0

    class FakeResponse:
        headers = {"x-request-id": "tutor-request-id"}

        def __init__(self, payload: dict[str, object]):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")

    def fake_urlopen(request, timeout):
        nonlocal tutor_attempts
        body = json.loads(request.data.decode("utf-8"))
        captured_bodies.append(body)
        prompt = body["messages"][1]["content"]
        context = json.loads(body["messages"][2]["content"])
        if prompt.startswith("你是独立的 SafetyAgent"):
            content = {
                "review": {
                    "passed": True,
                    "risk_level": "low",
                    "issues": [],
                    "suggestions": ["回答片段与课程证据一致"],
                    "confidence": 0.95,
                }
            }
        else:
            tutor_attempts += 1
            answer = context["baseline"]
            if tutor_attempts == 1:
                answer["citations"][0]["evidence_chunk_ids"] = ["invented_chunk"]
            content = {"answer": answer}
        return FakeResponse(
            {
                "model": "fake-tutor-model",
                "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content, ensure_ascii=False)}}],
                "usage": {"prompt_tokens": 120, "completion_tokens": 80, "total_tokens": 200},
            }
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-tutor-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(orchestrator.tutor_generation_pipeline, "llm_gateway", LLMGateway(runtime))

    response = client.post(
        "/tutor/chat",
        json={
            "course_id": 1,
            "question": "我的名字是王小明，邮箱 alice@example.com，前序遍历为什么先访问根节点？",
            "answer_mode": "step_by_step",
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["planned_agents"] == ["KnowledgeAgent", "TutorAgent", "SafetyAgent"]
    assert payload["generation_mode"] == "real_model"
    assert payload["model_runtime"]["call_count"] == 3
    assert payload["model_runtime"]["total_tokens"] == 600
    assert [call["agent"] for call in payload["model_runtime"]["calls"]] == [
        "TutorAgent",
        "TutorAgent",
        "SafetyAgent",
    ]
    assert payload["safety"]["passed"] is True
    assert payload["answer"] == payload["answer_markdown"]
    valid_ids = {item["chunk_id"] for item in payload["evidence"]}
    assert all(
        citation["answer_fragment"] in payload["answer_markdown"]
        and set(citation["evidence_chunk_ids"]).issubset(valid_ids)
        for citation in payload["citations"]
    )
    outbound = json.dumps(captured_bodies, ensure_ascii=False)
    assert "王小明" not in outbound
    assert "alice@example.com" not in outbound


def test_real_tutor_agent_normalizes_grounded_citation_missing_from_answer(monkeypatch):
    tutor_attempts = 0

    class FakeResponse:
        headers = {"x-request-id": "tutor-citation-normalization"}

        def __init__(self, payload: dict[str, object]):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")

    def fake_urlopen(request, timeout):
        nonlocal tutor_attempts
        body = json.loads(request.data.decode("utf-8"))
        prompt = body["messages"][1]["content"]
        context = json.loads(body["messages"][2]["content"])
        if prompt.startswith("你是独立的 SafetyAgent"):
            content = {
                "review": {
                    "passed": True,
                    "risk_level": "low",
                    "issues": [],
                    "suggestions": ["回答与课程证据一致"],
                    "confidence": 0.96,
                }
            }
        else:
            tutor_attempts += 1
            answer = context["baseline"]
            answer["answer_markdown"] = (
                "递归调用栈会按调用顺序压入栈帧，并在返回时按相反顺序弹出。"
                "可以逐层记录参数、返回位置和当前待完成操作来理解这一过程。"
            )
            content = {"answer": answer}
        return FakeResponse(
            {
                "model": "fake-tutor-model",
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": json.dumps(content, ensure_ascii=False)},
                    }
                ],
                "usage": {"prompt_tokens": 120, "completion_tokens": 80, "total_tokens": 200},
            }
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-tutor-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(orchestrator.tutor_generation_pipeline, "llm_gateway", LLMGateway(runtime))

    response = client.post(
        "/tutor/chat",
        json={
            "course_id": 1,
            "question": "递归调用栈如何压栈和返回？",
            "answer_mode": "step_by_step",
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert tutor_attempts == 1
    assert payload["generation_mode"] == "real_model"
    assert payload["model_runtime"]["call_count"] == 2
    assert "**课程证据依据**" in payload["answer_markdown"]
    assert all(
        citation["answer_fragment"] in payload["answer_markdown"]
        for citation in payload["citations"]
    )


def test_real_tutor_agents_revise_answer_after_safety_feedback(monkeypatch):
    captured_contexts: list[dict[str, object]] = []
    safety_attempts = 0

    class FakeResponse:
        headers = {"x-request-id": "tutor-safety-revision"}

        def __init__(self, payload: dict[str, object]):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")

    def fake_urlopen(request, timeout):
        nonlocal safety_attempts
        body = json.loads(request.data.decode("utf-8"))
        prompt = body["messages"][1]["content"]
        context = json.loads(body["messages"][2]["content"])
        captured_contexts.append(context)
        if prompt.startswith("你是独立的 SafetyAgent"):
            safety_attempts += 1
            content = {
                "review": {
                    "passed": safety_attempts > 1,
                    "risk_level": "low" if safety_attempts > 1 else "medium",
                    "issues": [] if safety_attempts > 1 else ["summary 回答过长且引用映射不够直接"],
                    "suggestions": [] if safety_attempts > 1 else ["压缩回答并只保留证据直接支持的片段"],
                    "confidence": 0.96 if safety_attempts > 1 else 0.6,
                }
            }
        elif "safety_feedback" in context:
            evidence = context["evidence"][0]
            excerpt = str(evidence["content"])[:100]
            content = {
                "answer": {
                    "answer_markdown": f"课程证据指出：{excerpt}。其余细节不作推断。",
                    "steps": ["阅读证据摘要"],
                    "citations": [
                        {
                            "answer_fragment": excerpt,
                            "evidence_chunk_ids": [evidence["chunk_id"]],
                        }
                    ],
                    "confidence": 0.82,
                }
            }
        else:
            content = {"answer": context["baseline"]}
        return FakeResponse(
            {
                "model": "fake-tutor-model",
                "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content, ensure_ascii=False)}}],
                "usage": {"prompt_tokens": 60, "completion_tokens": 40, "total_tokens": 100},
            }
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-tutor-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(orchestrator.tutor_generation_pipeline, "llm_gateway", LLMGateway(runtime))

    response = client.post(
        "/tutor/chat",
        json={
            "course_id": 1,
            "question": "请简要解释递归调用栈。",
            "answer_mode": "summary",
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["generation_mode"] == "real_model"
    assert payload["safety"]["passed"] is True
    assert payload["model_runtime"]["call_count"] == 4
    assert payload["model_runtime"]["total_tokens"] == 400
    assert payload["model_runtime"]["safety_feedback_revision_rounds"] == 1
    assert [call["agent"] for call in payload["model_runtime"]["calls"]] == [
        "TutorAgent",
        "SafetyAgent",
        "TutorAgent",
        "SafetyAgent",
    ]
    assert any("safety_feedback" in context for context in captured_contexts)
    assert payload["answer_markdown"].endswith("其余细节不作推断。")


def test_real_tutor_agents_use_evidence_fallback_when_remote_review_keeps_rejecting(monkeypatch):
    class FakeResponse:
        headers = {"x-request-id": "tutor-safe-fallback"}

        def __init__(self, payload: dict[str, object]):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")

    def fake_urlopen(request, timeout):
        body = json.loads(request.data.decode("utf-8"))
        prompt = body["messages"][1]["content"]
        context = json.loads(body["messages"][2]["content"])
        if prompt.startswith("你是独立的 SafetyAgent"):
            content = {
                "review": {
                    "passed": False,
                    "risk_level": "medium",
                    "issues": ["当前回答仍包含证据未直接支持的扩展"],
                    "suggestions": ["只返回证据摘要"],
                    "confidence": 0.6,
                }
            }
        else:
            content = {
                "answer": context.get("previous_answer")
                or context.get("baseline")
            }
        return FakeResponse(
            {
                "model": "fake-tutor-model",
                "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content, ensure_ascii=False)}}],
                "usage": {"prompt_tokens": 60, "completion_tokens": 40, "total_tokens": 100},
            }
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-tutor-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(orchestrator.tutor_generation_pipeline, "llm_gateway", LLMGateway(runtime))

    response = client.post(
        "/tutor/chat",
        json={
            "course_id": 1,
            "question": "请简要解释递归调用栈并给出复杂度。",
            "answer_mode": "summary",
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["generation_mode"] == "deterministic_fallback"
    assert payload["safety"]["passed"] is True
    assert payload["model_runtime"]["real_model_used"] is True
    assert payload["model_runtime"]["call_count"] == 4
    assert payload["model_runtime"]["safety_feedback_revision_rounds"] == 1
    assert "证据未覆盖" in payload["answer_markdown"]
    assert any("自动改用严格受 RAG 证据约束的安全回答" in item for item in payload["safety"]["suggestions"])
    assert all(
        citation["answer_fragment"] in payload["answer_markdown"]
        for citation in payload["citations"]
    )


def test_profile_extract_does_not_fill_demo_defaults_when_message_has_no_evidence():
    response = client.post(
        "/profile/extract",
        json={"student_id": "fresh_student", "message": "我想开始学习这门课", "course_ids": [1]},
    )

    assert response.status_code == 200
    profile = response.json()["profile"]
    assert profile["student_id"] == "fresh_student"
    assert profile["weak_points"] == []
    assert profile["resource_preference"] == []
    assert profile.get("major", "") == ""
    assert profile.get("grade", "") == ""


def test_real_profile_agents_validate_structure_deidentify_context_and_expose_runtime(monkeypatch):
    captured_bodies: list[dict[str, object]] = []
    profile_attempts = 0

    class FakeResponse:
        headers = {"x-request-id": "profile-request-id"}

        def __init__(self, payload: dict[str, object]):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")

    def fake_urlopen(request, timeout):
        nonlocal profile_attempts
        body = json.loads(request.data.decode("utf-8"))
        captured_bodies.append(body)
        prompt = body["messages"][1]["content"]
        context = json.loads(body["messages"][2]["content"])
        if prompt.startswith("你是独立的 SafetyAgent"):
            content = {
                "review": {
                    "passed": True,
                    "risk_level": "low",
                    "issues": [],
                    "suggestions": ["画像增量与学生原话一致"],
                    "confidence": 0.94,
                }
            }
        else:
            profile_attempts += 1
            profile = {**context["baseline"], "learning_goal": "补强递归并通过分步代码练习掌握调用栈"}
            if profile_attempts == 1:
                profile["student_id"] = "model-must-not-return-this-field"
            content = {"profile": profile}
        return FakeResponse(
            {
                "model": "fake-profile-model",
                "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content, ensure_ascii=False)}}],
                "usage": {"prompt_tokens": 120, "completion_tokens": 80, "total_tokens": 200},
            }
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-profile-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(orchestrator.profile_extraction_pipeline, "llm_gateway", LLMGateway(runtime))

    response = client.post(
        "/profile/extract",
        json={
            "student_id": "private-profile-student",
            "message": "我叫王小明，手机号13800138000，邮箱alice@example.com，大二，递归薄弱，喜欢代码，每天30分钟",
            "course_ids": [1],
        },
    )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["profile"]["student_id"] == "private-profile-student"
    assert payload["generation_mode"] == "real_model"
    assert payload["model_runtime"]["call_count"] == 3
    assert payload["model_runtime"]["total_tokens"] == 600
    assert [call["agent"] for call in payload["model_runtime"]["calls"]] == [
        "ProfileAgent",
        "ProfileAgent",
        "SafetyAgent",
    ]
    assert payload["safety"]["passed"] is True
    outbound = json.dumps(captured_bodies, ensure_ascii=False)
    assert "private-profile-student" not in outbound
    assert "王小明" not in outbound
    assert "13800138000" not in outbound
    assert "alice@example.com" not in outbound


def test_ai_task_status_and_stream_endpoints_expose_created_task():
    response = client.post(
        "/resource/generate",
        json={
            "course_id": 2,
            "knowledge_point_ids": [251],
            "resource_types": ["flowchart"],
            "difficulty": "medium",
            "student_profile": {"student_id": "demo_student"},
        },
    )
    assert response.status_code == 200
    task_id = response.json()["task_id"]

    status_response = client.get(f"/tasks/{task_id}")
    assert status_response.status_code == 200
    status_payload = status_response.json()
    assert status_payload["task_id"] == task_id
    assert status_payload["status"] == "success"
    assert status_payload["progress"] == 100
    assert status_payload["planned_agents"][-1] == "SafetyAgent"
    assert status_payload["steps"][0]["agent"] == "ProfileAgent"
    assert status_payload["events"][0]["event"] == "task_created"

    stream_response = client.get(f"/tasks/{task_id}/stream")
    assert stream_response.status_code == 200
    assert "event: task_status" in stream_response.text
    assert "event: agent_step" in stream_response.text
    assert '"status": "running"' in stream_response.text
    assert f'"task_id": "{task_id}"' in stream_response.text


def test_async_resource_task_runs_each_real_model_agent_and_safety_review(monkeypatch):
    captured_bodies: list[dict[str, object]] = []
    resource_attempts = 0
    safety_attempts = 0

    class FakeResponse:
        headers = {"x-request-id": "fake-request-id"}

        def __init__(self, payload: dict[str, object]):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")

    def fake_urlopen(request, timeout):
        nonlocal resource_attempts, safety_attempts
        body = json.loads(request.data.decode("utf-8"))
        captured_bodies.append(body)
        prompt = body["messages"][1]["content"]
        context = json.loads(body["messages"][2]["content"])
        time.sleep(0.01)
        if prompt.startswith("你是 SafetyAgent"):
            safety_attempts += 1
            content = {
                "review": {
                    "passed": safety_attempts > 1,
                    "risk_level": "low" if safety_attempts > 1 else "medium",
                    "issues": [] if safety_attempts > 1 else ["示例答案需要重新核算"],
                    "suggestions": ["已核对证据引用"] if safety_attempts > 1 else ["修订后重新审查"],
                    "confidence": 0.95,
                }
            }
        else:
            resource_attempts += 1
            baseline = context.get("baseline") or context["previous_resource"]
            use_three_evidence = "quality_feedback" in context or (
                "safety_feedback" in context and baseline["resource_type"] == "lecture"
            )
            evidence_ids = [
                item["chunk_id"] for item in context["evidence"][: 3 if use_three_evidence else 2]
            ]
            citation_text = "、".join(f"[{chunk_id}]" for chunk_id in evidence_ids)
            revised_content = (
                "## 真实模型学习资源\n\n"
                "本资源依据课程证据解释概念、执行步骤、边界条件和迁移方法。"
                "学习者应先复述定义，再跟踪中间状态，最后使用反例检验结论。"
                "每一步都应记录输入、输出和判断依据，以便定位概念误解并完成迁移练习。"
                f"关键依据来自 {citation_text}，并通过分层练习巩固理解。"
            )
            if "quality_feedback" in context and baseline["resource_type"] == "quiz":
                revised_content = (
                    "## 分层练习题\n\n"
                    "### 基础题\n问题：核心定义是什么？\n答案：依据课程定义作答。\n解析：先核对概念边界。\n\n"
                    "### 迁移题\n问题：如何迁移到新输入？\n答案：跟踪中间状态。\n解析：比较状态变化。\n\n"
                    "### 综合题\n问题：如何设计边界反例？\n答案：选择最小边界输入。\n解析：逐步验证结论。\n\n"
                    "作答后还应说明每个选项对应的定义、执行规则和边界条件，"
                    "再把错误答案改写为正确表述，以检查学习者是否真正完成知识迁移。\n\n"
                    f"RAG 证据：{citation_text}"
                )
            elif "safety_feedback" in context and baseline["resource_type"] == "lecture":
                revised_content = (
                    "## 学习目标\n掌握课程核心定义。\n\n"
                    "## 分步讲解\n按输入、状态变化和结束条件逐步推演。\n\n"
                    "## 边界例题\n使用最小输入验证边界。\n\n"
                    "完成推演后，学习者需要复述每一步判断依据，比较正常输入与边界输入的状态差异，"
                    "并记录错误规则为什么会导致不同结果，从而形成可以迁移到新问题的稳定方法。\n\n"
                    f"## RAG 证据\n关键结论依据 {citation_text}。"
                )
            content = {
                "resource": {
                    **baseline,
                    "title": f"真实模型生成的{baseline['resource_type']}资源",
                    "content": revised_content,
                    "evidence_chunk_ids": evidence_ids,
                }
            }
            if resource_attempts == 1:
                content["resource"]["evidence_chunk_ids"] = []
            elif resource_attempts == 3:
                for evidence_id in evidence_ids:
                    content["resource"]["content"] = content["resource"]["content"].replace(
                        f"[{evidence_id}]", "课程证据"
                    )
        return FakeResponse(
            {
                "model": "fake-real-model",
                "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content, ensure_ascii=False)}}],
                "usage": {"prompt_tokens": 120, "completion_tokens": 80, "total_tokens": 200},
            }
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    runtime = Settings(
        vector_store_provider="local",
        chroma_url="",
        chroma_collection="",
        llm_provider="deepseek",
        llm_base_url="https://llm.example.test",
        llm_model="fake-real-model",
        llm_api_key="test-key",
        llm_timeout_seconds=2,
        embedding_provider="local",
        embedding_model="local-hash-v1",
        embedding_api_key="",
    )
    monkeypatch.setattr(orchestrator.resource_generation_pipeline, "llm_gateway", LLMGateway(runtime))

    created = client.post(
        "/resource/tasks",
        json={
            "course_id": 1,
            "knowledge_point_ids": [131],
            "resource_types": ["lecture", "quiz"],
            "difficulty": "basic",
            "student_profile": {
                "student_id": "private-student-id",
                "weak_points": ["递归调用栈"],
                "resource_preference": ["lecture", "quiz"],
            },
        },
    )

    assert created.status_code == 200
    assert created.json()["status"] == "pending"
    task_id = created.json()["task_id"]
    snapshot = None
    for _ in range(100):
        snapshot = client.get(f"/tasks/{task_id}").json()
        if snapshot["status"] in {"success", "failed"}:
            break
        time.sleep(0.01)

    assert snapshot is not None
    assert snapshot["status"] == "success", snapshot.get("error")
    assert snapshot["result"]["model_runtime"]["mode"] == "real_model"
    assert snapshot["result"]["model_runtime"]["call_count"] == 9
    assert snapshot["result"]["model_runtime"]["total_tokens"] == 1800
    assert snapshot["result"]["model_runtime"]["quality_revision_rounds"] == 2
    assert snapshot["result"]["model_runtime"]["safety_feedback_revision_rounds"] == 1
    assert snapshot["result"]["model_runtime"]["quality_feedback_revision_rounds"] == 1
    assert snapshot["result"]["model_runtime"]["quality_revised_resource_count"] == 1
    assert len(snapshot["result"]["resources"]) == 2
    assert snapshot["result"]["resources"][0]["title"].startswith("真实模型生成")
    assert all(
        any(f"[{chunk_id}]" in resource["content"] for chunk_id in resource["evidence_chunk_ids"])
        for resource in snapshot["result"]["resources"]
    )
    assert all(
        resource["quality_evaluation"]["gate_passed"] for resource in snapshot["result"]["resources"]
    ), json.dumps(
        [resource["quality_evaluation"] for resource in snapshot["result"]["resources"]],
        ensure_ascii=False,
    )
    quality_contexts = [
        json.loads(body["messages"][2]["content"])
        for body in captured_bodies
        if "quality_feedback" in json.loads(body["messages"][2]["content"])
    ]
    assert len(quality_contexts) == 1
    assert set(quality_contexts[0]["quality_feedback"]["failed_dimensions"]) == {
        "structural_completeness"
    }
    assert any("## RAG 证据索引" in json.dumps(body, ensure_ascii=False) for body in captured_bodies)
    assert any(event["event"] == "agent_step" for event in snapshot["events"])
    assert "private-student-id" not in json.dumps(captured_bodies, ensure_ascii=False)
