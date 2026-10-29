runtime_helper="$MISE_PROJECT_ROOT/../scripts/setup/runtime.sh"
if [[ -f "$runtime_helper" ]]; then
  source "$runtime_helper"
fi

ford_compose() {
  if [[ "${FORD_RUNTIME_ENV:-development}" != "development" ]]; then
    echo "Local Compose tasks require the dev mise environment." >&2
    return 2
  fi
  ensure_local_env
  if declare -F ford_runtime_export >/dev/null 2>&1; then
    ford_runtime_export
  fi
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    echo "Docker Compose is unavailable; install the Docker Compose plugin or docker-compose." >&2
    return 127
  fi
}

ford_observability_env() {
  local task="$MISE_PROJECT_ROOT/.mise/tasks/observability/env"
  [[ -x "$task" ]] || {
    echo "Missing observability environment task: $task" >&2
    return 1
  }
  "$task"
}

ford_compose_config_check() {
  if [[ "${FORD_RUNTIME_ENV:-development}" != "development" ]]; then
    echo "Local Compose tasks require the dev mise environment." >&2
    return 2
  fi
  ensure_local_env
  if docker compose version >/dev/null 2>&1; then
    docker compose config --quiet
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose config >/dev/null
  else
    echo "Docker Compose is unavailable; install the Docker Compose plugin or docker-compose." >&2
    return 127
  fi
}

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required command not found: $command_name" >&2
    return 1
  fi
}

local_env_value() {
  local key="$1" env_file="$MISE_PROJECT_ROOT/.env"
  [[ -f "$env_file" ]] || return 0
  awk -F= -v key="$key" '
    $1 == key {
      value = substr($0, index($0, "=") + 1)
      gsub(/^[ \t]+|[ \t]+$/, "", value)
      if (value == "\"\"" || value == sprintf("%c%c", 39, 39)) value = ""
    }
    END { printf "%s", value }
  ' "$env_file"
}

set_local_env_value() {
  local key="$1" value="$2" env_file="$MISE_PROJECT_ROOT/.env" temp_file
  if ! grep -q "^${key}=" "$env_file"; then
    printf '%s=%s\n' "$key" "$value" >> "$env_file"
    return
  fi

  temp_file="$(mktemp "$MISE_PROJECT_ROOT/.env.catalog.XXXXXX")"
  awk -v key="$key" -v replacement="$value" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
      value = substr($0, length(key) + 2)
      trimmed = value
      gsub(/^[ \t]+|[ \t]+$/, "", trimmed)
      if (trimmed == "" || trimmed == "\"\"" || trimmed == sprintf("%c%c", 39, 39)) {
        print key "=" replacement
        replaced = 1
        next
      }
    }
    { print }
    END { if (!replaced) print key "=" replacement }
  ' "$env_file" > "$temp_file"
  chmod 600 "$temp_file"
  mv "$temp_file" "$env_file"
}

require_service_running() {
  local service_name="$1"
  if ! ford_compose ps --status running --services | grep -Fxq "$service_name"; then
    echo "$service_name: not running" >&2
    return 1
  fi
  echo "$service_name: running"
}

