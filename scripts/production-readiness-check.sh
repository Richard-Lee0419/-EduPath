#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The historical filename is retained for callers. This checks artifacts and
# static configuration expectations only; it cannot certify a live deployment.

required_files=(
  "$ROOT_DIR/frontend/Dockerfile"
  "$ROOT_DIR/frontend/.dockerignore"
  "$ROOT_DIR/frontend/nginx.conf"
  "$ROOT_DIR/backend-api/Dockerfile"
  "$ROOT_DIR/backend-api/.dockerignore"
  "$ROOT_DIR/ai-agent-service/Dockerfile"
  "$ROOT_DIR/ai-agent-service/.dockerignore"
  "$ROOT_DIR/docker-compose.prod.yml"
  "$ROOT_DIR/.env.production.example"
  "$ROOT_DIR/scripts/verify-production.sh"
)

missing=0
for file in "${required_files[@]}"; do
  if [ ! -s "$file" ]; then
    echo "Missing deployment artifact: ${file#$ROOT_DIR/}" >&2
    missing=1
  fi
done

if [ "$missing" -ne 0 ]; then
  exit 1
fi

require_text() {
  if ! grep -Fq "$1" "$2"; then
    echo "Missing static configuration expectation '$1' in ${2#$ROOT_DIR/}" >&2
    exit 1
  fi
}

require_text "SPRING_PROFILES_ACTIVE: prod" "$ROOT_DIR/docker-compose.prod.yml"
require_text "EDUPATH_ENV: prod" "$ROOT_DIR/docker-compose.prod.yml"
require_text "proxy_pass http://backend-api:8080" "$ROOT_DIR/frontend/nginx.conf"
require_text "VITE_API_BASE_URL: /api" "$ROOT_DIR/docker-compose.prod.yml"

if grep -q "env_file:" "$ROOT_DIR/docker-compose.prod.yml"; then
  echo "docker-compose.prod.yml must not inject a shared env_file into services; pass only service-specific variables."
  exit 1
fi

echo "Deployment artifacts and static configuration expectations passed."
echo "This does not verify service startup, external dependencies, or production readiness."
