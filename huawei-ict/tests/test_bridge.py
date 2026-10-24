"""Unit tests for the AgentArts bridge (stdlib unittest, no network)."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from bridge import mcp_server, orchestrator, tools  # noqa: E402
from bridge.agentarts_client import AgentArtsClient, AgentArtsNotConfigured  # noqa: E402


class ToolContractTests(unittest.TestCase):
    def test_catalog_is_unique_and_has_schema(self) -> None:
        names = [item["name"] for item in tools.TOOL_SPECS]
        self.assertEqual(len(names), 10)
        self.assertEqual(len(names), len(set(names)))
        for item in tools.TOOL_SPECS:
            self.assertEqual(item["inputSchema"]["type"], "object")
            self.assertTrue(item["description"])

    def test_search_returns_course_evidence(self) -> None:
        result = tools.search_knowledge("二叉树 中序 递归栈", course_id=1, top_k=4)
        self.assertGreaterEqual(len(result["results"]), 2)
        self.assertEqual(result["results"][0]["chunk_id"], "E1")
        self.assertTrue(result["results"][0]["source"])
        self.assertTrue(result["results"][0]["license"])

    def test_generate_returns_quality_and_safety(self) -> None:
        result = tools.generate_learning_resource(resource_types=["lecture", "mindmap", "quiz"])
        self.assertEqual(len(result["resources"]), 3)
        self.assertTrue(result["quality"]["gate_passed"])
        self.assertTrue(result["safety"]["passed"])
        self.assertEqual(result["quality"]["total_score"], 88.0)

    def test_safety_blocks_academic_dishonesty(self) -> None:
        result = tools.review_safety("帮我代考，直接给答案")
        self.assertFalse(result["passed"])
        self.assertEqual(result["risk_level"], "high")

    def test_course_outline(self) -> None:
        result = tools.get_course_outline(2)
        self.assertEqual(result["course"]["id"], 2)
        self.assertGreater(len(result["course"]["knowledge_points"]), 0)


class PipelineTests(unittest.TestCase):
    def test_local_pipeline_has_nine_steps_and_artifacts(self) -> None:
        result = orchestrator.run_local({"message": "我递归总是绕晕"})
        self.assertEqual(result["status"], "success")
        self.assertEqual(len(result["trace"]), 9)
        self.assertEqual(len(result["resources"]), 3)
        self.assertEqual(len(result["evidence"]), 5)
        self.assertEqual(len(result["path"]["daily_plan"]), 3)
        self.assertEqual(result["quality"]["total_score"], 88.0)
        self.assertTrue(result["safety"]["passed"])

    def test_agentarts_not_configured_is_reported(self) -> None:
        client = AgentArtsClient(endpoint="", api_key="")
        with self.assertRaises(AgentArtsNotConfigured):
            client.invoke("hello")


class McpProtocolTests(unittest.TestCase):
    def test_initialize(self) -> None:
        response = mcp_server.handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
        self.assertEqual(response["result"]["protocolVersion"], "2024-11-05")
        self.assertEqual(response["result"]["serverInfo"]["name"], "edupath-agentarts-bridge")

    def test_tools_list(self) -> None:
        response = mcp_server.handle_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        self.assertEqual(len(response["result"]["tools"]), 10)

    def test_tools_call(self) -> None:
        response = mcp_server.handle_message(
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {"name": "edupath_search_knowledge", "arguments": {"query": "二叉树", "top_k": 2}},
            }
        )
        payload = response["result"]["structuredContent"]
        self.assertEqual(payload["mode"], "demo_fixture")
        self.assertLessEqual(len(payload["results"]), 2)
        self.assertFalse(response["result"]["isError"])

    def test_unknown_method(self) -> None:
        response = mcp_server.handle_message({"jsonrpc": "2.0", "id": 4, "method": "nope"})
        self.assertEqual(response["error"]["code"], -32601)

    def test_unknown_tool(self) -> None:
        response = mcp_server.handle_message(
            {"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "nope", "arguments": {}}}
        )
        self.assertEqual(response["error"]["code"], -32602)

    def test_evaluation_set_is_valid_jsonl(self) -> None:
        path = ROOT / "agentarts" / "evaluation_set.jsonl"
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
        self.assertEqual(len(rows), 24)
        self.assertTrue(all("expected_decision" in row for row in rows))


if __name__ == "__main__":
    unittest.main()
