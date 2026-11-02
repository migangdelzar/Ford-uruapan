#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: restore-rehearsal.sh <archive-dir> <postgres-archive> <container> <rehearsal_database> <source-db>" >&2
  exit 2
}
[[ $# == 5 ]] || usage
archive_dir="$1"
archive_name="$2"
container="$3"
target_db="$4"
source_db="$5"

[[ -n "${DOCKER_HOST:-}" && "$DOCKER_HOST" == unix://* ]] || {
  echo "DOCKER_HOST must explicitly name a local Unix socket." >&2; exit 2;
}
unset DOCKER_CONTEXT
socket_path="${DOCKER_HOST#unix://}"
[[ -S "$socket_path" ]] || { echo "DOCKER_HOST must point to an existing Unix socket." >&2; exit 2; }
local_host="$(hostname)"
[[ "$DOCKER_HOST" != *emme* && "$local_host" != *emme* && -z "${SSH_CONNECTION:-}" && -z "${SSH_CLIENT:-}" ]] || {
  echo "Remote/emme SSH sessions are forbidden for rehearsal." >&2; exit 2;
}
[[ "$container" =~ ^rehearsal-[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || {
  echo "Container name must use the rehearsal- prefix." >&2; exit 2;
}
[[ "$target_db" =~ ^rehearsal_[A-Za-z0-9_]+$ && "$target_db" != ford && "$target_db" != ford_service ]] || {
  echo "Target database must use rehearsal_ prefix; production names are forbidden." >&2; exit 2;
}
[[ "$source_db" == ford || "$source_db" == ford_service ]] || {
  echo "Source database must be ford_service or ford." >&2; exit 2;
}
[[ "$archive_name" =~ ^[A-Za-z0-9._-]+\.dump\.gz$ ]] || usage
archive_path="$archive_dir/$archive_name"
[[ -f "$archive_path" && ! -L "$archive_path" ]] || { echo "Source PostgreSQL archive missing or unsafe." >&2; exit 1; }

root="$(cd "$(dirname "$0")/../.." && pwd)"
day="$(basename "$archive_name" | sed -E 's/.*_([0-9]{8})T[0-9]{6}Z\.dump\.gz/\1/')"
[[ "$day" =~ ^[0-9]{8}$ ]] || { echo "Archive filename must contain a UTC timestamp." >&2; exit 1; }
python3 "$root/deployment/ansible/roles/catalog_sync/files/verify_backup.py" \
  "$archive_dir" "$day" "$source_db" "$archive_name" >/dev/null

actual_name="$(docker inspect --format '{{.Name}}' "$container" 2>/dev/null | sed 's#^/##')"
label="$(docker inspect --format '{{ index .Config.Labels "ford.rehearsal" }}' "$container" 2>/dev/null)"
[[ "$actual_name" == "$container" && "$label" == isolated ]] || {
  echo "Target must be a running container labeled ford.rehearsal=isolated." >&2; exit 2;
}
gzip -dc "$archive_path" | docker exec -i "$container" \
  pg_restore -U ford -d "$target_db" --clean --if-exists --no-owner
echo "isolated_restore=completed source=$source_db target=$target_db archive=$archive_name"
