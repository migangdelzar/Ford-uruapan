#!/usr/bin/env bash
set -euo pipefail

setup_is_apply() {
  if [[ "${SETUP_APPLY:-0}" == "1" ]]; then
    return 0
  fi

  local argument
  for argument in "$@"; do
    [[ "$argument" == "--apply" ]] && return 0
  done
  return 1
}

setup_run() {
  if setup_is_apply; then
    "$@"
    return
  fi

  printf '[dry-run]'
  printf ' %q' "$@"
  printf '\n'
}

setup_require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing required command: %s\n' "$1" >&2
    return 1
  }
}

setup_project_root() {
  if [[ -n "${MISE_PROJECT_ROOT:-}" ]]; then
    printf '%s\n' "$MISE_PROJECT_ROOT"
    return
  fi

  cd "$(dirname "${BASH_SOURCE[0]}")/../.."
  pwd
}

setup_os_release_file() {
  printf '%s\n' "${SETUP_OS_RELEASE_FILE:-/etc/os-release}"
}

setup_os_id() {
  local os_release
  os_release="$(setup_os_release_file)"
  [[ -r "$os_release" ]] || return 1
  awk -F= '$1 == "ID" {gsub(/"/, "", $2); print tolower($2)}' "$os_release"
}

setup_os_like() {
  local os_release
  os_release="$(setup_os_release_file)"
  [[ -r "$os_release" ]] || return 1
  awk -F= '$1 == "ID_LIKE" {gsub(/"/, "", $2); print tolower($2)}' "$os_release"
}

setup_report() {
  printf '%s\n' "$*"
}

setup_fail() {
  printf 'Setup error: %s\n' "$*" >&2
  return 1
}
