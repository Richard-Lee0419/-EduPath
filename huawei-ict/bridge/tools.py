"""MCP tool implementations exposed to Huawei Cloud AgentArts.

Every tool returns plain JSON-serialisable data.  Two execution sources are
supported and always reported in ``mode``:

* ``edupath_ai_service`` – proxy to the existing EduPath FastAPI service when
  ``EDUPATH_AI_BASE_URL`` is configured.
* ``demo_fixture`` – deterministic, course-grounded fixtures used offline.

A failed proxy call falls back to fixtures with ``mode=live_fallback`` and a
``fallback_reason``; the bridge never presents fixture output as model output.
"""

from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.request
from typing import Any

from . import demo_data

DEFAULT_TOP_K = 4
MAX_TOP_K = 8


def _ai_base() -> str:
    return os.getenv("EDUPATH_AI_BASE_URL", "").rstrip("/")


def _proxy_enabled() -> bool:
    return bool(_ai_base()) and os.getenv("EDUPATH_BRIDGE_MODE", "auto").lower() != "fixture"


def _request(method: str, path: str, payload: dict[str, Any] | None = None, timeout: float = 45.0) -> dict[str, Any]:
    url = f"{_ai_base()}{path}"
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - configured internal URL
        raw = response.read().decode("utf-8")
    parsed = json.loads(raw)
    if isinstance(parsed, dict) and "data" in parsed and "code" in parsed:
        return parsed["data"]
    return parsed


def _try_proxy(path: str, payload: dict[str, Any], timeout: float = 45.0) -> tuple[dict[str, Any] | None, str]:
    if not _proxy_enabled():
        return None, "EDUPATH_AI_BASE_URL is not configured"
    try:
        return _request("POST", path, payload, timeout=timeout), ""
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
        return None, f"{type(exc).__name__}: {exc}"


def _clip(text: str, limit: int = 240) -> str:
    text = re.sub(r"\s+", " ", text).strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _score_evidence(query: str, item: dict[str, Any]) -> float:
    """A small lexical score used only for the offline fixture path."""

    haystack = f"{item['title']} {item['knowledge_point']} {item['content']}"
    terms = [term for term in re.split(r"[\s,，。；;、/]+", query) if len(term) >= 2]
    bonus = 0.0
    for term in terms:
        if term in haystack:
            bonus += 0.03
    for keyword in ("二叉树", "遍历", "递归", "调用栈", "中序", "前序", "后序", "DFS"):
        if keyword in query and keyword in haystack:
            bonus += 0.02
    return round(min(item["score"] + bonus, 0.99), 2)


# ---------------------------------------------------------------------------
# Tool 1: learner profile
# ---------------------------------------------------------------------------


def get_profile(student_id: str = demo_data.DEMO_STUDENT_ID, message: str = "") -> dict[str, Any]:
    proxied, reason = _try_proxy("/profile/extract", {"student_id": student_id, "message": message, "course_ids": [1]})
    if proxied:
        return {
            "mode": "edupath_ai_service",
            "student_id": student_id,
            "profile": proxied.get("profile", demo_data.DEMO_PROFILE),
            "confidence": proxied.get("confidence", 0.0),
            "dimensions": proxied.get("dimensions", []),
            "safety": proxied.get("safety"),
        }
    return {
        "mode": "demo_fixture",
        "student_id": student_id,
        "profile": demo_data.DEMO_PROFILE,
        "confidence": demo_data.DEMO_PROFILE["confidence_score"],
        "dimensions": ["学习目标", "知识基础", "薄弱点", "资源偏好", "认知风格", "学习节奏"],
        "fallback_reason": reason,
    }


# ---------------------------------------------------------------------------
# Tool 2: course knowledge search
# ---------------------------------------------------------------------------


