#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
    echo "Missing production artifact: ${file#$ROOT_DIR/}"
    missing=1
  fi
done

if [ "$missing" -ne 0 ]; then
  exit 1
fi

grep -q "SPRING_PROFILES_ACTIVE: prod" "$ROOT_DIR/docker-compose.prod.yml"
grep -q "EDUPATH_ENV: prod" "$ROOT_DIR/docker-compose.prod.yml"
grep -q "proxy_pass http://backend-api:8080" "$ROOT_DIR/frontend/nginx.conf"
grep -q "VITE_API_BASE_URL: /api" "$ROOT_DIR/docker-compose.prod.yml"
grep -q "npm run test:real-api" "$ROOT_DIR/scripts/verify-production.sh"

if grep -q "env_file:" "$ROOT_DIR/docker-compose.prod.yml"; then
  echo "docker-compose.prod.yml must not inject a shared env_file into services; pass only service-specific variables."
  exit 1
fi

echo "Production readiness artifacts are present."
