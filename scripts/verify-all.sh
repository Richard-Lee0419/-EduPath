#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bash "$ROOT_DIR/scripts/production-readiness-check.sh"

if [ -d "$ROOT_DIR/frontend/node_modules" ]; then
  (cd "$ROOT_DIR/frontend" && npm run test:real-api && npm run type-check && npm run build)
else
  echo "Skipping frontend checks: frontend/node_modules is missing. Run npm install in frontend first."
fi

if command -v mvn >/dev/null 2>&1; then
  (cd "$ROOT_DIR/backend-api" && mvn test)
else
  echo "Skipping backend checks: mvn is not installed."
fi

if [ -x "$ROOT_DIR/ai-agent-service/.venv/bin/python" ]; then
  (cd "$ROOT_DIR/ai-agent-service" && .venv/bin/python -m pytest)
else
  echo "Skipping AI checks: ai-agent-service/.venv is missing. Create it and install dependencies first."
fi