ensure_local_env() {
  require_command openssl

  local env_file="$MISE_PROJECT_ROOT/.env"
  local grafana_password operator_password catalog_admin_password catalog_reader_password

  if [[ ! -f "$env_file" ]]; then
    grafana_password="$(openssl rand -hex 32)"
    operator_password="$(openssl rand -hex 32)"
    catalog_admin_password="$(openssl rand -hex 32)"
    catalog_reader_password="$(openssl rand -hex 32)"
    umask 077
    printf 'GRAFANA_ADMIN_PASSWORD=%s\nFORD_OPERATOR_USERNAME=operator@localhost.test\nFORD_OPERATOR_PASSWORD=%s\n' \
      "$grafana_password" "$operator_password" > "$env_file"
    printf 'FORD_CATALOG_ADMIN_USERNAME=ford_catalog_admin\nFORD_CATALOG_ADMIN_PASSWORD=%s\n' \
      "$catalog_admin_password" >> "$env_file"
    printf 'FORD_CATALOG_USERNAME=ford_catalog_reader\nFORD_CATALOG_PASSWORD=%s\n' \
      "$catalog_reader_password" >> "$env_file"
    chmod 600 "$env_file"
    echo "Created $env_file with private local credentials."
    return
  fi

  if ! grep -q '^FORD_OPERATOR_USERNAME=' "$env_file"; then
    printf 'FORD_OPERATOR_USERNAME=operator@localhost.test\n' >> "$env_file"
  fi
  if ! grep -q '^FORD_OPERATOR_PASSWORD=' "$env_file"; then
    operator_password="$(openssl rand -hex 32)"
    printf 'FORD_OPERATOR_PASSWORD=%s\n' "$operator_password" >> "$env_file"
  else
    echo "Preserving existing $env_file"
  fi

  local variable_name value
  for variable_name in FORD_CATALOG_ADMIN_USERNAME FORD_CATALOG_ADMIN_PASSWORD \
      FORD_CATALOG_USERNAME FORD_CATALOG_PASSWORD; do
    value="$(local_env_value "$variable_name")"
    [[ -n "$value" ]] && continue

    case "$variable_name" in
      FORD_CATALOG_ADMIN_USERNAME) value=ford_catalog_admin ;;
      FORD_CATALOG_USERNAME) value=ford_catalog_reader ;;
      *) value="$(openssl rand -hex 32)" ;;
    esac
    set_local_env_value "$variable_name" "$value"
  done
  chmod 600 "$env_file"
}

require_langfuse_env() {
  local missing=() variable_name
  for variable_name in LANGFUSE_PUBLIC_KEY LANGFUSE_SECRET_KEY; do
    [[ -n "${!variable_name:-}" ]] || missing+=("$variable_name")
  done
  if (( ${#missing[@]} > 0 )); then
    echo "Cloud Langfuse requires: ${missing[*]}" >&2
    return 1
  fi
}

require_grafana_cloud_env() {
  local missing=() variable_name
  for variable_name in GRAFANA_CLOUD_OTLP_ENDPOINT GRAFANA_CLOUD_INSTANCE_ID GRAFANA_CLOUD_API_TOKEN; do
    [[ -n "${!variable_name:-}" ]] || missing+=("$variable_name")
  done
  if (( ${#missing[@]} > 0 )); then
    echo "Grafana Cloud requires: ${missing[*]}" >&2
    return 1
  fi
  if [[ "$GRAFANA_CLOUD_API_TOKEN" != glc_* ]]; then
    echo "GRAFANA_CLOUD_API_TOKEN must be a Grafana Cloud Access Policy token with metrics:write, logs:write, and traces:write." >&2
    return 1
  fi
}

export_langfuse_otel_env() {
  local base_url="${LANGFUSE_BASE_URL:-https://us.cloud.langfuse.com}"
  export LANGFUSE_OTLP_ENDPOINT="${LANGFUSE_OTLP_ENDPOINT:-${base_url%/}/api/public/otel}"
}

wait_for_url() {
  local name="$1" url="$2" attempts="${3:-60}" attempt
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

ford_service_url() {
  printf 'http://localhost:%s\n' "${FORD_SERVICE_PORT:-18080}"
}

dev_state_dir() { printf '%s\n' "$MISE_PROJECT_ROOT/.mise/state"; }
dev_pid_file() { printf '%s/dev.pid\n' "$(dev_state_dir)"; }
dev_log_file() { printf '%s/dev.log\n' "$(dev_state_dir)"; }
ensure_dev_state_dir() { mkdir -p "$(dev_state_dir)"; }

read_dev_pid() {
  local pid_file="$(dev_pid_file)" pid
  [[ -f "$pid_file" ]] || return 1
  IFS= read -r pid < "$pid_file" || true
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  printf '%s\n' "$pid"
}

dev_process_running() {
  local pid="$1"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] && kill -0 "$pid" >/dev/null 2>&1
}

clear_stale_dev_pid() {
  local pid="$(read_dev_pid 2>/dev/null || true)"
  if [[ -z "$pid" ]] || ! dev_process_running "$pid"; then
    rm -f "$(dev_pid_file)"
  fi
}

application_jar() {
  local jar
  jar="$(find "$MISE_PROJECT_ROOT/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit 2>/dev/null || true)"
  [[ -n "$jar" ]] || { echo "No Spring Boot jar found under $MISE_PROJECT_ROOT/build/libs" >&2; return 1; }
  printf '%s\n' "$jar"
}
