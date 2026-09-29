FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_DEFAULT_TIMEOUT=120 \
    PIP_RETRIES=5

ARG PIP_INDEX_URL=https://pypi.org/simple
ARG TORCH_VERSION=2.5.1+cpu
ARG TORCH_EXTRA_INDEX_URL=https://download.pytorch.org/whl/cpu
ENV PIP_INDEX_URL=${PIP_INDEX_URL}

WORKDIR /app

RUN adduser --disabled-password --gecos "" edupath
COPY pyproject.toml README.md ./
COPY app ./app
COPY data ./data
RUN pip install --prefer-binary --upgrade "setuptools<82" wheel \
    && pip install --prefer-binary filelock typing-extensions networkx jinja2 fsspec sympy==1.13.1 \
    && pip install --prefer-binary --no-deps --extra-index-url "${TORCH_EXTRA_INDEX_URL}" "torch==${TORCH_VERSION}" \
    && pip install --prefer-binary --no-build-isolation -e ".[vector,embedding]"
RUN mkdir -p /tmp/edupath-embeddings \
    && chown -R edupath:edupath /tmp/edupath-embeddings

USER edupath
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3).read()" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
