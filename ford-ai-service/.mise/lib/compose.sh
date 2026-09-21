runtime_helper="$MISE_PROJECT_ROOT/../scripts/setup/runtime.sh"
if [[ -f "$runtime_helper" ]]; then
  source "$runtime_helper"
fi

ford_compose() {
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