def search_knowledge(query: str, course_id: int = 1, top_k: int = DEFAULT_TOP_K) -> dict[str, Any]:
    top_k = max(1, min(int(top_k), MAX_TOP_K))
    proxied, reason = _try_proxy("/kb/search", {"course_id": course_id, "query": query, "top_k": top_k})
    if proxied and isinstance(proxied.get("results"), list):
        results = [
            {
                "chunk_id": item.get("chunk_id", ""),
                "title": item.get("title", ""),
                "knowledge_point": item.get("knowledge_point", ""),
                "content": _clip(item.get("content", ""), 420),
                "score": item.get("score", 0.0),
                "source": item.get("source", ""),
                "license": item.get("license", ""),
            }
            for item in proxied["results"]
        ]
        return {"mode": "edupath_ai_service", "query": query, "course_id": course_id, "results": results, "corpus": proxied.get("corpus", {})}

    pool = [item for item in demo_data.EVIDENCE if item["course_id"] == course_id] or demo_data.EVIDENCE
    ranked = sorted(pool, key=lambda item: _score_evidence(query, item), reverse=True)[:top_k]
    results = [
        {
            "chunk_id": item["chunk_id"],
            "title": item["title"],
            "knowledge_point": item["knowledge_point"],
            "content": _clip(item["content"], 420),
            "score": _score_evidence(query, item),
            "source": item["source"],
            "source_url": item["source_url"],
            "license": item["license"],
            "attribution": item["attribution"],
        }
        for item in ranked
    ]
    return {
        "mode": "demo_fixture",
        "query": query,
        "course_id": course_id,
        "results": results,
        "corpus": {"documents": 108, "chunks": 1200, "courses": 2, "note": "公开语料清单见 docs/03；切片数为约数"},
        "fallback_reason": reason,
    }


# ---------------------------------------------------------------------------
# Tool 3: resource bundle planning
# ---------------------------------------------------------------------------


def plan_resource_bundle(
    goal: str,
    weak_points: list[str] | None = None,
    resource_types: list[str] | None = None,
    difficulty: str = "medium",
) -> dict[str, Any]:
    weak_points = weak_points or demo_data.DEMO_PROFILE["weak_points"]
    requested = resource_types or ["lecture", "mindmap", "quiz"]
    allowed = {"lecture", "mindmap", "quiz", "codelab", "reading", "flowchart"}
    chosen = [item for item in requested if item in allowed] or ["lecture"]
    return {
        "mode": "demo_fixture",
        "goal": goal,
        "difficulty": difficulty,
        "resource_types": chosen,
        "rationale": "先用图解降低递归调用栈的认知负担，再用三道题验证边界条件；资源量控制在 30 分钟内。",
        "target_weak_points": weak_points[:3],
        "estimated_minutes": 12 + 8 + 10,
        "success_criteria": ["能口述前/中/后序差别", "能写出带空节点判断的递归函数", "3 题中至少答对 2 题"],
    }


# ---------------------------------------------------------------------------
# Tool 4: resource generation
# ---------------------------------------------------------------------------


def _lecture_resource() -> dict[str, Any]:
    return {
        "title": demo_data.LECTURE_TITLE,
        "resource_type": "lecture",
        "content_format": "markdown",
        "content": demo_data.LECTURE_MARKDOWN,
        "summary": "用“叠盘子”类比解释递归调用栈，对比三种遍历，并给出空节点与顺序混淆的纠错练习。",
        "difficulty": "basic",
        "knowledge_points": ["二叉树遍历", "递归与边界"],
        "personalized_reason": "命中你的 3 个薄弱点：递归调用栈、边界条件、遍历顺序混淆。",
        "estimated_minutes": 12,
        "evidence_chunk_ids": ["E1", "E2", "E3"],
    }


def _mindmap_resource() -> dict[str, Any]:
    return {
        "title": "二叉树遍历知识图谱",
        "resource_type": "mindmap",
        "content_format": "json",
        "content": demo_data.MINDMAP,
        "summary": "四种遍历方式、递归三要素、复杂度与易错点的结构化图谱。",
        "difficulty": "basic",
        "knowledge_points": ["二叉树遍历", "复杂度"],
        "personalized_reason": "你偏好视觉化材料，导图用于课前 3 分钟建立全局图式。",
        "estimated_minutes": 8,
        "evidence_chunk_ids": ["E1", "E2"],
    }


