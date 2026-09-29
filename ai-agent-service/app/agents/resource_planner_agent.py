from app.services.personalization import PersonalizationContext


class ResourcePlannerAgent:
    """Resource planning agent boundary."""

    _type_agent_map = {
        "lecture": "LectureAgent",
        "mindmap": "MindmapAgent",
        "quiz": "QuizAgent",
        "codelab": "CodelabAgent",
        "animation_script": "AnimationScriptAgent",
        "flowchart": "MindmapAgent",
        "reading": "LectureAgent",
    }

    def plan(self, resource_types: list[str]) -> list[str]:
        agents = ["ProfileAgent", "KnowledgeAgent", "ResourcePlannerAgent"]
        for resource_type in resource_types:
            agent = self._type_agent_map.get(resource_type, "LectureAgent")
            if agent not in agents:
                agents.append(agent)
        agents.append("SafetyAgent")
        return agents

    def order(self, resource_types: list[str], personalization: PersonalizationContext) -> list[str]:
        return personalization.order_resource_types(resource_types)
