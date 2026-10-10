#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
mkdir -p output/validation
export EDUPATH_ENV=dev VECTOR_STORE_PROVIDER=local EMBEDDING_PROVIDER=local
export EMBEDDING_MODEL=local-hash-v1 EMBEDDING_STRICT=false COMPETITION_MODE=false
export LLM_PROVIDER='' LLM_BASE_URL='' LLM_MODEL='' LLM_API_KEY='' XFYUN_API_KEY=''
export EMAIL_ENABLED=false
unset EDUPATH_SEED_CORPUS_PATH

(cd ai-agent-service && exec python -m uvicorn app.main:app --host 127.0.0.1 --port 8000) \
  >output/validation/ai-service.log 2>&1 &
ai_pid=$!
java -jar backend-api/target/backend-api-1.0.0-rc.1.jar \
  --spring.profiles.active=test --server.address=127.0.0.1 --server.port=8080 \
  --edupath.ai-service-base-url=http://127.0.0.1:8000 \
  >output/validation/backend.log 2>&1 &
java_pid=$!
frontend_pid=''
trap 'kill "$ai_pid" "$java_pid" ${frontend_pid:+"$frontend_pid"} 2>/dev/null || true' EXIT

ready=false
for attempt in {1..60}; do
  if curl --fail --silent --max-time 2 http://127.0.0.1:8000/health >/dev/null \
    && curl --fail --silent --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null; then
    ready=true
    break
  fi
  kill -0 "$ai_pid" "$java_pid" 2>/dev/null || break
  sleep 2
done
if [ "$ready" != true ]; then
  tail -n 60 output/validation/ai-service.log output/validation/backend.log
  echo "Local services failed to become healthy." >&2
  exit 1
fi
python scripts/smoke_learning_flow.py --timeout 90 --output output/validation/learning-flow.json
python scripts/smoke_knowledge_lifecycle.py --confirm-isolated-local --timeout 90 \
  --output output/validation/kb-lifecycle.json

if [ "${EDUPATH_BROWSER_TESTS:-false}" = true ]; then
  (cd frontend && VITE_PROXY_TARGET=http://127.0.0.1:8080 exec node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort) \
    >output/validation/frontend.log 2>&1 &
  frontend_pid=$!
  ready=false
  for attempt in {1..30}; do
    if curl --fail --silent --max-time 2 http://127.0.0.1:5173 >/dev/null; then
      ready=true
      break
    fi
    kill -0 "$frontend_pid" 2>/dev/null || break
    sleep 2
  done
  if [ "$ready" != true ]; then
    tail -n 60 output/validation/frontend.log
    exit 1
  fi
  (cd frontend && npm run test:e2e)
fi
