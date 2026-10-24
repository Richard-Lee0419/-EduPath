"""Zero-dependency demo gateway for the Huawei ICT / AgentArts edition.

Serves the visual demo, the agent run API (JSON + SSE), and the MCP endpoint.
Using only the Python standard library keeps the competition demo portable:
the judges' laptop only needs Python 3.11+, no pip install and no node build.

    python -m bridge.gateway --port 8765
    # open http://127.0.0.1:8765/
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import queue
import threading
import time
import traceback
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse

from . import __version__, demo_data, orchestrator, tools
from .mcp_server import dispatch as mcp_dispatch

WEB_ROOT = Path(__file__).resolve().parent.parent / "web"
TASKS: dict[str, "Task"] = {}
TASKS_LOCK = threading.Lock()
MCP_SESSIONS: dict[str, "queue.Queue[dict[str, Any]]"] = {}
MCP_LOCK = threading.Lock()


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default


def runtime_info() -> dict[str, Any]:
    mode = os.getenv("EDUPATH_BRIDGE_MODE", "fixture").lower()
    ai_base = os.getenv("EDUPATH_AI_BASE_URL", "")
    endpoint = os.getenv("AGENTARTS_ENDPOINT", "")
    return {
        "service": "edupath-agentarts-bridge",
        "version": __version__,
        "mode": mode,
        "mode_label": {
            "fixture": "本地演示链路（未调用云平台）",
            "agentarts": "AgentArts 已发布智能体",
            "edupath_ai": "EduPath AI 服务（MCP 工具代理）",
        }.get(mode, mode),
        "agentarts_configured": bool(endpoint),
        "edupath_ai_configured": bool(ai_base),
        "model": os.getenv("AGENTARTS_MODEL", "DeepSeek-V4-Flash"),
        "platform": "华为云 AgentArts",
        "note": "fixture 模式用于离线彩排；正式提交必须配置 AgentArts 端点并在平台内完成构建。",
    }


class Task:
    def __init__(self, task_id: str, request: dict[str, Any]) -> None:
        self.id = task_id
        self.request = request
        self.status = "queued"
        self.events: list[dict[str, Any]] = []
        self.result: dict[str, Any] | None = None
        self.error = ""
        self.condition = threading.Condition()

    def emit(self, event: str, data: dict[str, Any]) -> None:
        with self.condition:
            self.events.append({"seq": len(self.events) + 1, "event": event, "data": data, "ts": time.time()})
            self.condition.notify_all()

    def finish(self, result: dict[str, Any] | None, error: str = "") -> None:
        self.result = result
        self.error = error
        self.status = "success" if result is not None and not error else "failed"
        with self.condition:
            payload = result or {"error": error, "task_id": self.id}
            self.events.append({"seq": len(self.events) + 1, "event": "done", "data": payload, "ts": time.time()})
            self.condition.notify_all()

    def wait_for_events(self, index: int, timeout: float = 20.0) -> list[dict[str, Any]]:
        with self.condition:
            if index >= len(self.events):
                self.condition.wait(timeout=timeout)
            return self.events[index:]


def _run_task(task: Task) -> None:
    delay = _env_float("EDUPATH_DEMO_DELAY", 0.55)
    if task.request.get("fast"):
        delay = 0.0
    try:
        task.status = "running"
        task.emit("status", {"status": "running", "message": "AgentArts 工作流已启动"})
        result = orchestrator.run(task.request, task_id=task.id)
        task.emit("profile", {"profile": result["profile"]})
        task.emit("evidence", {"evidence": result["evidence"], "corpus": {"documents": 108, "chunks": 1200}})
        for step in result.get("trace", []):
            if delay:
                time.sleep(delay)
            task.emit("step", step)
        if delay:
            time.sleep(delay)
        task.emit(
            "artifact",
            {
                "plan": result["plan"],
                "resources": result["resources"],
                "path": result["path"],
                "tutor": result["tutor"],
                "quality": result["quality"],
                "safety": result["safety"],
                "metrics": result["metrics"],
            },
        )
        if result.get("warning"):
            task.emit("warning", {"message": result["warning"]})
        task.finish(result)
    except Exception as exc:  # noqa: BLE001 - report the failure to the UI
        traceback.print_exc()
        task.error = f"{type(exc).__name__}: {exc}"
        task.finish(None, task.error)


def _safe_json(body: bytes) -> dict[str, Any]:
    if not body:
        return {}
    parsed = json.loads(body.decode("utf-8"))
    if not isinstance(parsed, dict):
        raise ValueError("request body must be a JSON object")
    return parsed


class Handler(BaseHTTPRequestHandler):
    server_version = f"EduPathAgentArtsBridge/{__version__}"
    protocol_version = "HTTP/1.1"

    # ---------------------------------------------------------------- helpers
    def _send_json(self, payload: Any, status: int = HTTPStatus.OK) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)

    def _send_text(self, text: str, status: int = HTTPStatus.OK, content_type: str = "text/plain; charset=utf-8") -> None:
        encoded = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _send_static(self, path: str) -> None:
        relative = unquote(path).lstrip("/") or "index.html"
        if relative in {"", "."}:
            relative = "index.html"
        target = (WEB_ROOT / relative).resolve()
        try:
            target.relative_to(WEB_ROOT.resolve())
        except ValueError:
            self._send_text("forbidden", HTTPStatus.FORBIDDEN)
            return
        if not target.is_file():
            self._send_text("not found", HTTPStatus.NOT_FOUND)
            return
        mime, _ = mimetypes.guess_type(str(target))
        content = target.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", f"{mime or 'application/octet-stream'}; charset=utf-8" if (mime or "").startswith("text/") else (mime or "application/octet-stream"))
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(content)

    def _sse_headers(self) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache, no-transform")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()

    # ------------------------------------------------------------------- verbs
    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        if path in {"/", "/index.html"}:
            self._send_static("index.html")
            return
        if path == "/health":
            self._send_json({"status": "ok", "service": "edupath-agentarts-bridge", "version": __version__})
            return
        if path == "/runtime":
            self._send_json(self._runtime())
            return
        if path == "/api/agent/bootstrap":
            self._send_json(self._bootstrap())
            return
        if path.startswith("/api/agent/tasks/"):
            tail = path[len("/api/agent/tasks/") :]
            if tail.endswith("/stream"):
                self._stream_task(tail[: -len("/stream")])
                return
            if tail.endswith("/trace"):
                task = TASKS.get(tail[: -len("/trace")])
                if not task:
                    self._send_json({"error": "task not found"}, HTTPStatus.NOT_FOUND)
                    return
                self._send_json({"task_id": task.id, "status": task.status, "trace": task.result.get("trace", []) if task.result else []})
                return
            task = TASKS.get(tail)
            if not task:
                self._send_json({"error": "task not found"}, HTTPStatus.NOT_FOUND)
                return
            self._send_json({"task_id": task.id, "status": task.status, "result": task.result, "error": task.error})
            return
        if path == "/api/mcp/sse":
            self._mcp_sse()
            return
        if path in {"/styles.css", "/app.js", "/favicon.svg"} or path.startswith("/assets/"):
            self._send_static(path)
            return
        self._send_text("not found", HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        try:
            if path == "/api/agent/run":
                self._start_task(body)
                return
            if path == "/api/agent/chat":
                payload = _safe_json(body)
                answer = tools.answer_with_evidence(
                    question=str(payload.get("question") or "递归为什么总是绕晕？"),
                    course_id=int(payload.get("course_id") or 1),
                )
                self._send_json(answer)
                return
            if path == "/api/mcp":
                self._mcp_json(body)
                return
            if path == "/api/mcp/message":
                self._mcp_message(parse_qs(parsed.query), body)
                return
            self._send_json({"error": "not found"}, HTTPStatus.NOT_FOUND)
        except json.JSONDecodeError as exc:
            self._send_json({"error": f"invalid JSON: {exc}"}, HTTPStatus.BAD_REQUEST)
        except ValueError as exc:
            self._send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)
        except Exception as exc:  # noqa: BLE001 - keep the demo server alive
            traceback.print_exc()
            self._send_json({"error": f"{type(exc).__name__}: {exc}"}, HTTPStatus.INTERNAL_SERVER_ERROR)

    # --------------------------------------------------------------- endpoints
    def _runtime(self) -> dict[str, Any]:
        return runtime_info()

    def _bootstrap(self) -> dict[str, Any]:
        return {
            "runtime": self._runtime(),
            "profile": demo_data.DEMO_PROFILE,
            "courses": demo_data.COURSES,
            "evidence": demo_data.EVIDENCE,
            "platform_agents": demo_data.PLATFORM_AGENTS,
            "steps": demo_data.TRACE_STEPS,
            "quality": demo_data.QUALITY,
            "safety": demo_data.SAFETY,
            "path": demo_data.PATH,
        }

    def _start_task(self, body: bytes) -> None:
        payload = _safe_json(body)
        task_id = f"task-{uuid.uuid4().hex[:10]}"
        task = Task(task_id, payload)
        with TASKS_LOCK:
            TASKS[task_id] = task
        thread = threading.Thread(target=_run_task, args=(task,), daemon=True)
        thread.start()
        self._send_json({"task_id": task_id, "status": "queued", "stream": f"/api/agent/tasks/{task_id}/stream"}, HTTPStatus.ACCEPTED)

    def _stream_task(self, task_id: str) -> None:
        task = TASKS.get(task_id)
        if not task:
            self._send_json({"error": "task not found"}, HTTPStatus.NOT_FOUND)
            return
        self._sse_headers()
        index = 0
        idle = 0.0
        try:
            while True:
                events = task.wait_for_events(index, timeout=10.0)
                if not events:
                    idle += 10.0
                    if task.status in {"success", "failed"}:
                        break
                    if idle > 60:
                        self.wfile.write(b": heartbeat\n\n")
                        self.wfile.flush()
                        idle = 0.0
                    continue
                idle = 0.0
                for event in events:
                    index = max(index, event["seq"])
                    chunk = (
                        f"id: {event['seq']}\n"
                        f"event: {event['event']}\n"
                        f"data: {json.dumps(event['data'], ensure_ascii=False)}\n\n"
                    )
                    self.wfile.write(chunk.encode("utf-8"))
                    self.wfile.flush()
                if any(event["event"] == "done" for event in events):
                    break
        except (BrokenPipeError, ConnectionResetError):
            return

    def _mcp_json(self, body: bytes) -> None:
        payload = json.loads(body.decode("utf-8"))
        response = mcp_dispatch(payload)
        if response is None:
            self.send_response(HTTPStatus.ACCEPTED)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self._send_json(response)

    def _mcp_sse(self) -> None:
        session_id = uuid.uuid4().hex
        session: queue.Queue[dict[str, Any]] = queue.Queue()
        with MCP_LOCK:
            MCP_SESSIONS[session_id] = session
        self._sse_headers()
        try:
            self.wfile.write(f"event: endpoint\ndata: /api/mcp/message?session_id={session_id}\n\n".encode("utf-8"))
            self.wfile.flush()
            while True:
                try:
                    message = session.get(timeout=15)
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
                    continue
                if message is None:
                    break
                data = json.dumps(message, ensure_ascii=False)
                self.wfile.write(f"event: message\ndata: {data}\n\n".encode("utf-8"))
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            with MCP_LOCK:
                MCP_SESSIONS.pop(session_id, None)

    def _mcp_message(self, query: dict[str, list[str]], body: bytes) -> None:
        session_id = (query.get("session_id") or [""])[0]
        session = MCP_SESSIONS.get(session_id)
        if session is None:
            self._send_json({"error": "unknown MCP session"}, HTTPStatus.NOT_FOUND)
            return
        payload = json.loads(body.decode("utf-8"))
        response = mcp_dispatch(payload)
        if response is not None:
            session.put(response)
        self.send_response(HTTPStatus.ACCEPTED)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
        if os.getenv("EDUPATH_HTTP_LOG", "0") == "1":
            super().log_message(fmt, *args)


def create_server(host: str = "127.0.0.1", port: int = 8765) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="EduPath Huawei ICT / AgentArts demo gateway")
    parser.add_argument("--host", default=os.getenv("EDUPATH_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("EDUPATH_PORT", "8765")))
    args = parser.parse_args()
    server = create_server(args.host, args.port)
    print(f"EduPath AgentArts demo: http://{args.host}:{args.port}/")
    print(f"Runtime: {json.dumps(runtime_info(), ensure_ascii=False)}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
