#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${1:-/opt/edupath}"
ENV_FILE="${APP_DIR}/.env.production"
BRIDGE_HOST="${EDUPATH_MYSQL_BRIDGE_HOST:-172.17.0.1}"
BRIDGE_PORT="${EXTERNAL_DB_PORT:-3307}"
MYSQL_HOST="${EDUPATH_SYSTEM_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${EDUPATH_SYSTEM_MYSQL_PORT:-3306}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}" >&2
  exit 1
fi

if ! command -v socat >/dev/null 2>&1; then
  echo "socat is required. Install it before running this script." >&2
  exit 1
fi

python3 - "${ENV_FILE}" <<'PY'
import subprocess
import sys
from pathlib import Path

env = {}
for raw in Path(sys.argv[1]).read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    env[key] = value


def ident(value: str) -> str:
    return "`" + value.replace("`", "``") + "`"


def literal(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


database = env.get("MYSQL_DATABASE", "edupath")
user = env.get("MYSQL_USER", "edupath")
password = env["MYSQL_PASSWORD"]
hosts = ["localhost", "127.0.0.1", "%"]
statements = [
    f"CREATE DATABASE IF NOT EXISTS {ident(database)} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
]
for host in hosts:
    statements.extend(
        [
            f"CREATE USER IF NOT EXISTS {literal(user)}@{literal(host)} IDENTIFIED BY {literal(password)};",
            f"ALTER USER {literal(user)}@{literal(host)} IDENTIFIED BY {literal(password)};",
            f"GRANT ALL PRIVILEGES ON {ident(database)}.* TO {literal(user)}@{literal(host)};",
        ]
    )
statements.append("FLUSH PRIVILEGES;")
subprocess.run(["mysql", "-uroot"], input="\n".join(statements).encode(), check=True)
print("system-mysql-ready")
PY

cat >/etc/systemd/system/edupath-mysql-bridge.service <<EOF
[Unit]
Description=EduPath MySQL bridge for Docker containers
After=mysql.service docker.service network-online.target
Requires=mysql.service

[Service]
ExecStart=/usr/bin/socat TCP-LISTEN:${BRIDGE_PORT},bind=${BRIDGE_HOST},reuseaddr,fork TCP:${MYSQL_HOST}:${MYSQL_PORT}
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now edupath-mysql-bridge.service

if grep -q '^EXTERNAL_DB_PORT=' "${ENV_FILE}"; then
  sed -i "s#^EXTERNAL_DB_PORT=.*#EXTERNAL_DB_PORT=${BRIDGE_PORT}#" "${ENV_FILE}"
else
  printf '\nEXTERNAL_DB_PORT=%s\n' "${BRIDGE_PORT}" >> "${ENV_FILE}"
fi

chmod 600 "${ENV_FILE}"
systemctl --no-pager --plain status edupath-mysql-bridge.service | sed -n '1,8p'
ss -ltnp | grep ":${BRIDGE_PORT}"
