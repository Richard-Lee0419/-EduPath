#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
mkdir -p output/validation
# Intended only for a disposable CI runner. This script removes this project's volumes.
if [ "${CI:-false}" != true ]; then
  echo "Docker smoke is restricted to an isolated CI runner." >&2
  exit 1
fi
cleanup() {
  docker compose --project-name edupath-ci logs --no-color >output/validation/docker-services.log 2>&1 || true
  docker compose --project-name edupath-ci down --volumes --remove-orphans || true
}
trap cleanup EXIT
docker compose --project-name edupath-ci config --quiet
docker compose --project-name edupath-ci up --detach --wait --wait-timeout 720
ready=false
for attempt in {1..120}; do
  if curl --fail --silent --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null \
    && curl --fail --silent --max-time 2 http://127.0.0.1:5173 >/dev/null; then
    ready=true
    break
  fi
  sleep 2
done
if [ "$ready" != true ]; then
  echo "Container API/frontend failed to become healthy." >&2
  exit 1
fi
python scripts/smoke_learning_flow.py --vector-provider chroma --timeout 120 --output output/validation/docker-learning-flow.json
python scripts/smoke_knowledge_lifecycle.py --confirm-isolated-local --vector-provider chroma \
  --timeout 120 --output output/validation/docker-kb-lifecycle.json
