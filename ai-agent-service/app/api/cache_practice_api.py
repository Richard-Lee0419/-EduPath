import json
import logging
import os
from functools import lru_cache
from importlib.resources import files

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.rag import cache_diagnosis
from app.services.agentarts_workflow import WorkflowError, invoke_cache

router = APIRouter(prefix="/evaluation/cache-practice", tags=["evaluation"])
log = logging.getLogger(__name__)


class DiagnosisRequest(BaseModel):
    query: str = Field(min_length=1, max_length=500)


@lru_cache(maxsize=1)
def public_handout() -> dict[str, str]:
    resource = files("app.rag").joinpath("cache-address-diagnosis.md")
    return {"document_name": resource.name, "content": resource.read_text(encoding="utf-8")}


@router.post("/diagnose")
def diagnose(request: DiagnosisRequest) -> dict[str, object]:
    mode = os.getenv("EDUPATH_CACHE_DIAGNOSIS_PROVIDER", "local").strip()
    if mode not in {"local", "agentarts"}:
        raise HTTPException(503, "Cache 诊断模式配置错误")

    _, state = cache_diagnosis.prepare(request.query)
    baseline = cache_diagnosis.complete(state, [public_handout()])
    citations = json.loads(baseline["citations_json"])
    if baseline["decision"] not in {"confirm", "diagnose"} or not citations:
        raise HTTPException(422, "题目、作答或公开讲义未通过诊断检查")
    if mode == "local":
        return {
            "decision": baseline["decision"],
            "mistake": baseline["mistake"],
            "explanation": baseline["answer"].partition("\n\n依据 [E1]")[0],
            "citation": citations[0],
        }

    try:
        remote = invoke_cache(
            os.getenv("AGENTARTS_CACHE_ENDPOINT", ""),
            os.getenv("AGENTARTS_CACHE_API_KEY", ""),
            request.query,
            auth_mode=os.getenv("AGENTARTS_CACHE_AUTH_MODE", "api_key").strip(),
            access_key=os.getenv("AGENTARTS_CACHE_IAM_AK", ""),
            secret_key=os.getenv("AGENTARTS_CACHE_IAM_SK", ""),
            security_token=os.getenv("AGENTARTS_CACHE_IAM_SECURITY_TOKEN", ""),
        )
    except WorkflowError as failure:
        log.warning("AgentArts Cache diagnosis failed: %s", failure)
        raise HTTPException(502, str(failure)) from None

    source = remote.get("citation")
    expected = citations[0]
    explanation = remote.get("explanation")
    if (remote.get("decision") != baseline["decision"] or remote.get("mistake") != baseline["mistake"]
            or not isinstance(explanation, str) or not explanation.strip() or len(explanation) > 4000
            or not isinstance(source, dict)
            or source.get("document") not in {cache_diagnosis.DOCUMENT, cache_diagnosis.DOCUMENT + ".md"}
            or any(source.get(key) != expected[key] for key in ("marker", "section", "excerpt", "scope", "source_version"))):
        raise HTTPException(502, "AgentArts 判定或讲义引用校验失败")
    receipt = remote["provenance"]
    log.info("AgentArts Cache request_id=%s execution_id=%s", receipt["request_id"], receipt["execution_id"])
    return {
        "decision": remote["decision"], "mistake": remote["mistake"], "explanation": explanation,
        "citation": source, "provenance": receipt,
    }