def _quiz_resource() -> dict[str, Any]:
    return {
        "title": demo_data.QUIZ["title"],
        "resource_type": "quiz",
        "content_format": "json",
        "content": demo_data.QUIZ,
        "summary": "3 道单选题，覆盖前序输出、BST 中序性质与递归出口，附解析和证据。",
        "difficulty": "medium",
        "knowledge_points": ["二叉树遍历", "递归与边界"],
        "personalized_reason": "针对你上次“中序/前序混淆”和“空节点未处理”的两类错误。",
        "estimated_minutes": 10,
        "evidence_chunk_ids": ["E1", "E2", "E5"],
    }


def _codelab_resource() -> dict[str, Any]:
    return {
        "title": "代码实验：三种遍历只改一行",
        "resource_type": "codelab",
        "content_format": "markdown",
        "content": (
            "## 任务\n\n"
            "在 `traverse(root, order)` 中只移动 `result.append(root.val)` 的位置，分别得到前序、中序、后序。\n\n"
            "```python\n"
            "def traverse(root, order, result=None):\n"
            "    if result is None:\n"
            "        result = []\n"
            "    if root is None:\n"
            "        return result\n"
            "    if order == 'pre':\n"
            "        result.append(root.val)\n"
            "    traverse(root.left, order, result)\n"
            "    if order == 'in':\n"
            "        result.append(root.val)\n"
            "    traverse(root.right, order, result)\n"
            "    if order == 'post':\n"
            "        result.append(root.val)\n"
            "    return result\n"
            "```\n\n"
            "## 验收\n\n用 `1(2,3)` 手推三种输出，并解释为什么空节点判断必须在第一行。"
        ),
        "summary": "用同一段骨架改写三种遍历，把“访问根的位置”变成可操作的变量。",
        "difficulty": "medium",
        "knowledge_points": ["二叉树遍历"],
        "personalized_reason": "你偏好可运行代码，用小实验替代纯阅读。",
        "estimated_minutes": 15,
        "evidence_chunk_ids": ["E1", "E2"],
    }


def generate_learning_resource(
    course_id: int = 1,
    knowledge_point: str = "二叉树遍历",
    resource_types: list[str] | None = None,
    difficulty: str = "medium",
    student_profile: dict[str, Any] | None = None,
) -> dict[str, Any]:
    requested = resource_types or ["lecture", "mindmap", "quiz"]
    proxied, reason = _try_proxy(
        "/resource/generate",
        {
            "course_id": course_id,
            "knowledge_points": [knowledge_point],
            "goal": (student_profile or {}).get("learning_goal", ""),
            "resource_types": [item for item in requested if item in {"lecture", "mindmap", "quiz", "codelab", "reading", "flowchart"}],
            "difficulty": difficulty,
            "student_profile": student_profile or demo_data.DEMO_PROFILE,
        },
        timeout=120.0,
    )
    if proxied and isinstance(proxied.get("resources"), list):
        return {
            "mode": "edupath_ai_service",
            "course_id": course_id,
            "knowledge_point": knowledge_point,
            "resources": proxied["resources"],
            "evidence": proxied.get("evidence", []),
            "safety": proxied.get("safety", demo_data.SAFETY),
            "quality": proxied.get("quality") or demo_data.QUALITY,
            "model_runtime": proxied.get("model_runtime", {}),
        }

    builders = {
        "lecture": _lecture_resource,
        "mindmap": _mindmap_resource,
        "quiz": _quiz_resource,
        "codelab": _codelab_resource,
    }
    resources = []
    for resource_type in requested:
        builder = builders.get(resource_type)
        if builder:
            resources.append(builder())
    evidence = [item for item in demo_data.EVIDENCE if item["chunk_id"] in {"E1", "E2", "E3", "E5"}]
    return {
        "mode": "demo_fixture",
        "course_id": course_id,
        "knowledge_point": knowledge_point,
        "resources": resources,
        "evidence": evidence,
        "safety": demo_data.SAFETY,
        "quality": demo_data.QUALITY,
        "model_runtime": {
            "mode": "demo_fixture",
            "provider": "local",
            "model": "fixture",
            "call_count": 0,
            "total_tokens": 0,
            "note": "未配置真实模型；内容来自课程证据约束的演示样例",
        },
        "fallback_reason": reason,
    }


# ---------------------------------------------------------------------------
# Tool 5: learning path
# ---------------------------------------------------------------------------


