#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

command -v docker >/dev/null 2>&1 || {
  echo "Configuration verification failed: Docker with the Compose plugin is required." >&2
  exit 1
}
docker compose version >/dev/null || {
  echo "Configuration verification failed: Docker Compose is unavailable." >&2
  exit 1
}

bash "$ROOT_DIR/scripts/production-readiness-check.sh"

docker compose -f "$ROOT_DIR/docker-compose.prod.yml" --env-file "$ROOT_DIR/.env.production.example" config >/dev/null
bash "$ROOT_DIR/scripts/verify-all.sh"

echo "Local verification and production Compose template parsing passed."
echo "Scope: artifacts, configuration syntax, and local tests; no deployment, credentials, external services, or production readiness validated."
