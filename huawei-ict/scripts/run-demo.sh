#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${EDUPATH_BRIDGE_MODE:-fixture}"
PORT="${EDUPATH_PORT:-8765}"

echo "Mode: $MODE | Port: $PORT"
echo "Open: http://127.0.0.1:$PORT/"
exec python3 -m bridge.gateway --host 127.0.0.1 --port "$PORT"
