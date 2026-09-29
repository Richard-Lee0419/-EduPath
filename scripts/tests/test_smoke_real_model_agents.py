from __future__ import annotations

import importlib.util
import io
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "smoke_real_model_agents.py"
SPEC = importlib.util.spec_from_file_location("smoke_real_model_agents", SCRIPT_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RealModelClosureSmokeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.task_id = "task_resource_acceptance"
        self.resource_id = "res_acceptance"

    def response_for(self, method: str, url: str, payload=None, token: str = "", timeout: float = 30):
        if url.endswith("/api/auth/login"):
            return {"data": {"token": "test-token"}}
        if url.endswith("/api/agent/resource-task"):
            return {"data": {"task_id": self.task_id}}
        if url.endswith(f"/api/agent/tasks/{self.task_id}"):
            return {
                "data": {
                    "task_id": self.task_id,
                    "status": "success",
                    "progress": 100,
                    "result": {
                        "model_runtime": {
                            "mode": "real_model",
                            "real_model_used": True,
                            "provider": "deepseek",
                            "model": "deepseek-chat",
                            "call_count": 2,
                        },
                        "safety": {"passed": True},
                        "resources": [{"resource_id": self.resource_id}],
                    },
                }
            }
        if "/api/agent/tasks?size=20" in url:
            return {
                "data": {
                    "items": [{
                        "task_id": self.task_id,
                        "status": "success",
                        "operation_type": "resource_task",
                    }]
                }
            }
        if url.endswith(f"/api/resources/{self.resource_id}"):
            return {
                "data": {
                    "resource_id": self.resource_id,
                    "generation_mode": "real_model",
                    "content": "A" * 180,
                    "evidence": [{"chunk_id": "course_chunk_1"}],
                    "safety": {"passed": True},
                    "quality_evaluation": {"gate_passed": True},
                }
            }
        raise AssertionError(f"unexpected request: {method} {url}")

    def test_backend_closure_checks_task_center_and_persisted_resource(self) -> None:
        argv = [
            str(SCRIPT_PATH),
            "--skip-ai-direct",
            "--resource-types",
            "lecture",
            "--poll",
            "0",
        ]
        output = io.StringIO()
        with patch.object(MODULE, "request_json", side_effect=self.response_for), patch.object(sys, "argv", argv), redirect_stdout(output):
            self.assertEqual(MODULE.main(), 0)
        result = output.getvalue()
        self.assertIn('"task_center_verified": true', result)
        self.assertIn('"resource_persistence_verified": true', result)


if __name__ == "__main__":
    unittest.main()
