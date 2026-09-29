from __future__ import annotations

from typing import Any

from app.agents.animation_script_agent import AnimationScriptAgent
from app.agents.codelab_agent import CodelabAgent
from app.agents.evaluation_agent import EvaluationAgent
from app.agents.knowledge_agent import KnowledgeAgent
from app.agents.lecture_agent import LectureAgent
from app.agents.mindmap_agent import MindmapAgent
from app.agents.path_agent import PathAgent
from app.agents.profile_agent import ProfileAgent
from app.agents.quiz_agent import QuizAgent
from app.agents.resource_planner_agent import ResourcePlannerAgent
from app.agents.safety_agent import SafetyAgent
from app.agents.tutor_agent import TutorAgent
from app.rag.retriever import RagEvidence
from app.schemas.path_schema import (
    PathGenerateRequest,
    PathGenerateResponse,
    PathReplanRequest,
    PathReplanResponse,
)
from app.schemas.profile_schema import ProfileExtractRequest, ProfileExtractResponse
from app.schemas.quiz_schema import QuizGenerateRequest, QuizGenerateResponse
from app.schemas.evaluation_schema import EvaluationAnalyzeRequest, EvaluationAnalyzeResponse
from app.schemas.resource_schema import (
    GeneratedResource,
    ResourceGenerateRequest,
    ResourceGenerateResponse,
    ResourceRepairRequest,
    ResourceRepairResponse,
    ResourceTaskCreated,
)
from app.schemas.tutor_schema import TutorChatRequest, TutorChatResponse
from app.services.task_manager import task_manager
from app.services.personalization import PersonalizationContext
from app.services.llm_gateway import LLMGateway
from app.services.path_generation_pipeline import PathGenerationPipeline
from app.services.profile_extraction_pipeline import ProfileExtractionPipeline
from app.services.quiz_generation_pipeline import QuizGenerationPipeline
from app.services.resource_generation_pipeline import ResourceGenerationPipeline
from app.services.tutor_generation_pipeline import TutorGenerationPipeline
from app.services.evaluation_pipeline import EvaluationPipeline


