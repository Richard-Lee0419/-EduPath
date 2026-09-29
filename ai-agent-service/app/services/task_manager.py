from __future__ import annotations

from concurrent.futures import Future, ThreadPoolExecutor
from copy import deepcopy
from datetime import UTC, datetime
from threading import Lock
from typing import Any, Callable
from uuid import uuid4

from app.core.config import settings


class TaskManager:
    """Task state for the internal AI service boundary."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._tasks: dict[str, dict[str, Any]] = {}
        self._futures: dict[str, Future[object]] = {}
        self._executor = ThreadPoolExecutor(
            max_workers=settings.agent_task_workers,
            thread_name_prefix="edupath-agent",
        )

    def create_pending_task(self, prefix: str, planned_agents: list[str]) -> dict[str, Any]:
        with self._lock:
            task_id = f"{prefix}_{uuid4().hex}"
            now = _now()
            task = {
                "task_id": task_id,
                "task_type": prefix,
                "status": "pending",
                "progress": 0,
                "planned_agents": list(planned_agents),
                "current_agent": None,
                "steps": [
                    {"agent": agent, "status": "pending", "message": "waiting"}
                    for agent in planned_agents
                ],
                "events": [
                    {"event": "task_created", "task_id": task_id, "timestamp": now},
                ],
                "result": {},
                "created_at": now,
                "updated_at": now,
            }
            self._tasks[task_id] = task
            return deepcopy(task)

    def submit(self, task_id: str, runner: Callable[[], object]) -> None:
        def execute() -> object:
            try:
                return runner()
            finally:
                with self._lock:
                    self._futures.pop(task_id, None)

        with self._lock:
            if task_id not in self._tasks:
                raise KeyError(f"task not found: {task_id}")
            self._futures[task_id] = self._executor.submit(execute)

    def start_task(self, task_id: str) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return None
            now = _now()
            task.update(status="running", progress=max(1, int(task.get("progress", 0))), updated_at=now)
            task["events"].append({"event": "task_started", "task_id": task_id, "timestamp": now})
            return deepcopy(task)

    def update_step(
        self,
        task_id: str,
        agent: str,
        status: str,
        message: str,
        progress: int,
    ) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None or task.get("status") == "cancelled":
                return None
            steps = task.get("steps", [])
            step = next(
                (
                    item
                    for item in steps
                    if item.get("agent") == agent and item.get("status") not in {"success", "cancelled"}
                ),
                None,
            )
            if step is None:
                step = next((item for item in steps if item.get("agent") == agent), None)
            if step is None:
                raise KeyError(f"agent step not found: {agent}")
            now = _now()
            step.update(status=status, message=message)
            task["status"] = "running"
            task["progress"] = max(int(task.get("progress", 0)), min(max(progress, 0), 99))
            task["current_agent"] = None if status == "success" else agent
            task["updated_at"] = now
            task["events"].append(
                {
                    "event": "agent_step",
                    "task_id": task_id,
                    "agent": agent,
                    "status": status,
                    "message": message,
                    "progress": task["progress"],
                    "timestamp": now,
                }
            )
            return deepcopy(task)

    def complete_task(self, task_id: str, result: dict[str, Any]) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None or task.get("status") == "cancelled":
                return None
            now = _now()
            task.update(
                status="success",
                progress=100,
                current_agent=None,
                result=result,
                updated_at=now,
            )
            task["events"].append({"event": "task_completed", "task_id": task_id, "timestamp": now})
            return deepcopy(task)

    def fail_task(self, task_id: str, error: str) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None or task.get("status") == "cancelled":
                return None
            now = _now()
            task.update(status="failed", current_agent=None, error=error, updated_at=now)
            for step in task.get("steps", []):
                if step.get("status") == "running":
                    step.update(status="failed", message=error)
                    break
            task["events"].append(
                {"event": "task_failed", "task_id": task_id, "error": error, "timestamp": now}
            )
            return deepcopy(task)

    def cancel_task(self, task_id: str) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return None
            if task.get("status") in {"success", "failed", "cancelled"}:
                return deepcopy(task)
            future = self._futures.get(task_id)
            if future is not None:
                future.cancel()
            now = _now()
            task.update(status="cancelled", current_agent=None, updated_at=now)
            for step in task.get("steps", []):
                if step.get("status") in {"pending", "running"}:
                    step.update(status="cancelled", message="cancelled")
            task["events"].append({"event": "task_cancelled", "task_id": task_id, "timestamp": now})
            return deepcopy(task)

    def create_task(
        self,
        prefix: str,
        planned_agents: list[str],
        result: dict[str, Any] | None = None,
        status: str = "success",
    ) -> dict[str, Any]:
        with self._lock:
            task_id = f"{prefix}_{uuid4().hex}"
            now = _now()
            terminal = status in {"success", "failed", "cancelled"}
            steps = [
                {
                    "agent": agent,
                    "status": "success" if status == "success" else "pending",
                    "message": f"{agent} completed" if status == "success" else "waiting",
                }
                for agent in planned_agents
            ]
            events: list[dict[str, Any]] = [
                {"event": "task_started", "task_id": task_id, "timestamp": now},
            ]
            if status == "success":
                total = max(len(planned_agents), 1)
                for index, agent in enumerate(planned_agents, start=1):
                    events.append(
                        {
                            "event": "agent_step",
                            "task_id": task_id,
                            "agent": agent,
                            "status": "running",
                            "progress": max(1, int((index - 1) * 90 / total)),
                            "timestamp": now,
                        }
                    )
                    events.append(
                        {
                            "event": "agent_step",
                            "task_id": task_id,
                            "agent": agent,
                            "status": "success",
                            "progress": min(98, int(index * 90 / total)),
                            "timestamp": now,
                        }
                    )
                events.append({"event": "task_completed", "task_id": task_id, "timestamp": now})
            task = {
                "task_id": task_id,
                "task_type": prefix,
                "status": status,
                "progress": 100 if status == "success" else 0,
                "planned_agents": planned_agents,
                "current_agent": None if terminal else (planned_agents[0] if planned_agents else None),
                "steps": steps,
                "events": events,
                "result": result or {},
                "created_at": now,
                "updated_at": now,
            }
            self._tasks[task_id] = task
            return deepcopy(task)

    def get_task(self, task_id: str) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            return None if task is None else deepcopy(task)

    def events_since(self, task_id: str, offset: int) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return [], None
            events = deepcopy(task.get("events", [])[max(offset, 0) :])
            return events, deepcopy(task)

    def update_task(
        self,
        task_id: str,
        *,
        status: str | None = None,
        progress: int | None = None,
        result: dict[str, Any] | None = None,
        error: str | None = None,
    ) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return None
            if status is not None:
                task["status"] = status
            if progress is not None:
                task["progress"] = max(0, min(progress, 100))
            if result is not None:
                task["result"] = result
            if error is not None:
                task["error"] = error
            task["updated_at"] = _now()
            return deepcopy(task)


task_manager = TaskManager()


def _now() -> str:
    return datetime.now(UTC).isoformat()
