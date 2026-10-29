#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
task_names="$(mise tasks ls --json | python3 -c 'import json,sys; print("\n".join(t["name"] for t in json.load(sys.stdin)))')"

for task in app:run container:up app:deploy container:app:start; do
  grep -Fxq "$task" <<<"$task_names"
done

for old_task in app:start app:stop app:restart app:status dev:start docker:up deploy:app; do
  if grep -Fxq "$old_task" <<<"$task_names"; then
    echo "obsolete task name still registered: $old_task" >&2
    exit 1
  fi
done

test -f "$project_root/mise.dev.toml"
test -f "$project_root/mise.prod.toml"
test ! -e "$project_root/mise.local.toml"
grep -q 'ford_compose up --build -d postgres redis ford-service' "$project_root/.mise/tasks/container/up"
grep -q 'development)' "$project_root/.mise/tasks/app/deploy"
grep -q 'mise -E dev run container:up' "$project_root/.mise/tasks/app/deploy"
grep -q 'production)' "$project_root/.mise/tasks/app/deploy"
grep -q 'mise -E dev run deps:start' "$project_root/.mise/tasks/app/run"
grep -q 'classes --continuous' "$project_root/.mise/tasks/app/run"
grep -q 'bootRun --no-daemon' "$project_root/.mise/tasks/app/run"
grep -q 'SPRING_DEVTOOLS_RESTART_ENABLED' "$project_root/.mise/tasks/app/run"

if output="$(FORD_RUNTIME_ENV=staging "$project_root/.mise/tasks/app/deploy" 2>&1)"; then
  echo "app:deploy unexpectedly accepted an unsupported environment" >&2
  exit 1
fi
grep -q 'Select the dev or prod mise environment' <<<"$output"
