#!/usr/bin/env bash
set -euo pipefail

if (( $# == 0 )); then
  echo "Usage: with-keychain-secrets.sh COMMAND [ARG ...]" >&2
  exit 2
fi
if ! command -v swift >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1; then
  echo "Keychain access requires Swift and Python 3." >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
service_name="${FORD_KEYCHAIN_SERVICE:-com.ford.uruapan.local}"
secret_names=(
  DATABASE_URL
  OPENAI_API_KEY
  OPENROUTER_API_KEY
  GROQ_API_KEY
  GEMINI_API_KEY
  FORD_DEEPSEEK_API_KEY
  FORD_GROQ_API_KEY
  FORD_GEMINI_API_KEY
  FORD_CLOUDFLARE_WORKERS_AI_API_KEY
  CLOUDFLARE_API_TOKEN
  TYPESAFE_API_KEY
  FORD_TYPESAFE_JEV_API_KEY
  FORD_RESEND_API_KEY
  FORD_META_APP_SECRET
  FORD_META_VERIFY_TOKEN
  FORD_META_WHATSAPP_ACCESS_TOKEN
  FORD_META_MESSENGER_ACCESS_TOKEN
  FORD_META_INSTAGRAM_ACCESS_TOKEN
  FORD_MCP_TOKEN
  FORD_MCP_API_TOKEN
  MCP_HTTP_BEARER_TOKEN
  FORD_MCP_ACCESS_CLIENT_SECRET
  FORD_AI_OLLAMA_ACCESS_CLIENT_SECRET
  GRAFANA_CLOUD_API_TOKEN
  LANGFUSE_SECRET_KEY
  POSTGRES_PASSWORD
  REDIS_PASSWORD
  FORD_OPERATOR_PASSWORD
  FORD_SECURITY_JWK_PRIVATE_KEY
  FORD_BFF_OIDC_CLIENT_SECRET
  GRAFANA_CLOUD_SERVICE_KEY
  OTEL_EXPORTER_OTLP_API_TOKEN
  OTEL_EXPORTER_OTLP_HEADERS
  GHCR_TOKEN
  OCI_SSH_PRIVATE_KEY
  EMME_1_SSH_KEY
  EMME_2_SSH_KEY
)

set +e
swift "$script_dir/keychain-secrets.swift" get "$service_name" "${secret_names[@]}" \
  | python3 "$script_dir/exec-with-keychain-env.py" "$@"
exit $?
