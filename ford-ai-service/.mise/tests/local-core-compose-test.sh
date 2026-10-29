#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
GRAFANA_ADMIN_PASSWORD=unused docker compose -f "$project_root/docker-compose.yml" \
  -f "$project_root/docker-compose.core.yml" config --format json |
  python3 -c '
import json, sys
config = json.load(sys.stdin)
services = config["services"]
dependencies = services["ford-service"].get("depends_on", {})
assert set(dependencies) == {"postgres", "redis"}, dependencies
env = services["ford-service"]["environment"]
assert str(env["FORD_OTEL_TRACES_EXPORT_ENABLED"]).lower() == "false", env
assert str(env["FORD_OTEL_METRICS_EXPORT_ENABLED"]).lower() == "false", env
print("local core Compose override validated")
'

if grep -q 'mise run telemetry:start' "$project_root/.mise/tasks/deps/start"; then
  echo "deps:start must not start telemetry in the core-only local mode" >&2
  exit 1
fi

grep -q 'ford_compose up --build -d postgres redis ford-service' "$project_root/.mise/tasks/container/up"
grep -q 'mise run telemetry:start' "$project_root/.mise/tasks/full/start"
grep -q 'COMPOSE_FILE = "docker-compose.yml:docker-compose.core.yml"' "$project_root/mise.dev.toml"
test ! -e "$project_root/mise.local.toml"
