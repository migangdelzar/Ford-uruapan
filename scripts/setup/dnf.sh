#!/usr/bin/env bash
set -euo pipefail

setup_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$setup_script_dir/common.sh"

dnf_supported() {
  case "$(setup_os_id)" in
    fedora|ol|ollinux|oracle|oraclelinux)
      return 0
      ;;
    *)
      setup_fail "unsupported dnf distribution: $(setup_os_id)"
      ;;
  esac
}

dnf_repo_url() {
  case "$(setup_os_id)" in
    fedora)
      printf '%s\n' 'https://download.docker.com/linux/fedora/docker-ce.repo'
      ;;
    ol|ollinux|oracle|oraclelinux)
      printf '%s\n' 'https://download.docker.com/linux/rhel/docker-ce.repo'
      ;;
  esac
}

dnf_packages=(
  dnf-plugins-core
  curl
  ca-certificates
  shadow-utils
  fuse-overlayfs
  slirp4netns
  iptables-nft
  docker-ce
  docker-ce-cli
  containerd.io
  docker-buildx-plugin
  docker-compose-plugin
  docker-ce-rootless-extras
)

dnf_user_has_subordinate_range() {
  local database="$1" user_name="$2"
  [[ -r "$database" ]] || return 1
  awk -F: -v user_name="$user_name" '$1 == user_name && $3 >= 65536 {found=1} END {exit !found}' "$database"
}

dnf_check_rootless() {
  local user_name="${USER:-$(id -un)}"
  local runtime_dir="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
  local subuid_file="${SETUP_SUBUID_FILE:-/etc/subuid}"
  local subgid_file="${SETUP_SUBGID_FILE:-/etc/subgid}"

  [[ "$(id -u)" != "0" ]] || setup_fail "rootless Docker setup must run as a non-root user"
  command -v newuidmap >/dev/null 2>&1 || setup_fail "newuidmap is missing; install the shadow-utils package"
  command -v newgidmap >/dev/null 2>&1 || setup_fail "newgidmap is missing; install the shadow-utils package"
  dnf_user_has_subordinate_range "$subuid_file" "$user_name" || setup_fail "${user_name} needs at least 65536 subordinate UIDs in $subuid_file"
  dnf_user_has_subordinate_range "$subgid_file" "$user_name" || setup_fail "${user_name} needs at least 65536 subordinate GIDs in $subgid_file"
  [[ -d "$runtime_dir" ]] || setup_fail "user runtime directory is unavailable: $runtime_dir"
}

dnf_rootful_service_active() {
  command -v systemctl >/dev/null 2>&1 || return 1
  systemctl is-active --quiet docker.service
}

dnf_check() {
  dnf_supported
  setup_require_command dnf
  setup_require_command sudo
  setup_require_command systemctl
  setup_report "Supported dnf distribution: $(setup_os_id)"

  local package
  local missing=0
  if command -v rpm >/dev/null 2>&1; then
    for package in "${dnf_packages[@]}"; do
      if ! rpm -q "$package" >/dev/null 2>&1; then
        printf 'Missing package: %s\n' "$package"
        missing=1
      fi
    done
  fi

  dnf_check_rootless
  if dnf_rootful_service_active; then
    setup_fail "rootful Docker service is active; stop it manually before rootless setup"
  fi

  if ((missing)); then
    return 1
  fi
}

dnf_setup_host() {
  if setup_is_apply "$@"; then
    export SETUP_APPLY=1
  fi
  dnf_supported
  setup_require_command dnf
  setup_require_command sudo

  local repo_url
  repo_url="$(dnf_repo_url)"
  setup_run sudo dnf -y install dnf-plugins-core
  if [[ "$(setup_os_id)" == "fedora" ]]; then
    setup_run sudo dnf config-manager addrepo --from-repofile "$repo_url"
  else
    setup_run sudo dnf config-manager --add-repo "$repo_url"
  fi
  setup_run sudo dnf -y install "${dnf_packages[@]:1}"
  setup_report "Docker packages planned for rootless setup; run setup:docker -- --apply next."
}

dnf_setup_docker() {
  if setup_is_apply "$@"; then
    export SETUP_APPLY=1
  fi
  dnf_supported
  if ! setup_is_apply; then
    setup_report "[dry-run] requires docker-ce-rootless-extras, shadow-utils, subordinate UID/GID ranges, and a user systemd session"
    setup_run dockerd-rootless-setuptool.sh install
    setup_run systemctl --user enable --now docker.service
    setup_run sudo loginctl enable-linger "${USER:-$(id -un)}"
    setup_report "[dry-run] docker context use rootless"
    setup_report "[dry-run] docker info --format rootless security check"
    return
  fi

  setup_require_command docker
  setup_require_command systemctl
  setup_require_command loginctl
  setup_require_command dockerd-rootless-setuptool.sh
  dnf_check_rootless

  if dnf_rootful_service_active; then
    setup_fail "rootful Docker service is active; stop it manually before rootless setup"
  fi

  setup_run dockerd-rootless-setuptool.sh install
  setup_run systemctl --user enable --now docker.service
  setup_run sudo loginctl enable-linger "${USER:-$(id -un)}"

  docker context use rootless >/dev/null
  local security_options
  security_options="$(docker info --format '{{json .SecurityOptions}}')"
  [[ "$security_options" == *rootless* ]] || setup_fail "Docker is reachable but is not reporting rootless security"
  setup_report "rootless Docker is ready"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-}" in
    check)
      shift
      dnf_check "$@"
      ;;
    host)
      shift
      dnf_setup_host "$@"
      ;;
    docker)
      shift
      dnf_setup_docker "$@"
      ;;
    *)
      printf 'Usage: %s {check|host|docker} [--apply]\n' "$0" >&2
      exit 2
      ;;
  esac
fi