def plan_learning_path(
    course_id: int = 1,
    target: str = "",
    days: int = 3,
    daily_minutes: int = 30,
    student_profile: dict[str, Any] | None = None,
) -> dict[str, Any]:
    days = max(1, min(int(days), 7))
    proxied, reason = _try_proxy(
        "/path/generate",
        {
            "course_ids": [course_id],
            "target": target or demo_data.DEMO_PROFILE["learning_goal"],
            "days": days,
            "daily_minutes": daily_minutes,
            "student_profile": student_profile or demo_data.DEMO_PROFILE,
        },
        timeout=90.0,
    )
    if proxied and isinstance(proxied.get("path"), dict):
        return {"mode": "edupath_ai_service", "path": proxied["path"], "evidence": proxied.get("evidence", []), "safety": proxied.get("safety")}

    path = dict(demo_data.PATH)
    path["daily_plan"] = demo_data.PATH["days"][:days]
    path["target"] = target or demo_data.DEMO_PROFILE["learning_goal"]
    path["daily_minutes"] = daily_minutes
    return {"mode": "demo_fixture", "path": path, "evidence": demo_data.EVIDENCE[:3], "fallback_reason": reason}


# ---------------------------------------------------------------------------
# Tool 6: evidence-grounded tutoring
# ---------------------------------------------------------------------------


def answer_with_evidence(question: str, course_id: int = 1, answer_mode: str = "step_by_step") -> dict[str, Any]:
    proxied, reason = _try_proxy(
        "/tutor/chat",
        {"course_id": course_id, "question": question, "answer_mode": answer_mode},
        timeout=60.0,
    )
    if proxied and proxied.get("answer_markdown"):
        return {
            "mode": "edupath_ai_service",
            "answer_markdown": proxied["answer_markdown"],
            "steps": proxied.get("steps", []),
            "citations": proxied.get("citations", []),
            "evidence": proxied.get("evidence", []),
            "confidence": proxied.get("confidence", 0.0),
        }

    answer = (
        "先用一句话回答：**递归遍历的关键不是背三种顺序，而是决定“访问根”发生在递归的哪一步。**\n\n"
        "1. 前序：进入节点就先记录，再处理左右子树 [E1]。\n"
        "2. 中序：先处理左子树，再记录根，最后右子树；在 BST 上会得到递增序列 [E1][E5]。\n"
        "3. 后序：左右都处理完才记录根，适合释放或自底向上聚合 [E1]。\n\n"
        "你提到的“调用栈绕晕”，可以把它想成叠盘子：每进入一层就放一个盘子，返回时取走一个。"
        "递归深度等于树高，退化树最坏是 O(n)，这时应改用显式栈 [E3]。\n\n"
        "> 下一步：用三节点树 `1(2,3)` 手推三种输出，再把结果发给我，我按“概念—过程—边界”给你反馈。"
    )
    return {
        "mode": "demo_fixture",
        "answer_markdown": answer,
        "steps": ["定位问题：递归调用栈与遍历顺序", "检索课程证据 E1/E3/E5", "用类比解释", "给出可操作的下一步"],
        "citations": [
            {"answer_fragment": "前序/中序/后序的访问时机", "evidence_chunk_ids": ["E1"]},
            {"answer_fragment": "递归栈深度与退化树", "evidence_chunk_ids": ["E3"]},
        ],
        "evidence": [item for item in demo_data.EVIDENCE if item["chunk_id"] in {"E1", "E3", "E5"}],
        "confidence": 0.87,
        "fallback_reason": reason,
    }


# ---------------------------------------------------------------------------
# Tool 7/8: quiz + evaluation
# ---------------------------------------------------------------------------


def generate_quiz(
    course_id: int = 1,
    knowledge_point: str = "二叉树遍历",
    question_count: int = 3,
    difficulty: str = "basic",
) -> dict[str, Any]:
    proxied, reason = _try_proxy(
        "/quiz/generate",
        {
            "course_id": course_id,
            "knowledge_points": [knowledge_point],
            "difficulty": difficulty,
            "question_count": max(1, min(int(question_count), 3)),
            "student_profile": demo_data.DEMO_PROFILE,
        },
        timeout=60.0,
    )
    if proxied and isinstance(proxied.get("quiz"), dict):
        return {"mode": "edupath_ai_service", "quiz": proxied["quiz"], "evidence": proxied.get("evidence", []), "safety": proxied.get("safety")}
    quiz = dict(demo_data.QUIZ)
    quiz["questions"] = demo_data.QUIZ["questions"][: max(1, min(int(question_count), 3))]
    return {"mode": "demo_fixture", "quiz": quiz, "evidence": demo_data.EVIDENCE[:3], "fallback_reason": reason}


