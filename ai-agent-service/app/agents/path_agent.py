import re

from app.rag.retriever import RagEvidence
from app.services.personalization import PersonalizationContext


class PathAgent:
    name = "PathAgent"

    def generate(
        self,
        course_ids: list[int],
        target: str,
        days: int,
        daily_minutes: int,
        evidence: list[RagEvidence],
        personalization: PersonalizationContext,
    ) -> dict[str, object]:
        evidence_topics = list(dict.fromkeys(item.knowledge_point for item in evidence))
        grounded_weak_points = [
            point
            for point in personalization.weak_points
            if any(_evidence_supports_point(item, point) for item in evidence)
        ]
        topics = list(dict.fromkeys(grounded_weak_points + evidence_topics))
        if not topics:
            topics = [target.strip() or "课程核心知识点"]
        preferred_type = self._preferred_task_type(personalization)
        daily_plan: list[dict[str, object]] = []
        for day in range(1, days + 1):
            topic = topics[(day - 1) % len(topics)]
            topic_evidence = [item.chunk_id for item in evidence if item.knowledge_point == topic]
            if not topic_evidence:
                topic_evidence = [item.chunk_id for item in evidence]
            is_remedial = day == 1 and (
                personalization.latest_quiz_score is not None
                and personalization.latest_quiz_score < 70
            )
            primary_minutes = max(5, (daily_minutes * 2) // 3)
            quiz_minutes = max(5, daily_minutes - primary_minutes)
            daily_plan.append(
                {
                    "day": day,
                    "theme": topic,
                    "difficulty": personalization.effective_difficulty,
                    "reason": self._reason(topic, preferred_type, personalization, is_remedial),
                    "tasks": [
                        {
                            "type": preferred_type,
                            "resource_id": "res_pending",
                            "title": self._task_title(topic, preferred_type, is_remedial),
                            "estimated_minutes": primary_minutes,
                        },
                        {
                            "type": "quiz",
                            "resource_id": "quiz_pending",
                            "title": f"{topic} 随堂检测",
                            "estimated_minutes": quiz_minutes,
                        },
                    ],
                    "expected_outcome": f"能解释 {topic} 的关键规则，并完成一道迁移练习",
                    "evidence_chunk_ids": list(dict.fromkeys(topic_evidence))[:2],
                }
            )
        return {
            "path_id": "path_ai_local",
            "path_title": self._path_title(days, target),
            "target": target,
            "course_ids": course_ids,
            "daily_minutes": daily_minutes,
            "daily_plan": daily_plan,
            "personalization_summary": personalization.summary,
            "profile_fingerprint": personalization.fingerprint,
            "adjustment_strategy": self._adjustment_strategy(personalization),
            "evidence_chunk_ids": list(dict.fromkeys(item.chunk_id for item in evidence)),
        }

    def _path_title(self, days: int, target: str) -> str:
        normalized = target.strip()
        if re.match(r"^\d+\s*天", normalized):
            return normalized
        return f"{days} 天{normalized}"

    def _preferred_task_type(self, personalization: PersonalizationContext) -> str:
        # animation_script 保留旧接口兼容，但不再进入学生端新路径推荐；
        # 用流程图承接动态过程展示，避免路径出现制作型脚本资源。
        supported = {"lecture", "mindmap", "quiz", "codelab", "flowchart", "reading"}
        return next(
            (item for item in personalization.preferred_resource_types if item in supported),
            "lecture",
        )

    def _task_title(self, topic: str, task_type: str, is_remedial: bool) -> str:
        label = {
            "mindmap": "结构图解",
            "codelab": "代码实验",
            "flowchart": "流程拆解",
            "quiz": "分层练习",
            "reading": "证据阅读",
        }.get(task_type, "RAG 证据讲义")
        prefix = "补救：" if is_remedial else ""
        return f"{prefix}{topic}{label}"

    def _reason(
        self,
        topic: str,
        task_type: str,
        personalization: PersonalizationContext,
        is_remedial: bool,
    ) -> str:
        reason = personalization.reason_for(task_type, topic)
        return f"最近测验未达 70 分，首日先安排补救；{reason}" if is_remedial else reason

    def _adjustment_strategy(self, personalization: PersonalizationContext) -> str:
        if personalization.latest_quiz_score is not None and personalization.latest_quiz_score < 70:
            return (
                f"当前最近测验为 {personalization.latest_quiz_score} 分；先补强薄弱点，"
                "阶段测验达到 70 分后再提升难度，否则继续插入补救资源。"
            )
        if personalization.latest_quiz_score is not None and personalization.latest_quiz_score >= 85:
            return "当前掌握度较好；阶段测验保持 85 分以上时增加综合任务，低于 70 分时回退到基础补救。"
        return "阶段测验低于 70 分时插入补救资源，并根据最新画像的薄弱点和资源偏好重排后续任务。"


def _evidence_supports_point(item: RagEvidence, point: str) -> bool:
    normalized = point.strip().lower()
    if not normalized:
        return False
    evidence_text = "\n".join(
        [item.knowledge_point, item.title, item.content]
    ).lower()
    if normalized in evidence_text:
        return True
    groups = re.findall(r"[a-z0-9]+|[\u4e00-\u9fff]+", normalized)
    return bool(groups) and all(
        group in evidence_text
        if re.fullmatch(r"[a-z0-9]+", group) or len(group) <= 2
        else any(group[index : index + 2] in evidence_text for index in range(len(group) - 1))
        for group in groups
    )
