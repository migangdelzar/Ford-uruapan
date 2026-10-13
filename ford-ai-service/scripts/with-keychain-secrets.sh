#!/usr/bin/env bash
set -euo pipefail

if (( $# == 0 )); then
  echo "Usage: with-keychain-secrets.sh COMMAND [ARG ...]" >&2
  exit 2
fi
if ! command -v security >/dev/null 2>&1; then
  echo "macOS Keychain access requires the security command." >&2
  exit 2
fi

service_name="${FORD_KEYCHAIN_SERVICE:-com.ford.uruapan.local}"
secret_names=(
  OPENAI_API_KEY
  OPENROUTER_API_KEY
  GROQ_API_KEY
  GEMINI_API_KEY
  CLOUDFLARE_API_TOKEN
  TYPESAFE_API_KEY
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
  GHCR_TOKEN
)

for name in "${secret_names[@]}"; do
  unset keychain_value
  if keychain_value="$(security find-generic-password -s "$service_name" -a "$name" -w 2>/dev/null)" \
    && [[ -n "$keychain_value" ]]; then
    export "${name}=${keychain_value}"
  fi
  unset keychain_value
done

exec "$@"
