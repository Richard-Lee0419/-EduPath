from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import evaluation_api, kb_api, path_api, profile_api, quiz_api, resource_api, task_api, tutor_api
from app.core.config import settings
from app.rag.retriever import retriever


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.validate_for_startup()
    yield


app = FastAPI(
    title="EduPath AI Agent Service",
    version="0.1.0",
    description="Internal multi-agent/RAG service for EduPath.",
    lifespan=lifespan,
)


@app.get("/health", tags=["system"])
def health() -> dict[str, str]:
    return {"service": "ai-agent-service", "status": "ok"}


@app.get("/runtime", tags=["system"])
def runtime() -> dict[str, object]:
    return settings.public_runtime()


@app.get("/readiness", tags=["system"])
def readiness() -> dict[str, object]:
    runtime_info = settings.public_runtime()
    corpus = retriever.corpus_summary()
    llm_configured = bool(runtime_info["llm"]["configured"])
    embedding_configured = bool(runtime_info["embedding"]["configured"])
    corpus_ready = int(corpus.get("chunks", 0)) > 0 and int(corpus.get("external_documents", 0)) > 0
    fallback_ready = int(corpus.get("chunks", 0)) > 0
    live_generation_ready = llm_configured and embedding_configured and corpus_ready
    status = "ready" if live_generation_ready else "degraded" if fallback_ready else "blocked"
    return {
        "service": "ai-agent-service",
        "status": status,
        "live_generation_ready": live_generation_ready,
        "prepared_demo_supported": fallback_ready,
        "checks": {
            "llm_configured": llm_configured,
            "embedding_configured": embedding_configured,
            "course_corpus_ready": corpus_ready,
        },
        "runtime": runtime_info,
        "corpus": corpus,
        "validation_scope": "service_configuration_and_local_dependencies",
    }


app.include_router(profile_api.router)
app.include_router(resource_api.router)
app.include_router(tutor_api.router)
app.include_router(path_api.router)
app.include_router(quiz_api.router)
app.include_router(evaluation_api.router)
app.include_router(kb_api.router)
app.include_router(task_api.router)
