from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import re
from typing import Any


_RESOURCE_ALIASES = {
    "code": "codelab",
    "experiment": "codelab",
    "animation": "animation_script",
    "visual": "mindmap",
    "summary": "lecture",
}
_LEVEL_RANK = {"beginner": 0, "medium": 1, "advanced": 2}
_DIFFICULTIES = ("basic", "medium", "advanced")


@dataclass(frozen=True)
class PersonalizationContext:
    student_id: str
    learning_goal: str
    weak_points: list[str]
    mistake_patterns: list[str]
    preferences: list[str]
    cognitive_styles: list[str]
    knowledge_levels: dict[str, str]
    latest_quiz_score: int | None
    effective_difficulty: str
    preferred_resource_types: list[str]
    estimated_minutes: int
    summary: str
    fingerprint: str

    @classmethod
    def from_profile(
        cls,
        profile: dict[str, Any] | None,
        requested_difficulty: str,
        daily_minutes: int | None = None,
    ) -> "PersonalizationContext":
        source = profile if isinstance(profile, dict) else {}
        weak_points = _strings(source.get("weak_points"))
        mistake_patterns = _strings(source.get("mistake_patterns"))
        preferences = [_RESOURCE_ALIASES.get(item, item) for item in _strings(source.get("resource_preference"))]
        preferences = _unique(preferences)
        cognitive_styles = _strings(source.get("cognitive_style"))
        knowledge_levels = {
            str(key): str(value)
            for key, value in (source.get("knowledge_base") or {}).items()
            if str(value) in _LEVEL_RANK
        } if isinstance(source.get("knowledge_base"), dict) else {}
        latest_quiz_score = _score(source.get("latest_quiz_score"))
        effective_difficulty = _difficulty(requested_difficulty, latest_quiz_score, knowledge_levels)
        time_budget = _time_budget(daily_minutes, source.get("learning_pace"))
        estimated_minutes = max(10, min(60, time_budget // 2))
        goal = _text(source.get("learning_goal"))
        summary = _summary(goal, weak_points, preferences, cognitive_styles, latest_quiz_score)
        fingerprint_payload = {
            "goal": goal,
            "weak_points": weak_points,
            "mistake_patterns": mistake_patterns,
            "preferences": preferences,
            "cognitive_styles": cognitive_styles,
            "knowledge_levels": knowledge_levels,
            "latest_quiz_score": latest_quiz_score,
            "difficulty": effective_difficulty,
            "time_budget": time_budget,
        }
        fingerprint = hashlib.sha256(
            json.dumps(fingerprint_payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
        ).hexdigest()[:16]
        return cls(
            student_id=_text(source.get("student_id")),
            learning_goal=goal,
            weak_points=weak_points,
            mistake_patterns=mistake_patterns,
            preferences=preferences,
            cognitive_styles=cognitive_styles,
            knowledge_levels=knowledge_levels,
            latest_quiz_score=latest_quiz_score,
            effective_difficulty=effective_difficulty,
            preferred_resource_types=preferences,
            estimated_minutes=estimated_minutes,
            summary=summary,
            fingerprint=fingerprint,
        )

    def order_resource_types(self, requested: list[str]) -> list[str]:
        requested_unique = _unique(requested)
        preferred = [item for item in self.preferred_resource_types if item in requested_unique]
        return preferred + [item for item in requested_unique if item not in preferred]

    def reason_for(self, resource_type: str, topic: str) -> str:
        parts: list[str] = []
        if self.weak_points:
            matched = [point for point in self.weak_points if point in topic or topic in point]
            parts.append(f"优先补强{('、'.join(matched or self.weak_points[:2]))}")
        if resource_type in self.preferences:
            parts.append(f"匹配你偏好的{_resource_label(resource_type)}形式")
        if self.latest_quiz_score is not None:
            parts.append(f"最近测验 {self.latest_quiz_score} 分，采用{_difficulty_label(self.effective_difficulty)}梯度")
        elif self.knowledge_levels:
            parts.append(f"依据当前知识基础采用{_difficulty_label(self.effective_difficulty)}梯度")
        if self.learning_goal:
            parts.append(f"服务于目标“{self.learning_goal}”")
        if not parts:
            return f"依据当前请求与课程证据生成 {topic} 的{_resource_label(resource_type)}"
        return "；".join(parts) + "。"

    def learning_instruction(self) -> str:
        instructions: list[str] = []
        if "visual" in self.cognitive_styles or "mindmap" in self.preferences:
            instructions.append("先用结构图或状态关系建立整体认识")
        if "step_by_step" in self.cognitive_styles:
            instructions.append("把过程拆成可检查的小步骤")
        if "example_first" in self.cognitive_styles or "codelab" in self.preferences:
            instructions.append("先给最小示例再归纳规则")
        if self.mistake_patterns:
            instructions.append(f"针对错因{('、'.join(self.mistake_patterns[:2]))}设置反例")
        return "；".join(instructions) or "按定义、过程、边界和迁移练习组织内容"


def _strings(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return _unique([str(item).strip() for item in value if str(item).strip()])


def _unique(values: list[str]) -> list[str]:
    return list(dict.fromkeys(values))


def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def _score(value: Any) -> int | None:
    try:
        score = int(float(value))
    except (TypeError, ValueError):
        return None
    return max(0, min(100, score))


def _difficulty(requested: str, quiz_score: int | None, levels: dict[str, str]) -> str:
    normalized = requested if requested in _DIFFICULTIES else "medium"
    if quiz_score is not None:
        if quiz_score < 60:
            return "basic"
        if quiz_score >= 85:
            return "advanced"
        return "medium"
    if levels:
        lowest = min((_LEVEL_RANK[value] for value in levels.values()), default=1)
        highest = max((_LEVEL_RANK[value] for value in levels.values()), default=1)
        if lowest == 0:
            return "basic"
        if highest == 2 and lowest == 2:
            return "advanced"
    return normalized


def _time_budget(explicit: int | None, learning_pace: Any) -> int:
    if explicit is not None:
        return max(10, min(180, explicit))
    match = re.search(r"(\d{1,3})\s*分钟", _text(learning_pace))
    return max(10, min(180, int(match.group(1)))) if match else 40


def _summary(
    goal: str,
    weak_points: list[str],
    preferences: list[str],
    styles: list[str],
    score: int | None,
) -> str:
    facts: list[str] = []
    if weak_points:
        facts.append(f"优先处理{('、'.join(weak_points[:2]))}")
    if preferences:
        facts.append(f"偏好{('、'.join(_resource_label(item) for item in preferences[:2]))}")
    if styles:
        facts.append(f"认知方式{('、'.join(styles[:2]))}")
    if score is not None:
        facts.append(f"最近测验{score}分")
    if goal:
        facts.append(f"目标为{goal}")
    return "；".join(facts) if facts else "当前画像信息不足，按请求目标和课程证据生成"


def _resource_label(resource_type: str) -> str:
    return {
        "lecture": "讲义",
        "mindmap": "思维导图",
        "quiz": "练习题",
        "codelab": "代码实验",
        "animation_script": "动画脚本",
        "flowchart": "流程图",
        "reading": "拓展阅读",
    }.get(resource_type, resource_type)


def _difficulty_label(difficulty: str) -> str:
    return {"basic": "基础", "medium": "中等", "advanced": "进阶"}.get(difficulty, difficulty)