class OrchestratorAgent:
    """Top-level multi-agent orchestration boundary."""

    def __init__(self) -> None:
        self.profile_agent = ProfileAgent()
        self.knowledge_agent = KnowledgeAgent()
        self.resource_planner_agent = ResourcePlannerAgent()
        self.lecture_agent = LectureAgent()
        self.mindmap_agent = MindmapAgent()
        self.quiz_agent = QuizAgent()
        self.codelab_agent = CodelabAgent()
        self.animation_script_agent = AnimationScriptAgent()
        self.path_agent = PathAgent()
        self.tutor_agent = TutorAgent()
        self.evaluation_agent = EvaluationAgent()
        self.safety_agent = SafetyAgent()
        self.llm_gateway = LLMGateway()
        self.profile_extraction_pipeline = ProfileExtractionPipeline(
            profile_agent=self.profile_agent,
            safety_agent=self.safety_agent,
            llm_gateway=self.llm_gateway,
        )
        self.path_generation_pipeline = PathGenerationPipeline(
            knowledge_agent=self.knowledge_agent,
            path_agent=self.path_agent,
            safety_agent=self.safety_agent,
            llm_gateway=self.llm_gateway,
        )
        self.resource_generation_pipeline = ResourceGenerationPipeline(
            knowledge_agent=self.knowledge_agent,
            planner_agent=self.resource_planner_agent,
            safety_agent=self.safety_agent,
            llm_gateway=self.llm_gateway,
            baseline_generator=self._generate_resource,
        )
        self.tutor_generation_pipeline = TutorGenerationPipeline(
            knowledge_agent=self.knowledge_agent,
            tutor_agent=self.tutor_agent,
            safety_agent=self.safety_agent,
            llm_gateway=self.llm_gateway,
        )
        self.quiz_generation_pipeline = QuizGenerationPipeline(
            knowledge_agent=self.knowledge_agent,
            quiz_agent=self.quiz_agent,
            safety_agent=self.safety_agent,
            llm_gateway=self.llm_gateway,
        )
        self.evaluation_pipeline = EvaluationPipeline(
            knowledge_agent=self.knowledge_agent,
            evaluation_agent=self.evaluation_agent,
            safety_agent=self.safety_agent,
            llm_gateway=self.llm_gateway,
        )

    def generate_resource(self, request: ResourceGenerateRequest) -> ResourceGenerateResponse:
        return self.resource_generation_pipeline.generate(request)

    def start_resource_task(self, request: ResourceGenerateRequest) -> ResourceTaskCreated:
        return self.resource_generation_pipeline.start(request)

    def repair_resource(self, request: ResourceRepairRequest) -> ResourceRepairResponse:
        return self.resource_generation_pipeline.repair(request)

    def search_knowledge(
        self,
        course_id: int,
        query: str,
        top_k: int,
        knowledge_point_ids: list[int] | None = None,
    ) -> list[RagEvidence]:
        return self.knowledge_agent.retrieve(course_id, query, top_k, knowledge_point_ids)

    def ingest_knowledge_document(self, request: Any, text: str) -> dict[str, object]:
        planned_agents = ["DocumentParser", "Chunker", "EmbeddingAgent", "KnowledgeAgent", "SafetyAgent"]
        course_code = _course_code(request.course_id)
        knowledge_point = request.knowledge_point or request.filename.rsplit(".", 1)[0]
        chunks = self.knowledge_agent.ingest_uploaded_document(
            document_id=request.document_id,
            course_id=request.course_id,
            course_code=course_code,
            knowledge_point_id=request.knowledge_point_id or request.document_id,
            knowledge_point=knowledge_point,
            title=request.filename,
            content=text,
            source=request.source or f"{course_code}/{request.filename}",
        )
        safety = self.safety_agent.review(text, chunks, require_evidence=False)
        task = task_manager.create_task(
            "ai_kb_ingest",
            planned_agents,
            {
                "document_id": request.document_id,
                "chunk_count": len(chunks),
                "safety_status": "passed" if safety.passed else "review_required",
            },
        )
        return {
            "task_id": task["task_id"],
            "task": task,
            "document_id": request.document_id,
            "parse_status": "parsed",
            "index_status": "indexed",
            "chunks": [self._knowledge_evidence(item) for item in chunks],
            "safety": safety.model_dump(),
        }

    def extract_profile(self, request: ProfileExtractRequest) -> ProfileExtractResponse:
        return self.profile_extraction_pipeline.extract(request)

    def generate_path(self, request: PathGenerateRequest) -> PathGenerateResponse:
        return self.path_generation_pipeline.generate(request)

    def replan_path(self, request: PathReplanRequest) -> PathReplanResponse:
        return self.path_generation_pipeline.replan(request)

    def chat_tutor(self, request: TutorChatRequest) -> TutorChatResponse:
        return self.tutor_generation_pipeline.answer(request)

    def generate_quiz(self, request: QuizGenerateRequest) -> QuizGenerateResponse:
        return self.quiz_generation_pipeline.generate(request)

    def analyze_evaluation(self, request: EvaluationAnalyzeRequest) -> EvaluationAnalyzeResponse:
        return self.evaluation_pipeline.analyze(request)

    def evaluation_report(self) -> dict[str, object]:
        return self.evaluation_agent.report()

    def _generate_resource(
        self,
        resource_type: str,
        topic: str,
        difficulty: str,
        knowledge_points: list[str],
        evidence: list[RagEvidence],
        personalization: PersonalizationContext,
    ) -> GeneratedResource:
        if resource_type in ("lecture", "reading"):
            return self.lecture_agent.generate(resource_type, topic, difficulty, knowledge_points, evidence, personalization)
        if resource_type in ("mindmap", "flowchart"):
            return self.mindmap_agent.generate(resource_type, topic, difficulty, knowledge_points, evidence, personalization)
        if resource_type == "quiz":
            return self.quiz_agent.generate(topic, difficulty, knowledge_points, evidence, personalization)
        if resource_type == "codelab":
            return self.codelab_agent.generate(topic, difficulty, knowledge_points, evidence, personalization)
        if resource_type == "animation_script":
            return self.animation_script_agent.generate(topic, difficulty, knowledge_points, evidence, personalization)
        return self.lecture_agent.generate("lecture", topic, difficulty, knowledge_points, evidence, personalization)

    def _knowledge_evidence(self, item: RagEvidence) -> dict[str, object]:
        return {
            "chunk_id": item.chunk_id,
            "course_id": item.course_id,
            "knowledge_point_id": item.knowledge_point_id,
            "knowledge_point": item.knowledge_point,
            "related_knowledge_point_ids": list(item.related_knowledge_point_ids),
            "title": item.title,
            "content": item.content,
            "score": item.score,
            "source": item.source,
            "author": item.author,
            "source_url": item.source_url,
            "license": item.license,
            "license_status": item.license_status,
            "document_type": item.document_type,
            "contains_examples": item.contains_examples,
            "heading_path": list(item.heading_path),
        }


orchestrator = OrchestratorAgent()


def _course_code(course_id: int) -> str:
    return {
        1: "data_structures_algorithms",
        2: "computer_organization",
    }.get(course_id, f"course_{course_id}")
