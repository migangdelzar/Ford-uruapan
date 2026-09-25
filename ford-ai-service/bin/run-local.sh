#!/usr/bin/env bash

set -euo pipefail
shopt -s extglob

service_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$service_dir"

if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
elif [[ -x /Users/miguelangeldelgadillozarate/homebrew/bin/docker-compose ]]; then
  compose=(/Users/miguelangeldelgadillozarate/homebrew/bin/docker-compose)
else
  echo "Docker Compose is required" >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  echo "Missing $service_dir/.env" >&2
  exit 1
fi

"${compose[@]}" --env-file .env -f docker-compose.yml up -d postgres redis

while IFS='=' read -r key value || [[ -n "${key:-}${value:-}" ]]; do
  [[ "$key" =~ ^[[:space:]]*(export[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*[[:space:]]*$ ]] || continue
  key="${key#export }"
  key="${key##+([[:space:]])}"
  key="${key%%+([[:space:]])}"
  value="${value%$'\r'}"
  if [[ "$value" != \"*\" && "$value" != \'*\' ]]; then
    value="${value%%[[:space:]]#*}"
  fi
  export "$key=$value"
done < .env

export FORD_DATABASE_URL="${FORD_DATABASE_URL:-jdbc:postgresql://localhost:5432/ford_service}"
export FORD_DATABASE_USERNAME="${FORD_DATABASE_USERNAME:-ford}"
export FORD_DATABASE_PASSWORD="${FORD_DATABASE_PASSWORD:-ford}"
export SPRING_DATA_REDIS_HOST="${SPRING_DATA_REDIS_HOST:-localhost}"
export SPRING_DATA_REDIS_PORT="${SPRING_DATA_REDIS_PORT:-6379}"
export FORD_AI_LOCAL_BASE_URL="${FORD_AI_LOCAL_BASE_URL:-http://localhost:11434}"

exec ./gradlew bootRun --no-daemon
