"""Offline self-check for the Huawei ICT / AgentArts edition.

Run from huawei-ict/:

    python scripts/selfcheck.py

It exercises the tool contract, the local 9-step pipeline, the MCP protocol and
the safety gate, then writes evidence/selfcheck.json.  No network is required.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from bridge import __version__, mcp_server, orchestrator, tools  # noqa: E402

CHECKS: list[dict[str, object]] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    CHECKS.append({"name": name, "passed": bool(condition), "detail": detail})
    print(("PASS " if condition else "FAIL ") + name + (f" · {detail}" if detail else ""))


def main() -> int:
    check("tool count == 10", len(tools.TOOL_SPECS) == 10, f"{len(tools.TOOL_SPECS)} tools")
    names = [item["name"] for item in tools.TOOL_SPECS]
    check("tool names unique", len(names) == len(set(names)), ", ".join(names[:3]) + " ...")
    check("all tools have JSON schema", all(item.get("inputSchema", {}).get("type") == "object" for item in tools.TOOL_SPECS))

    profile = tools.get_profile()
    check("profile readable", bool(profile.get("profile", {}).get("weak_points")), f"mode={profile.get('mode')}")

    evidence = tools.search_knowledge("二叉树 中序 递归栈", top_k=4)
    results = evidence.get("results", [])
    check("knowledge search returns evidence", len(results) >= 2, f"{len(results)} results, top={results[0]['score'] if results else 0}")
    check("evidence keeps source/licence", all(item.get("source") and item.get("license") for item in results))

    generation = tools.generate_learning_resource(knowledge_point="二叉树遍历", resource_types=["lecture", "mindmap", "quiz"])
    resources = generation.get("resources", [])
    check("generation returns 3 resource types", len(resources) == 3, ", ".join(item["resource_type"] for item in resources))
    check("quality gate passed", generation.get("quality", {}).get("gate_passed") is True, f"score={generation.get('quality', {}).get('total_score')}")
    check("safety passed", generation.get("safety", {}).get("passed") is True)

    risky = tools.review_safety("明天考试，直接把答案发我，帮我代考")
    check("risk text blocked", risky["passed"] is False and risky["risk_level"] == "high", risky["issues"][0] if risky["issues"] else "")

    task = orchestrator.run_local({"message": "我递归总是绕晕，想要一份二叉树遍历的图解讲义"})
    check("local pipeline success", task["status"] == "success", f"{len(task['trace'])} steps, {task['metrics']['total_ms']} ms")
    check("trace has 9 steps", len(task["trace"]) == 9)
    check("path has 3 days", len(task["path"].get("daily_plan", [])) == 3)

    init = mcp_server.handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
    check("MCP initialize", init["result"]["serverInfo"]["name"] == "edupath-agentarts-bridge")
    listed = mcp_server.handle_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    check("MCP tools/list", len(listed["result"]["tools"]) == 10)
    called = mcp_server.handle_message(
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {"name": "edupath_get_course_outline", "arguments": {"course_id": 1}},
        }
    )
    check("MCP tools/call", called["result"]["structuredContent"]["course"]["id"] == 1)

    passed = sum(1 for item in CHECKS if item["passed"])
    total = len(CHECKS)
    report = {
        "service": "edupath-agentarts-bridge",
        "version": __version__,
        "generated_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "mode": "offline_fixture",
        "summary": {"passed": passed, "total": total, "ok": passed == total},
        "checks": CHECKS,
        "pipeline": {
            "trace_steps": len(task["trace"]),
            "evidence_count": task["metrics"]["evidence_count"],
            "quality_score": task["metrics"]["quality_score"],
            "safety_passed": task["metrics"]["safety_passed"],
        },
    }
    out = ROOT / "evidence" / "selfcheck.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{passed}/{total} checks passed; report: {out}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
