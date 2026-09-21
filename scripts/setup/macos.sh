#!/usr/bin/env bash
set -euo pipefail

setup_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$setup_script_dir/common.sh"

macos_profile() {
  printf '%s\n' "${FORD_COLIMA_PROFILE:-emme}"
}

macos_profile_socket() {
  printf '%s/.colima/%s/docker.sock\n' "${HOME:?HOME is required}" "$(macos_profile)"
}

macos_formulae=(mise docker docker-compose colima)

macos_check() {
  setup_require_command brew
  local formula missing=0
  for formula in "${macos_formulae[@]}"; do
    if ! brew list --formula "$formula" >/dev/null 2>&1; then
      printf 'Missing Homebrew formula: %s\n' "$formula"
      missing=1
    fi
  done

  if ! command -v docker >/dev/null 2>&1; then
    printf 'Docker CLI is missing from PATH\n'
    missing=1
  fi
  if ! command -v colima >/dev/null 2>&1; then
    printf 'Colima is missing from PATH\n'
    missing=1
  elif ! colima status --profile "$(macos_profile)" >/dev/null 2>&1; then
    printf 'Colima profile is not running: %s\n' "$(macos_profile)"
    missing=1
  fi

  ((missing == 0))
}

macos_setup_host() {
  if setup_is_apply "$@"; then
    export SETUP_APPLY=1
  fi
  setup_require_command brew

  local formula
  for formula in "${macos_formulae[@]}"; do
    if brew list --formula "$formula" >/dev/null 2>&1; then
      setup_report "Homebrew formula already installed: $formula"
    else
      setup_run brew install "$formula"
    fi
  done
}

macos_wait_for_docker() {
  local attempt
  for attempt in $(seq 1 30); do
    if docker info >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  setup_fail "Docker did not become ready after starting Colima profile $(macos_profile)"
}

macos_setup_docker() {
  if setup_is_apply "$@"; then
    export SETUP_APPLY=1
  fi
  local profile socket
  profile="$(macos_profile)"
  socket="$(macos_profile_socket)"

  if ! setup_is_apply; then
    setup_report "[dry-run] start or verify Colima profile $profile with Docker runtime"
    setup_run colima start --profile "$profile" --runtime docker
    setup_report "[dry-run] docker context use colima"
    setup_report "[dry-run] verify Compose, Buildx, docker info, and socket $socket"
    setup_report "Docker runs as root inside the isolated Colima VM on macOS."
    return
  fi

  setup_require_command colima
  setup_require_command docker
  if colima status --profile "$profile" >/dev/null 2>&1; then
    setup_report "Colima profile is already running: $profile"
  else
    setup_run colima start --profile "$profile" --runtime docker
  fi

  docker context use colima >/dev/null
  macos_wait_for_docker
  command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || setup_fail "Docker Compose is unavailable"
  docker buildx version >/dev/null 2>&1 || setup_fail "Docker Buildx is unavailable"
  [[ -S "$socket" ]] || setup_fail "Colima Docker socket is missing: $socket"
  setup_report "Docker runs as root inside the isolated Colima VM on macOS."
}

case "${1:-}" in
  check)
    shift
    macos_check "$@"
    ;;
  host)
    shift
    macos_setup_host "$@"
    ;;
  docker)
    shift
    macos_setup_docker "$@"
    ;;
  *)
    printf 'Usage: %s {check|host|docker} [--apply]\n' "$0" >&2
    exit 2
    ;;
esac
