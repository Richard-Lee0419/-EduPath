from functools import lru_cache
from importlib.resources import files

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.rag import tree_diagnosis


router = APIRouter(prefix="/evaluation/tree-practice", tags=["evaluation"])


class DiagnosisRequest(BaseModel):
    topic: str
    order: str
    nodes: list[dict] = Field(max_length=15)
    answer: list[str] = Field(max_length=24)


@lru_cache(maxsize=1)
def public_handout() -> dict[str, str]:
    resource = files("app.rag").joinpath(tree_diagnosis.DOCUMENT)
    return {"document_name": resource.name, "content": resource.read_text(encoding="utf-8")}


@router.post("/diagnose")
def diagnose(request: DiagnosisRequest) -> dict[str, object]:
    result = tree_diagnosis.diagnose(request.model_dump(), [public_handout()])
    if result["decision"] not in {"confirm", "diagnose"}:
        raise HTTPException(422, result["explanation"])
    return result
