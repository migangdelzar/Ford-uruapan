#!/usr/bin/env bash
set -euo pipefail

ford_runtime_profile() {
  printf '%s\n' "${FORD_COLIMA_PROFILE:-emme}"
}

ford_runtime_linux_socket() {
  printf '%s/docker.sock\n' "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
}

ford_runtime_colima_socket() {
  printf '%s/.colima/%s/docker.sock\n' "${HOME:?HOME is required}" "$(ford_runtime_profile)"
}

ford_runtime_context() {
  if command -v docker >/dev/null 2>&1; then
    docker context show 2>/dev/null || true
  fi
}

ford_runtime_export() {
  local platform context linux_socket colima_socket
  platform="$(uname -s)"
  context="$(ford_runtime_context)"

  if [[ -n "${DOCKER_HOST:-}" ]]; then
    if [[ -z "${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-}" && "$DOCKER_HOST" == unix://* ]]; then
      export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${DOCKER_HOST#unix://}"
    fi
    return
  fi

  case "$platform" in
    Darwin)
      colima_socket="$(ford_runtime_colima_socket)"
      if [[ "$context" == "colima" || "$context" == "${FORD_COLIMA_PROFILE:-emme}" || -S "$colima_socket" ]]; then
        export DOCKER_HOST="unix://${colima_socket}"
        export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
      fi
      ;;
    Linux)
      linux_socket="$(ford_runtime_linux_socket)"
      if [[ "$context" == "rootless" || -S "$linux_socket" ]]; then
        export DOCKER_HOST="unix://${linux_socket}"
        export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="$linux_socket"
      fi
      ;;
  esac
}

ford_runtime_status() {
  local platform context endpoint
  platform="$(uname -s)"
  context="$(ford_runtime_context)"
  endpoint="${DOCKER_HOST:-default Docker endpoint}"

  printf 'Platform: %s\n' "$platform"
  printf 'Docker context: %s\n' "${context:-default}"
  printf 'Docker endpoint: %s\n' "$endpoint"
  if [[ -n "${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-}" ]]; then
    printf 'Testcontainers socket: %s\n' "$TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE"
  fi
}
