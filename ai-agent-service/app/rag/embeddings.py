from __future__ import annotations

import math
import os
import re
from collections import Counter
from hashlib import sha256
from typing import Any


TOKEN_RE = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]")


class EmbeddingClient:
    """Embedding provider boundary."""

    def __init__(
        self,
        dense_dimension: int = 384,
        *,
        provider: str | None = None,
        model: str | None = None,
        strict: bool | None = None,
        cache_dir: str | None = None,
        device: str | None = None,
    ) -> None:
        self.dense_dimension = dense_dimension
        self.provider = (provider or os.getenv("EMBEDDING_PROVIDER") or "local").strip().lower()
        if not self.provider:
            self.provider = "local"
        self.model = (model or os.getenv("EMBEDDING_MODEL") or self._default_model()).strip()
        self.strict = _env_bool("EMBEDDING_STRICT", False) if strict is None else strict
        self.cache_dir = (cache_dir or os.getenv("EMBEDDING_CACHE_DIR") or "").strip() or None
        self.device = (device or os.getenv("EMBEDDING_DEVICE") or "").strip() or None
        self._sentence_transformer: Any | None = None
        self._load_error: str | None = None

    @property
    def prefers_dense(self) -> bool:
        return self.provider in {"huggingface", "hf", "sentence-transformers", "sentence_transformers"}

    def embed(self, text: str) -> dict[str, float]:
        tokens = tokenize(text)
        if not tokens:
            return {}
        counts = Counter(tokens)
        norm = math.sqrt(sum(value * value for value in counts.values()))
        if norm == 0:
            return {}
        return {token: count / norm for token, count in counts.items()}

    def embed_dense(self, text: str) -> list[float]:
        if self.prefers_dense:
            try:
                return self._embed_dense_huggingface(text)
            except Exception as exception:
                self._load_error = str(exception)
                if self.strict:
                    raise RuntimeError(
                        f"Failed to load Hugging Face embedding model '{self.model}'. "
                        "Install the embedding extra and make sure the model is available."
                    ) from exception
        sparse = self.embed(text)
        vector = [0.0] * self.dense_dimension
        for token, weight in sparse.items():
            index = int.from_bytes(sha256(token.encode("utf-8")).digest()[:4], "big") % self.dense_dimension
            vector[index] += weight
        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0:
            return vector
        return [value / norm for value in vector]

    def _embed_dense_huggingface(self, text: str) -> list[float]:
        model = self._load_sentence_transformer()
        encoded = model.encode(
            [text or ""],
            normalize_embeddings=True,
            show_progress_bar=False,
        )
        first = encoded[0]
        if hasattr(first, "tolist"):
            return [float(value) for value in first.tolist()]
        return [float(value) for value in first]

    def _load_sentence_transformer(self) -> Any:
        if self._sentence_transformer is not None:
            return self._sentence_transformer
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as exception:
            raise RuntimeError("sentence-transformers is not installed") from exception

        kwargs: dict[str, str] = {}
        if self.cache_dir:
            kwargs["cache_folder"] = self.cache_dir
        if self.device:
            kwargs["device"] = self.device
        self._sentence_transformer = SentenceTransformer(self.model, **kwargs)
        return self._sentence_transformer

    def _default_model(self) -> str:
        if self.provider in {"huggingface", "hf", "sentence-transformers", "sentence_transformers"}:
            return "BAAI/bge-m3"
        return "local-hash-v1"


def tokenize(text: str) -> list[str]:
    normalized = (text or "").lower()
    base_tokens = TOKEN_RE.findall(normalized)
    cjk_sequence = "".join(token for token in base_tokens if _is_cjk(token))
    bigrams = [cjk_sequence[index : index + 2] for index in range(max(0, len(cjk_sequence) - 1))]
    trigrams = [cjk_sequence[index : index + 3] for index in range(max(0, len(cjk_sequence) - 2))]
    return base_tokens + bigrams + trigrams


def cosine_similarity(left: dict[str, float], right: dict[str, float]) -> float:
    if not left or not right:
        return 0.0
    if len(left) > len(right):
        left, right = right, left
    return sum(value * right.get(token, 0.0) for token, value in left.items())


def _is_cjk(token: str) -> bool:
    return len(token) == 1 and "\u4e00" <= token <= "\u9fff"


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}
