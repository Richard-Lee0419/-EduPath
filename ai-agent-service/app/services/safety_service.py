from app.agents.safety_agent import SafetyAgent
from app.rag.retriever import RagEvidence
from app.schemas.resource_schema import SafetyReview


class SafetyService:
    """Safety service boundary."""

    def __init__(self, safety_agent: SafetyAgent | None = None) -> None:
        self.safety_agent = safety_agent or SafetyAgent()

    def review(
        self,
        output_text: str,
        evidence: list[RagEvidence],
        require_evidence: bool = True,
    ) -> SafetyReview:
        return self.safety_agent.review(output_text, evidence, require_evidence=require_evidence)
