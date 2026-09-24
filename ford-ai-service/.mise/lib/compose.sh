#!/usr/bin/env bash

set -euo pipefail

require_command() {
  local command_name="$1"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required command not found: $command_name" >&2
    return 1
  fi
}
ford_compose() {
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
    return
  fi

  require_command docker
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi

  echo "Docker Compose is required. Install Docker Desktop or docker-compose." >&2
  return 1
}

ensure_local_env() {
  if [[ -f "$MISE_PROJECT_ROOT/.env" ]]; then
    echo "Preserving existing $MISE_PROJECT_ROOT/.env"
    return
  fi

  require_command openssl

  local grafana_password
  grafana_password="$(openssl rand -hex 32)"

  umask 077
  printf 'GRAFANA_ADMIN_PASSWORD=%s\n' "$grafana_password" > "$MISE_PROJECT_ROOT/.env"
  echo "Created $MISE_PROJECT_ROOT/.env with a generated Grafana admin password."
  echo "The file is ignored by Git; keep it safe for local access to Grafana."
}

wait_for_url() {
  local name="$1"
  local url="$2"
  local attempts="${3:-60}"
  local attempt

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl --fail --silent --show-error --max-time 3 "$url" >/dev/null 2>&1; then
      echo "$name: ready"
      return
    fi
    sleep 2
  done

  echo "$name: not ready after $((attempts * 2)) seconds ($url)" >&2
  return 1
}