def analyze_quiz_result(
    score: int,
    question_results: list[dict[str, Any]] | None = None,
    course_id: int = 1,
) -> dict[str, Any]:
    question_results = question_results or []
    proxied, reason = _try_proxy(
        "/evaluation/analyze",
        {
            "quiz_id": 1,
            "course_id": course_id,
            "score": int(score),
            "question_results": question_results,
            "student_profile": demo_data.DEMO_PROFILE,
        },
        timeout=60.0,
    )
    if proxied and isinstance(proxied.get("evaluation"), dict):
        return {"mode": "edupath_ai_service", "evaluation": proxied["evaluation"], "evidence": proxied.get("evidence", [])}

    evaluation = dict(demo_data.EVALUATION)
    evaluation["overall_score"] = int(score)
    evaluation["summary"] = (
        f"本次得分 {score}。概念题表现稳定，但边界条件仍有缺口；"
        "建议保留 10 分钟补救任务，再决定是否进入 BST 应用。"
    )
    return {"mode": "demo_fixture", "evaluation": evaluation, "evidence": demo_data.EVIDENCE[:2], "fallback_reason": reason}


# ---------------------------------------------------------------------------
# Tool 9: safety review
# ---------------------------------------------------------------------------

_RISK_PATTERNS = [
    (r"代写|代考|帮我考试|直接给答案", "学术诚信", "high"),
    (r"自杀|自残|暴力", "安全风险", "high"),
    (r"身份证|手机号|家庭住址", "隐私", "medium"),
]


def review_safety(text: str) -> dict[str, Any]:
    issues = []
    risk = "low"
    for pattern, category, level in _RISK_PATTERNS:
        if re.search(pattern, text or ""):
            issues.append(f"{category}：命中规则 {pattern}")
            risk = "high" if level == "high" else ("medium" if risk == "low" else risk)
    passed = not issues
    return {
        "mode": "demo_fixture",
        "passed": passed,
        "risk_level": risk,
        "issues": issues,
        "suggestions": ["改为讲解思路或提供练习，而不是直接交付答案"] if issues else [],
        "confidence": 0.95,
        "checks": demo_data.SAFETY["checks"] if passed else [],
    }


# ---------------------------------------------------------------------------
# Tool 10: course outline / resource quality
# ---------------------------------------------------------------------------


def get_course_outline(course_id: int = 1) -> dict[str, Any]:
    course = demo_data.course_by_id(course_id)
    return {"mode": "demo_fixture", "course": course, "knowledge_point_count": len(course["knowledge_points"])}


