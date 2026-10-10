#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  echo "Verification failed: $*" >&2
  exit 1
}

for tool in node npm java mvn; do
  command -v "$tool" >/dev/null 2>&1 || fail "$tool is required. Install it and add it to PATH."
done
node --version >/dev/null || fail "Node could not start."
java -version >/dev/null 2>&1 || fail "Java could not start."
mvn --version >/dev/null 2>&1 || fail "Maven could not start. Check JAVA_HOME."
[ -d "$ROOT_DIR/frontend/node_modules" ] || fail "frontend/node_modules is missing. Run npm ci in frontend."

if [ -n "${PYTHON:-}" ]; then
  PYTHON_BIN="$PYTHON"
elif [ -x "$ROOT_DIR/ai-agent-service/.venv/bin/python" ]; then
  PYTHON_BIN="$ROOT_DIR/ai-agent-service/.venv/bin/python"
elif [ -x "$ROOT_DIR/ai-agent-service/.venv/Scripts/python.exe" ]; then
  PYTHON_BIN="$ROOT_DIR/ai-agent-service/.venv/Scripts/python.exe"
else
  fail "AI virtual environment is missing. Create ai-agent-service/.venv, install '.[dev]', or set PYTHON to a prepared interpreter."
fi
PYTHON_BIN="$(command -v "$PYTHON_BIN")" || fail "Configured Python interpreter could not be found."
if [[ "$PYTHON_BIN" != /* && "$PYTHON_BIN" == */* ]]; then
  PYTHON_BIN="$(cd "$(dirname "$PYTHON_BIN")" && pwd)/$(basename "$PYTHON_BIN")"
fi

"$PYTHON_BIN" -c 'import sys; assert sys.version_info >= (3, 11); import fastapi, pydantic, pytest, httpx, yaml, pypdf, docx, pptx' \
  || fail "Python 3.11+ and AI/dev dependencies are required. Install ai-agent-service with pip install -e '.[dev]'."

# These are repeatable local checks, not live model or deployment validation.
export EDUPATH_ENV=dev VECTOR_STORE_PROVIDER=local EMBEDDING_PROVIDER=local
export EMBEDDING_MODEL=local-hash-v1 EMBEDDING_STRICT=false EMBEDDING_API_KEY=''
export LLM_PROVIDER='' LLM_BASE_URL='' LLM_MODEL='' LLM_API_KEY=''
unset EDUPATH_SEED_CORPUS_PATH

bash "$ROOT_DIR/scripts/production-readiness-check.sh"
(cd "$ROOT_DIR/frontend" && npm test && npm run type-check && npm run build)
(cd "$ROOT_DIR/backend-api" && mvn test)
(cd "$ROOT_DIR/ai-agent-service" && "$PYTHON_BIN" -m pytest)

if [ -d "$ROOT_DIR/scripts/tests" ]; then
  (cd "$ROOT_DIR" && "$PYTHON_BIN" -m pytest scripts/tests)
fi

echo "Local tests, type checking, build, and deployment artifact checks passed."
echo "Scope: deterministic/mocked checks; no live model, deployed workflow, or production readiness claim."
