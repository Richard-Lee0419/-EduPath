"""A dependency-free MCP server for the EduPath bridge.

Implements the MCP 2024-11-05 core methods used by AgentArts custom MCP
integrations: ``initialize``, ``tools/list`` and ``tools/call``.  The same
dispatcher is used by the stdio loop and by the demo gateway's HTTP endpoints
(JSON-RPC and the legacy HTTP+SSE transport).

Run standalone:

    python -m bridge.mcp_server --stdio
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any

from . import __version__, tools

PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "edupath-agentarts-bridge"


def _result(request_id: Any, result: dict[str, Any]) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def _error(request_id: Any, code: int, message: str, data: Any = None) -> dict[str, Any]:
    error: dict[str, Any] = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    return {"jsonrpc": "2.0", "id": request_id, "error": error}


def handle_message(message: dict[str, Any]) -> dict[str, Any] | None:
    method = message.get("method")
    request_id = message.get("id")
    params = message.get("params") or {}

    if method == "initialize":
        return _result(
            request_id,
            {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": SERVER_NAME, "version": __version__},
                "instructions": (
                    "知径 EduPath 课程学习工具集。生成类工具必须引用 edupath_search_knowledge "
                    "返回的 chunk_id；安全审核未通过时不得发布内容。"
                ),
            },
        )

    if method in ("notifications/initialized", "notifications/cancelled"):
        return None

    if method == "ping":
        return _result(request_id, {})

    if method == "tools/list":
        return _result(request_id, {"tools": tools.TOOL_SPECS})

    if method == "tools/call":
        name = params.get("name")
        arguments = params.get("arguments") or {}
        if not name:
            return _error(request_id, -32602, "tools/call requires params.name")
        try:
            payload = tools.call_tool(name, arguments)
        except KeyError as exc:
            return _error(request_id, -32602, str(exc))
        except TypeError as exc:
            return _error(request_id, -32602, f"invalid arguments for {name}: {exc}")
        except Exception as exc:  # noqa: BLE001 - return a protocol error, never crash the server
            return _error(request_id, -32603, f"tool {name} failed: {type(exc).__name__}: {exc}")
        text = json.dumps(payload, ensure_ascii=False, indent=2)
        return _result(
            request_id,
            {
                "content": [{"type": "text", "text": text}],
                "structuredContent": payload,
                "isError": False,
            },
        )

    if method == "resources/list":
        return _result(request_id, {"resources": []})

    if method == "prompts/list":
        return _result(request_id, {"prompts": []})

    return _error(request_id, -32601, f"method not found: {method}")


def dispatch(payload: Any) -> Any:
    """Dispatch a JSON-RPC object or batch; notifications produce no response."""

    if isinstance(payload, list):
        responses = [handle_message(item) for item in payload if isinstance(item, dict)]
        responses = [item for item in responses if item is not None]
        return responses or None
    if isinstance(payload, dict):
        return handle_message(payload)
    return _error(None, -32600, "invalid request")


def serve_stdio() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            payload = json.loads(line)
        except json.JSONDecodeError as exc:
            response = _error(None, -32700, f"parse error: {exc}")
        else:
            response = dispatch(payload)
        if response is not None:
            sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
            sys.stdout.flush()


def main() -> None:
    parser = argparse.ArgumentParser(description="EduPath MCP server for Huawei Cloud AgentArts")
    parser.add_argument("--stdio", action="store_true", help="serve MCP over stdio (default)")
    parser.add_argument("--list-tools", action="store_true", help="print tool definitions and exit")
    args = parser.parse_args()
    if args.list_tools:
        print(json.dumps({"tools": tools.TOOL_SPECS}, ensure_ascii=False, indent=2))
        return
    serve_stdio()


if __name__ == "__main__":
    main()