def evaluate_resource(resource: dict[str, Any] | None = None, evidence: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    resource = resource or {}
    evidence = evidence or demo_data.EVIDENCE[:3]
    text = json.dumps(resource, ensure_ascii=False)
    cited = [item for item in evidence if item.get("chunk_id") in text]
    score = 88.0
    findings = [f"{len(cited)}/{len(evidence)} 条证据被引用"]
    if len(cited) < 3:
        score -= 6
    return {
        "mode": "demo_fixture",
        "evaluation": {**demo_data.QUALITY, "total_score": score, "dimensions": demo_data.QUALITY["dimensions"]},
        "findings": findings,
    }


TOOL_SPECS: list[dict[str, Any]] = [
    {
        "name": "edupath_get_profile",
        "description": "读取或增量提取学生的版本化学习画像：目标、薄弱点、偏好、时间预算、错因模式。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "student_id": {"type": "string", "description": "脱敏学生 ID"},
                "message": {"type": "string", "description": "学生自述；为空时读取已有画像"},
            },
        },
    },
    {
        "name": "edupath_search_knowledge",
        "description": "在课程知识库中做证据检索，返回 chunk、来源、许可证与匹配分数；生成内容必须引用返回的 chunk_id。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "检索问题或知识点"},
                "course_id": {"type": "integer", "default": 1},
                "top_k": {"type": "integer", "default": 4, "minimum": 1, "maximum": 8},
            },
            "required": ["query"],
        },
    },
    {
        "name": "edupath_plan_resource_bundle",
        "description": "根据学习目标、薄弱点和时间预算，规划资源类型组合、难度与验收标准。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "goal": {"type": "string"},
                "weak_points": {"type": "array", "items": {"type": "string"}},
                "resource_types": {"type": "array", "items": {"type": "string"}},
                "difficulty": {"type": "string", "enum": ["basic", "medium", "advanced"]},
            },
            "required": ["goal"],
        },
    },
    {
        "name": "edupath_generate_learning_resource",
        "description": "生成带课程证据的结构化学习资源（讲义/导图/题库/代码实验），并返回质量门禁与安全审核结果。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "course_id": {"type": "integer", "default": 1},
                "knowledge_point": {"type": "string", "default": "二叉树遍历"},
                "resource_types": {"type": "array", "items": {"type": "string"}},
                "difficulty": {"type": "string", "enum": ["basic", "medium", "advanced"]},
                "student_profile": {"type": "object"},
            },
            "required": ["knowledge_point"],
        },
    },
    {
        "name": "edupath_plan_learning_path",
        "description": "按天规划学习路径，返回每日主题、任务、时长、预期产出与证据；支持测验后重规划。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "course_id": {"type": "integer", "default": 1},
                "target": {"type": "string"},
                "days": {"type": "integer", "default": 3, "minimum": 1, "maximum": 7},
                "daily_minutes": {"type": "integer", "default": 30},
                "student_profile": {"type": "object"},
            },
        },
    },
    {
        "name": "edupath_answer_with_evidence",
        "description": "课程辅导问答：只依据检索到的课程证据回答，返回步骤、引用与置信度。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "question": {"type": "string"},
                "course_id": {"type": "integer", "default": 1},
                "answer_mode": {"type": "string", "enum": ["hint", "socratic", "step_by_step", "summary", "code_first"]},
            },
            "required": ["question"],
        },
    },
    {
        "name": "edupath_generate_quiz",
        "description": "生成 1-3 道带解析与证据的单选题，用于查漏补缺。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "course_id": {"type": "integer", "default": 1},
                "knowledge_point": {"type": "string"},
                "question_count": {"type": "integer", "default": 3, "minimum": 1, "maximum": 3},
                "difficulty": {"type": "string", "enum": ["basic", "medium", "advanced"]},
            },
            "required": ["knowledge_point"],
        },
    },
    {
        "name": "edupath_analyze_quiz_result",
        "description": "分析测验作答，输出掌握度、薄弱点、错因模式与下一步动作；分数由业务系统确定性计算。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "score": {"type": "integer", "minimum": 0, "maximum": 100},
                "question_results": {"type": "array", "items": {"type": "object"}},
                "course_id": {"type": "integer", "default": 1},
            },
            "required": ["score"],
        },
    },
    {
        "name": "edupath_review_safety",
        "description": "对生成内容或用户输入做安全审核：学术诚信、隐私、事实性与价值观。",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "edupath_get_course_outline",
        "description": "返回课程与知识点大纲，用于 AgentArts 工作流选择课程/知识点。",
        "inputSchema": {
            "type": "object",
            "properties": {"course_id": {"type": "integer", "default": 1}},
        },
    },
]

TOOL_HANDLERS = {
    "edupath_get_profile": get_profile,
    "edupath_search_knowledge": search_knowledge,
    "edupath_plan_resource_bundle": plan_resource_bundle,
    "edupath_generate_learning_resource": generate_learning_resource,
    "edupath_plan_learning_path": plan_learning_path,
    "edupath_answer_with_evidence": answer_with_evidence,
    "edupath_generate_quiz": generate_quiz,
    "edupath_analyze_quiz_result": analyze_quiz_result,
    "edupath_review_safety": review_safety,
    "edupath_get_course_outline": get_course_outline,
}


def call_tool(name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
    handler = TOOL_HANDLERS.get(name)
    if handler is None:
        raise KeyError(f"unknown tool: {name}")
    return handler(**(arguments or {}))
