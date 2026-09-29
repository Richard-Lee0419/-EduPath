#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bash "$ROOT_DIR/scripts/production-readiness-check.sh"

(cd "$ROOT_DIR/frontend" && npm run test:real-api && npm run build)
(cd "$ROOT_DIR/backend-api" && mvn test)

if [ -x "$ROOT_DIR/ai-agent-service/.venv/bin/python" ]; then
  (cd "$ROOT_DIR/ai-agent-service" && .venv/bin/python -m pytest)
else
  echo "Skipping AI pytest: ai-agent-service/.venv is missing."
fi

docker compose -f "$ROOT_DIR/docker-compose.prod.yml" --env-file "$ROOT_DIR/.env.production.example" config >/dev/null

echo "Production verification completed."
