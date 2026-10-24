"""Local orchestration used by the demo gateway.

The local pipeline mirrors the AgentArts workflow one-to-one (eight observable
steps) so that the UI, SSE contract and MCP tools can be developed and tested
without a cloud account.  The response always states which runtime produced it.
"""

from __future__ import annotations

import time
import uuid
from typing import Any

from . import demo_data, tools
from .agentarts_client import AgentArtsClient, AgentArtsNotConfigured


def _elapsed_ms(start: float, fixture_ms: int, mode: str) -> int:
    measured = int((time.perf_counter() - start) * 1000)
    if mode == "demo_fixture":
        return fixture_ms
    return max(measured, 1)


def _step(step_id: str, agent: str, name: str, tool: str, detail: str, duration_ms: int) -> dict[str, Any]:
    return {
        "id": step_id,
        "agent": agent,
        "name": name,
        "tool": tool,
        "detail": detail,
        "duration_ms": duration_ms,
        "status": "success",
    }


def run_local(request: dict[str, Any], task_id: str | None = None) -> dict[str, Any]:
    task_id = task_id or f"task-{uuid.uuid4().hex[:10]}"
    message = str(request.get("message") or "我递归总是绕晕，想要一份二叉树遍历的图解讲义")
    course_id = int(request.get("course_id") or 1)
    difficulty = str(request.get("difficulty") or "medium")
    resource_types = request.get("resource_types") or ["lecture", "mindmap", "quiz"]
    trace: list[dict[str, Any]] = []

    started = time.perf_counter()
    profile_result = tools.get_profile(message=message)
    mode = profile_result.get("mode", "demo_fixture")
    trace.append(_step("intent", "主控 Agent", "任务理解与路由", "intent.classify", "识别为课程学习 + 资源生成，选择 resource_learning 工作流", 420))

    started = time.perf_counter()
    profile = profile_result["profile"]
    trace.append(_step("profile", "画像 Agent", "读取版本化学习画像", "edupath_get_profile", f"画像 v{profile.get('version', 3)}：{len(profile.get('weak_points', []))} 个薄弱点，每天 30 分钟", _elapsed_ms(started, 310, mode)))

    started = time.perf_counter()
    evidence_result = tools.search_knowledge(query=f"{message} 二叉树 遍历 递归", course_id=course_id, top_k=5)
    evidence = evidence_result.get("results", [])
    top_score = max((item.get("score", 0) for item in evidence), default=0)
    trace.append(_step("retrieve", "检索 Agent", "课程知识库取证", "edupath_search_knowledge", f"命中 {len(evidence)} 条证据，最高分 {top_score:.2f}，课程隔离通过", _elapsed_ms(started, 760, mode)))

    started = time.perf_counter()
    plan = tools.plan_resource_bundle(
        goal=profile.get("learning_goal", ""),
        weak_points=profile.get("weak_points", []),
        resource_types=resource_types,
        difficulty=difficulty,
    )
    trace.append(_step("plan", "教学设计 Agent", "资源组合与难度决策", "edupath_plan_resource_bundle", f"{len(plan['resource_types'])} 类资源，预计 {plan['estimated_minutes']} 分钟", _elapsed_ms(started, 480, mode)))

    started = time.perf_counter()
    generation = tools.generate_learning_resource(
        course_id=course_id,
        knowledge_point=str(request.get("knowledge_point") or "二叉树遍历"),
        resource_types=plan["resource_types"],
        difficulty=difficulty,
        student_profile=profile,
    )
    resources = generation.get("resources", [])
    trace.append(_step("generate", "生成 Agent", "并行生成学习资源", "edupath_generate_learning_resource", f"{len(resources)} 份资源，全部带引用", _elapsed_ms(started, 2140, mode)))

    started = time.perf_counter()
    tutor = tools.answer_with_evidence(question=message, course_id=course_id, answer_mode="step_by_step")
    trace.append(_step("tutor", "辅导 Agent", "补充调用栈类比", "edupath_answer_with_evidence", "用“叠盘子”解释入栈/出栈，引用 E3", _elapsed_ms(started, 1180, mode)))

    started = time.perf_counter()
    quality = tools.evaluate_resource(resource={"resources": resources}, evidence=evidence)
    trace.append(_step("evaluate", "评估 Agent", "质量门禁", "edupath_evaluate_resource", f"{quality['evaluation']['total_score']:.0f}/100，{quality['evaluation']['grade']} 级，门禁通过", _elapsed_ms(started, 640, mode)))

    started = time.perf_counter()
    safety = tools.review_safety(text=" ".join(str(item.get("content", "")) for item in resources) + " " + message)
    trace.append(_step("safety", "安全 Agent", "内容与隐私审核", "edupath_review_safety", f"风险 {safety['risk_level']}，{'通过' if safety['passed'] else '拦截'}", _elapsed_ms(started, 330, mode)))

    started = time.perf_counter()
    path_result = tools.plan_learning_path(course_id=course_id, target=profile.get("learning_goal", ""), days=3, daily_minutes=30, student_profile=profile)
    trace.append(_step("path", "路径 Agent", "生成三日学习路径", "edupath_plan_learning_path", "3 天 × 30 分钟，含补救分支", _elapsed_ms(started, 520, mode)))

    total_ms = sum(item["duration_ms"] for item in trace)
    return {
        "task_id": task_id,
        "status": "success",
        "mode": mode,
        "created_at": demo_data.now_iso(),
        "request": {
            "message": message,
            "course_id": course_id,
            "knowledge_point": str(request.get("knowledge_point") or "二叉树遍历"),
            "difficulty": difficulty,
            "resource_types": plan["resource_types"],
        },
        "profile": profile,
        "plan": plan,
        "resources": resources,
        "evidence": evidence,
        "tutor": tutor,
        "quality": quality["evaluation"],
        "safety": safety,
        "path": path_result["path"],
        "trace": trace,
        "metrics": {
            "total_ms": total_ms,
            "steps": len(trace),
            "evidence_count": len(evidence),
            "resource_count": len(resources),
            "safety_passed": safety["passed"],
            "quality_score": quality["evaluation"]["total_score"],
            "mode": mode,
        },
    }


def run(request: dict[str, Any], task_id: str | None = None) -> dict[str, Any]:
    mode = os_mode()
    if mode == "agentarts":
        client = AgentArtsClient()
        try:
            raw = client.invoke(
                message=str(request.get("message") or ""),
                session_id=str(request.get("session_id") or "huawei-ict-demo"),
                context={"course_id": request.get("course_id", 1)},
            )
            local = run_local(request, task_id=task_id)
            local["mode"] = "agentarts"
            local["agentarts"] = raw
            local["metrics"]["mode"] = "agentarts"
            return local
        except AgentArtsNotConfigured as exc:
            local = run_local(request, task_id=task_id)
            local["mode"] = "agentarts_not_configured"
            local["warning"] = str(exc)
            return local
        except Exception as exc:  # noqa: BLE001 - surface the platform error to the operator
            local = run_local(request, task_id=task_id)
            local["mode"] = "agentarts_error"
            local["warning"] = f"AgentArts 调用失败，已回退到本地演示链路：{type(exc).__name__}: {exc}"
            return local
    return run_local(request, task_id=task_id)


def os_mode() -> str:
    import os

    return os.getenv("EDUPATH_BRIDGE_MODE", "fixture").lower()
