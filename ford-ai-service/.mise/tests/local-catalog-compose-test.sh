#!/usr/bin/env bash
set -euo pipefail

service_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
temp_root="$(mktemp -d)"
trap 'rm -rf "$temp_root"' EXIT

export MISE_PROJECT_ROOT="$temp_root"
source "$service_root/.mise/lib/compose.sh"
ensure_local_env >/dev/null

docker compose --env-file "$temp_root/.env" \
  -f "$service_root/docker-compose.yml" \
  -f "$service_root/docker-compose.core.yml" \
  config --format json > "$temp_root/compose.json"

python3 - "$temp_root/compose.json" <<'PY'
import json
import sys

compose = json.load(open(sys.argv[1]))
catalog = compose["services"]["catalog-postgres"]
assert catalog["environment"]["POSTGRES_DB"] == "ford_catalog"
assert catalog["ports"] == [
    {"mode": "ingress", "host_ip": "127.0.0.1", "published": "55433", "target": 5432, "protocol": "tcp"}
]
assert "ford-catalog-postgres" in compose["volumes"]
assert "ford_catalog" not in compose["services"]["postgres"]["environment"]["POSTGRES_DB"]
assert compose["services"]["ford-service"]["environment"]["FORD_CATALOG_TOOLS_ENABLED"] == "false"
assert compose["services"]["ford-service"]["environment"]["FORD_CATALOG_JDBC_URL"] == (
    "jdbc:postgresql://catalog-postgres:5432/ford_catalog"
)
print("local catalog Compose isolation validated")
PY
