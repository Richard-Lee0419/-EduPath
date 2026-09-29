from __future__ import annotations

import json
import time

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse

from app.services.task_manager import task_manager

router = APIRouter(prefix="/tasks", tags=["tasks"])


@router.get("/{task_id}")
def get_task(task_id: str) -> dict[str, object]:
    task = task_manager.get_task(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=f"task not found: {task_id}")
    return task


@router.get("/{task_id}/stream")
def stream_task(task_id: str) -> StreamingResponse:
    task = task_manager.get_task(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=f"task not found: {task_id}")

    def events():
        offset = 0
        last_status: tuple[object, object, object] | None = None
        while True:
            new_events, snapshot = task_manager.events_since(task_id, offset)
            if snapshot is None:
                return
            status_key = (
                snapshot.get("status"),
                snapshot.get("progress"),
                snapshot.get("current_agent"),
            )
            if status_key != last_status:
                yield "event: task_status\n"
                yield f"data: {json.dumps(snapshot, ensure_ascii=False)}\n\n"
                last_status = status_key
            for item in new_events:
                event_name = str(item.get("event") or "task_event")
                yield f"event: {event_name}\n"
                yield f"data: {json.dumps(item, ensure_ascii=False)}\n\n"
            offset += len(new_events)
            if snapshot.get("status") in {"success", "failed", "cancelled"}:
                yield "event: done\n"
                yield f"data: {json.dumps({'task_id': task_id}, ensure_ascii=False)}\n\n"
                return
            time.sleep(0.15)

    return StreamingResponse(events(), media_type="text/event-stream")


@router.post("/{task_id}/cancel")
def cancel_task(task_id: str) -> dict[str, object]:
    task = task_manager.get_task(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=f"task not found: {task_id}")
    return task_manager.cancel_task(task_id) or task
