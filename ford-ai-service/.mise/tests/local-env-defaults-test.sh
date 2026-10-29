#!/usr/bin/env bash
set -euo pipefail

service_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
temp_root="$(mktemp -d)"
trap 'rm -rf "$temp_root"' EXIT

export MISE_PROJECT_ROOT="$temp_root"
source "$service_root/.mise/lib/compose.sh"
ensure_local_env >/dev/null

python3 - "$temp_root/.env" <<'PY'
import re
import sys
from pathlib import Path

values = dict(
    line.split("=", 1)
    for line in Path(sys.argv[1]).read_text().splitlines()
    if "=" in line
)
username = values.get("FORD_OPERATOR_USERNAME", "")
assert re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", username), (
    "generated FORD_OPERATOR_USERNAME must be a valid email"
)
assert values.get("FORD_OPERATOR_PASSWORD"), "generated operator password must exist"
assert values.get("FORD_CATALOG_ADMIN_USERNAME") == "ford_catalog_admin"
assert values.get("FORD_CATALOG_USERNAME") == "ford_catalog_reader"
admin_password = values.get("FORD_CATALOG_ADMIN_PASSWORD", "")
reader_password = values.get("FORD_CATALOG_PASSWORD", "")
assert admin_password and reader_password, "separate catalog credentials must be generated"
assert admin_password != reader_password, "catalog passwords must differ"
print("local operator defaults validated")
PY

cat > "$temp_root/.env" <<'ENV'
FORD_CATALOG_ADMIN_USERNAME=custom_catalog_admin
FORD_CATALOG_ADMIN_PASSWORD=keep-admin-secret
FORD_CATALOG_USERNAME=custom_catalog_reader
FORD_CATALOG_PASSWORD=keep-reader-secret
ENV
ensure_local_env >/dev/null
python3 - "$temp_root/.env" <<'PY'
import sys
from pathlib import Path

values = dict(
    line.split("=", 1)
    for line in Path(sys.argv[1]).read_text().splitlines()
    if "=" in line
)
assert values["FORD_CATALOG_ADMIN_USERNAME"] == "custom_catalog_admin"
assert values["FORD_CATALOG_ADMIN_PASSWORD"] == "keep-admin-secret"
assert values["FORD_CATALOG_USERNAME"] == "custom_catalog_reader"
assert values["FORD_CATALOG_PASSWORD"] == "keep-reader-secret"
print("existing catalog credentials preserved")
PY

cat > "$temp_root/.env" <<'ENV'
FORD_CATALOG_ADMIN_PASSWORD=
FORD_CATALOG_PASSWORD=
ENV
ensure_local_env >/dev/null
python3 - "$temp_root/.env" <<'PY'
import sys
from pathlib import Path

values = dict(
    line.split("=", 1)
    for line in Path(sys.argv[1]).read_text().splitlines()
    if "=" in line
)
assert values.get("FORD_CATALOG_ADMIN_PASSWORD"), "blank admin password must be generated"
assert values.get("FORD_CATALOG_PASSWORD"), "blank reader password must be generated"
assert values["FORD_CATALOG_ADMIN_PASSWORD"] != values["FORD_CATALOG_PASSWORD"]
print("blank catalog credentials generated")
PY

cat > "$temp_root/.env" <<'ENV'
FORD_CATALOG_ADMIN_USERNAME="custom_catalog_admin"
FORD_CATALOG_ADMIN_PASSWORD=""
FORD_CATALOG_USERNAME=ford_catalog_reader
FORD_CATALOG_PASSWORD=''
ENV
ensure_local_env >/dev/null
python3 - "$temp_root/.env" <<'PY'
import sys
from pathlib import Path

values = dict(
    line.split("=", 1)
    for line in Path(sys.argv[1]).read_text().splitlines()
    if "=" in line
)
assert values["FORD_CATALOG_ADMIN_USERNAME"] == '"custom_catalog_admin"'
assert values["FORD_CATALOG_ADMIN_PASSWORD"] not in ('', '""', "''")
assert values["FORD_CATALOG_PASSWORD"] not in ('', '""', "''")
assert sum(line.startswith("FORD_CATALOG_ADMIN_PASSWORD=") for line in Path(sys.argv[1]).read_text().splitlines()) == 1
assert sum(line.startswith("FORD_CATALOG_PASSWORD=") for line in Path(sys.argv[1]).read_text().splitlines()) == 1
assert values["FORD_CATALOG_ADMIN_PASSWORD"] != values["FORD_CATALOG_PASSWORD"]
print("quoted blank catalog credentials replaced; custom values preserved")
PY
