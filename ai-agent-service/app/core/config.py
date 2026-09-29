from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    vector_store_provider: str
    chroma_url: str
    chroma_collection: str
    llm_provider: str
    llm_base_url: str
    llm_model: str
    llm_api_key: str
    llm_timeout_seconds: float
    embedding_provider: str
    embedding_model: str
    embedding_api_key: str
    environment: str = "dev"
    embedding_strict: bool = False
    embedding_cache_dir: str = ""
    embedding_device: str = ""
    seed_corpus_path: str = ""
    max_ingest_bytes: int = 20 * 1024 * 1024
    llm_max_retries: int = 2
    llm_max_output_tokens: int = 8192
    llm_temperature: float = 0.2
    agent_task_workers: int = 2

    @classmethod
    def from_env(cls) -> "Settings":
        llm_api_key = _env("LLM_API_KEY", "")
        llm_provider = _env("LLM_PROVIDER", "deepseek" if llm_api_key else "")
        deepseek_default = llm_provider.lower() == "deepseek"
        return cls(
            environment=_env("EDUPATH_ENV", _env("ENV", "dev")).lower(),
            vector_store_provider=_env("VECTOR_STORE_PROVIDER", "local").lower(),
            chroma_url=_env("CHROMA_URL", "http://localhost:8001"),
            chroma_collection=_env("CHROMA_COLLECTION", "edupath_course_chunks"),
            llm_provider=llm_provider,
            llm_base_url=_env("LLM_BASE_URL", "https://api.deepseek.com" if deepseek_default else ""),
            llm_model=_env("LLM_MODEL", "deepseek-chat" if deepseek_default else ""),
            llm_api_key=llm_api_key,
            llm_timeout_seconds=float(_env("LLM_TIMEOUT_SECONDS", "30")),
            embedding_provider=_env("EMBEDDING_PROVIDER", "local").lower(),
            embedding_model=_env("EMBEDDING_MODEL", "local-hash-v1"),
            embedding_api_key=_env("EMBEDDING_API_KEY", ""),
            embedding_strict=_env_bool("EMBEDDING_STRICT", False),
            embedding_cache_dir=_env("EMBEDDING_CACHE_DIR", ""),
            embedding_device=_env("EMBEDDING_DEVICE", ""),
            seed_corpus_path=_env("EDUPATH_SEED_CORPUS_PATH", ""),
            max_ingest_bytes=int(_env("MAX_INGEST_BYTES", str(20 * 1024 * 1024))),
            llm_max_retries=int(_env("LLM_MAX_RETRIES", "2")),
            llm_max_output_tokens=int(_env("LLM_MAX_OUTPUT_TOKENS", "8192")),
            llm_temperature=float(_env("LLM_TEMPERATURE", "0.2")),
            agent_task_workers=max(1, int(_env("AGENT_TASK_WORKERS", "2"))),
        )

    @property
    def is_production(self) -> bool:
        return self.environment in {"prod", "production"}

    def validate_for_startup(self) -> None:
        if not self.is_production:
            return

        missing = [
            name
            for name, value in {
                "LLM_PROVIDER": self.llm_provider,
                "LLM_BASE_URL": self.llm_base_url,
                "LLM_MODEL": self.llm_model,
                "LLM_API_KEY": self.llm_api_key,
                "CHROMA_URL": self.chroma_url,
                "CHROMA_COLLECTION": self.chroma_collection,
            }.items()
            if not value
        ]
        if missing:
            raise RuntimeError(f"prod AI service missing required settings: {', '.join(missing)}")
        if self.vector_store_provider != "chroma":
            raise RuntimeError("prod AI service requires VECTOR_STORE_PROVIDER=chroma")
        if self.embedding_provider in {"local", "", "local-hash-v1"}:
            raise RuntimeError("prod AI service requires a non-local embedding provider")
        if not self.embedding_strict:
            raise RuntimeError("prod AI service requires EMBEDDING_STRICT=true")
        if not self.embedding_model or self.embedding_model == "local-hash-v1":
            raise RuntimeError("prod AI service requires a real EMBEDDING_MODEL")
        seed_path = Path(self.seed_corpus_path) if self.seed_corpus_path else _default_seed_corpus_path()
        if not seed_path.is_file():
            raise RuntimeError(
                "prod AI service requires a generated two-course EDUPATH_SEED_CORPUS_PATH"
            )

    def public_runtime(self) -> dict[str, object]:
        return {
            "environment": self.environment,
            "vector_store": {
                "provider": self.vector_store_provider,
                "chroma_url": self.chroma_url if self.vector_store_provider == "chroma" else "",
                "collection": self.chroma_collection,
            },
            "llm": {
                "provider": self.llm_provider,
                "base_url": self.llm_base_url,
                "model": self.llm_model,
                "configured": bool(self.llm_provider and self.llm_api_key),
                "timeout_seconds": self.llm_timeout_seconds,
                "max_retries": self.llm_max_retries,
            },
            "embedding": {
                "provider": self.embedding_provider,
                "model": self.embedding_model,
                "configured": self.embedding_provider
                in {"local", "huggingface", "hf", "sentence-transformers", "sentence_transformers"}
                or bool(self.embedding_api_key),
                "strict": self.embedding_strict,
            },
        }


def _env(name: str, default: str) -> str:
    value = os.getenv(name)
    return default if value is None else value.strip()


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


settings = Settings.from_env()


def _default_seed_corpus_path() -> Path:
    return Path(__file__).resolve().parents[2] / "data" / "seed_corpus" / "seed_documents.jsonl"
