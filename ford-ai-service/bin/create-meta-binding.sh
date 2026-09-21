#!/usr/bin/env bash

set -euo pipefail
shopt -s extglob

service_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$service_dir"

if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
elif [[ -x /Users/miguelangeldelgadillozarate/homebrew/bin/docker-compose ]]; then
  compose=(/Users/miguelangeldelgadillozarate/homebrew/bin/docker-compose)
else
  echo "Docker Compose is required" >&2
  exit 1
fi

while IFS='=' read -r key value || [[ -n "${key:-}${value:-}" ]]; do
  [[ "$key" =~ ^[[:space:]]*(export[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*[[:space:]]*$ ]] || continue
  key="${key#export }"
  key="${key##+([[:space:]])}"
  key="${key%%+([[:space:]])}"
  value="${value%$'\r'}"
  if [[ "$value" != \"*\" && "$value" != \'*\' ]]; then
    value="${value%%[[:space:]]#*}"
  fi
  export "$key=$value"
done < .env

provider_account_id="${1:-${FORD_META_PROVIDER_ACCOUNT_ID:-${FORD_META_PHONE_ID:-}}}"
: "${provider_account_id:?Usage: $0 <meta-provider-account-id>}"
: "${FORD_TENANT_ID:?FORD_TENANT_ID is required in .env}"
: "${FORD_META_APP_SECRET:?FORD_META_APP_SECRET is required in .env}"

binding_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"

"${compose[@]}" --env-file .env -f docker-compose.yml up -d postgres >/dev/null
"${compose[@]}" --env-file .env -f docker-compose.yml exec -T postgres \
  psql -U ford -d ford_service -v ON_ERROR_STOP=1 \
  --set=binding_id="$binding_id" \
  --set=tenant_id="$FORD_TENANT_ID" \
  --set=provider_account_id="$provider_account_id" <<'SQL'
insert into channel_binding (
    id, webhook_key_hash, tenant_id, channel, provider_account_id,
    secret_reference, enabled, created_at, updated_at
)
values (
    :'binding_id'::uuid, null, :'tenant_id'::uuid, 'WHATSAPP',
    :'provider_account_id', null, true, now(), now()
)
on conflict (channel, provider_account_id) do update
set tenant_id = excluded.tenant_id,
    channel = excluded.channel,
    provider_account_id = excluded.provider_account_id,
    enabled = true,
    updated_at = now();
SQL

echo "Meta WhatsApp phone-number binding is ready for provider account: $provider_account_id"
