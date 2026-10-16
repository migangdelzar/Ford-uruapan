#!/usr/bin/env bash

set -euo pipefail

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required command not found: $command_name" >&2
    return 1
  fi
}

deploy_repo_root() { printf '%s\n' "$(cd "$MISE_PROJECT_ROOT/.." && pwd -P)"; }
deploy_config_file() { printf '%s\n' "${FORD_DEPLOY_CONFIG_FILE:-$(deploy_repo_root)/deployment/config/oci.env}"; }
deploy_state_dir() { printf '%s\n' "$MISE_PROJECT_ROOT/.mise/state/deploy"; }
deploy_inventory_file() { printf '%s\n' "${FORD_DEPLOY_INVENTORY_FILE:-$(deploy_state_dir)/inventory.yml}"; }
backup_inventory_file() { printf '%s\n' "$(deploy_state_dir)/backup-inventory.yml"; }
infra_state_dir() { printf '%s\n' "$MISE_PROJECT_ROOT/.mise/state/infra"; }
infra_vars_file() { printf '%s\n' "$(infra_state_dir)/terraform.tfvars"; }
vault_secret_mapping_file() { printf '%s\n' "$(deploy_repo_root)/deployment/config/vault-secret-ocids.env"; }

load_vault_secret_mappings() {
  local mapping_file="$(vault_secret_mapping_file)"
  local line_number=0 name value
  [[ -e "$mapping_file" ]] || return 0
  if [[ -L "$mapping_file" ]]; then
    echo "Vault OCID mapping file must not be a symlink." >&2
    return 1
  fi

  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    [[ -z "$line" || "$line" == \#* ]] && continue
    if [[ ! "$line" =~ ^(FORD_VAULT_SECRET_OCID_[A-Z][A-Z0-9_]*)=(ocid1\.vaultsecret\.oc1\.[a-z0-9-]*\.[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*)$ ]]; then
      echo "Invalid Vault OCID mapping at line ${line_number}; no value was loaded." >&2
      return 1
    fi
    name="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    export "${name}=${value}"
  done < "$mapping_file"
}

load_deploy_config() {
  local config_file="$(deploy_config_file)"
  local preserved_postgres_password="${POSTGRES_PASSWORD:-}"
  local preserved_redis_password="${REDIS_PASSWORD:-}"
  local preserved_operator_password="${FORD_OPERATOR_PASSWORD:-}"
  local preserved_meta_app_secret="${FORD_META_APP_SECRET:-}"
  local preserved_meta_verify_token="${FORD_META_VERIFY_TOKEN:-}"
  local preserved_meta_whatsapp_access_token="${FORD_META_WHATSAPP_ACCESS_TOKEN:-}"
  local preserved_security_jwk_private_key="${FORD_SECURITY_JWK_PRIVATE_KEY:-}"
  local preserved_bff_oidc_client_secret="${FORD_BFF_OIDC_CLIENT_SECRET:-}"
  local preserved_ghcr_username="${GHCR_USERNAME:-}"
  local preserved_ghcr_token="${GHCR_TOKEN:-}"
  local preserved_openai_api_key="${OPENAI_API_KEY:-}"
  local preserved_groq_api_key="${GROQ_API_KEY:-}"
  local preserved_gemini_api_key="${GEMINI_API_KEY:-}"
  local preserved_cloudflare_api_token="${CLOUDFLARE_API_TOKEN:-}"
  local preserved_cloudflare_account_id="${CLOUDFLARE_ACCOUNT_ID:-}"
  local preserved_openrouter_api_key="${OPENROUTER_API_KEY:-}"
  local preserved_typesafe_api_key="${TYPESAFE_API_KEY:-}"
  local preserved_resend_api_key="${FORD_RESEND_API_KEY:-}"
  local preserved_ford_mcp_token="${FORD_MCP_TOKEN:-}"
  local preserved_ford_mcp_api_token="${FORD_MCP_API_TOKEN:-}"
  local preserved_mcp_token="${MCP_HTTP_BEARER_TOKEN:-}"
  local preserved_ollama_access_secret="${FORD_AI_OLLAMA_ACCESS_CLIENT_SECRET:-}"
  local preserved_mcp_access_secret="${FORD_MCP_ACCESS_CLIENT_SECRET:-}"
  local preserved_grafana_token="${GRAFANA_CLOUD_API_TOKEN:-}"
  local preserved_langfuse_secret="${LANGFUSE_SECRET_KEY:-}"
  local preserved_oci_namespace="${OCI_OBJECT_STORAGE_NAMESPACE:-}"
  if [[ ! -f "$config_file" ]]; then
    echo "Missing $config_file; copy deployment/config/oci.env.example first." >&2
    return 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$config_file"
  set +a
  if [[ -n "$preserved_postgres_password" ]]; then export POSTGRES_PASSWORD="$preserved_postgres_password"; fi
  if [[ -n "$preserved_redis_password" ]]; then export REDIS_PASSWORD="$preserved_redis_password"; fi
  if [[ -n "$preserved_operator_password" ]]; then export FORD_OPERATOR_PASSWORD="$preserved_operator_password"; fi
  if [[ -n "$preserved_meta_app_secret" ]]; then export FORD_META_APP_SECRET="$preserved_meta_app_secret"; fi
  if [[ -n "$preserved_meta_verify_token" ]]; then export FORD_META_VERIFY_TOKEN="$preserved_meta_verify_token"; fi
  if [[ -n "$preserved_meta_whatsapp_access_token" ]]; then export FORD_META_WHATSAPP_ACCESS_TOKEN="$preserved_meta_whatsapp_access_token"; fi
  if [[ -n "$preserved_security_jwk_private_key" ]]; then export FORD_SECURITY_JWK_PRIVATE_KEY="$preserved_security_jwk_private_key"; fi
  if [[ -n "$preserved_bff_oidc_client_secret" ]]; then export FORD_BFF_OIDC_CLIENT_SECRET="$preserved_bff_oidc_client_secret"; fi
  if [[ -n "$preserved_ghcr_username" ]]; then export GHCR_USERNAME="$preserved_ghcr_username"; fi
  if [[ -n "$preserved_ghcr_token" ]]; then export GHCR_TOKEN="$preserved_ghcr_token"; fi
  if [[ -n "$preserved_openai_api_key" ]]; then export OPENAI_API_KEY="$preserved_openai_api_key"; fi
  if [[ -n "$preserved_groq_api_key" ]]; then export GROQ_API_KEY="$preserved_groq_api_key"; fi
  if [[ -n "$preserved_gemini_api_key" ]]; then export GEMINI_API_KEY="$preserved_gemini_api_key"; fi
  if [[ -n "$preserved_cloudflare_api_token" ]]; then export CLOUDFLARE_API_TOKEN="$preserved_cloudflare_api_token"; fi
  if [[ -n "$preserved_cloudflare_account_id" ]]; then export CLOUDFLARE_ACCOUNT_ID="$preserved_cloudflare_account_id"; fi
  if [[ -n "$preserved_openrouter_api_key" ]]; then export OPENROUTER_API_KEY="$preserved_openrouter_api_key"; fi
  if [[ -n "$preserved_typesafe_api_key" ]]; then export TYPESAFE_API_KEY="$preserved_typesafe_api_key"; fi
  if [[ -n "$preserved_resend_api_key" ]]; then export FORD_RESEND_API_KEY="$preserved_resend_api_key"; fi
  if [[ -n "$preserved_ford_mcp_token" ]]; then export FORD_MCP_TOKEN="$preserved_ford_mcp_token"; fi
  if [[ -n "$preserved_ford_mcp_api_token" ]]; then export FORD_MCP_API_TOKEN="$preserved_ford_mcp_api_token"; fi
  if [[ -n "$preserved_mcp_token" ]]; then export MCP_HTTP_BEARER_TOKEN="$preserved_mcp_token"; fi
  if [[ -n "$preserved_ollama_access_secret" ]]; then export FORD_AI_OLLAMA_ACCESS_CLIENT_SECRET="$preserved_ollama_access_secret"; fi
  if [[ -n "$preserved_mcp_access_secret" ]]; then export FORD_MCP_ACCESS_CLIENT_SECRET="$preserved_mcp_access_secret"; fi
  if [[ -n "$preserved_grafana_token" ]]; then export GRAFANA_CLOUD_API_TOKEN="$preserved_grafana_token"; fi
  if [[ -n "$preserved_langfuse_secret" ]]; then export LANGFUSE_SECRET_KEY="$preserved_langfuse_secret"; fi
  if [[ -n "$preserved_oci_namespace" ]]; then export OCI_OBJECT_STORAGE_NAMESPACE="$preserved_oci_namespace"; fi
  load_vault_secret_mappings
}

is_valid_tenant_id() {
  local tenant_id="$1"
  [[ "$tenant_id" == "00000000-0000-0000-0000-000000000001" \
    || "$tenant_id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]]
}

require_deploy_config() {
  local missing=() name
  for name in OCI_REGION OCI_SSH_USER OCI_SSH_PRIVATE_KEY EMME_1_SSH_HOST \
    EMME_2_SSH_HOST EMME_1_PRIVATE_IP EMME_2_PRIVATE_IP EMME_REMOTE_ROOT \
    EMME_DATA_VOLUME_MOUNT POSTGRES_PASSWORD REDIS_PASSWORD \
    FORD_OPERATOR_USERNAME FORD_OPERATOR_PASSWORD \
    FORD_META_APP_SECRET FORD_META_VERIFY_TOKEN \
    GRAFANA_CLOUD_OTLP_ENDPOINT GRAFANA_CLOUD_INSTANCE_ID GRAFANA_CLOUD_API_TOKEN \
    LANGFUSE_PUBLIC_KEY LANGFUSE_SECRET_KEY; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  [[ -r "${OCI_SSH_PRIVATE_KEY:-}" ]] || missing+=("readable:OCI_SSH_PRIVATE_KEY")
  [[ -r "${EMME_1_SSH_KEY:-${OCI_SSH_PRIVATE_KEY:-}}" ]] || missing+=("readable:EMME_1_SSH_KEY")
  [[ -r "${EMME_2_SSH_KEY:-${OCI_SSH_PRIVATE_KEY:-}}" ]] || missing+=("readable:EMME_2_SSH_KEY")
  if [[ "${EMME_APP_IMAGE:-}" == ghcr.io/* && -n "${GHCR_TOKEN:-}" && -z "${GHCR_USERNAME:-}" ]]; then
    missing+=("GHCR_USERNAME")
  fi
  if [[ "${FORD_META_AUTO_BINDING_ENABLED:-false}" == "true" ]]; then
    [[ -n "${FORD_META_PHONE_NUMBER_ID:-${FORD_META_PROVIDER_ACCOUNT_ID:-${FORD_META_PHONE_ID:-}}}" ]] || missing+=("FORD_META_PHONE_NUMBER_ID")
    [[ -n "${FORD_TENANT_ID:-00000000-0000-0000-0000-000000000001}" ]] || missing+=("FORD_TENANT_ID")
    [[ "${FORD_META_PHONE_NUMBER_ID:-${FORD_META_PROVIDER_ACCOUNT_ID:-${FORD_META_PHONE_ID:-}}}" =~ ^[0-9]+$ ]] || missing+=("numeric:FORD_META_PHONE_NUMBER_ID")
    is_valid_tenant_id "${FORD_TENANT_ID:-00000000-0000-0000-0000-000000000001}" || missing+=("uuid:FORD_TENANT_ID")
  fi
  if [[ "${FORD_META_DELIVERY_ENABLED:-false}" == "true" ]]; then
    [[ -n "${FORD_META_WHATSAPP_DELIVERY_ENDPOINT:-}" ]] || missing+=("FORD_META_WHATSAPP_DELIVERY_ENDPOINT")
    [[ -n "${FORD_META_WHATSAPP_ACCESS_TOKEN:-}" ]] || missing+=("FORD_META_WHATSAPP_ACCESS_TOKEN")
  fi
  if [[ "${FORD_MCP_ENABLED:-false}" == "true" ]]; then
    [[ -n "${FORD_MCP_URL:-}" ]] || missing+=("FORD_MCP_URL")
    [[ "${FORD_MCP_URL:-}" != "https://your-stable-mcp-domain.example.com" ]] || missing+=("usable:FORD_MCP_URL")
    [[ "${FORD_MCP_URL:-}" == https://* ]] || missing+=("https:FORD_MCP_URL")
    [[ -n "${FORD_MCP_ENDPOINT:-}" ]] || missing+=("FORD_MCP_ENDPOINT")
    [[ -n "${FORD_MCP_TOKEN:-${FORD_MCP_API_TOKEN:-${MCP_HTTP_BEARER_TOKEN:-}}}" ]] || missing+=("FORD_MCP_TOKEN")
  fi
  if [[ "${FORD_SECURITY_AUTH_ENABLED:-true}" == "true" ]]; then
    for name in FORD_SECURITY_JWK_PRIVATE_KEY FORD_RESEND_API_KEY RESEND_FROM; do
      [[ -n "${!name:-}" ]] || missing+=("$name")
    done
  fi
  if (( ${#missing[@]} > 0 )); then
    printf 'Missing deployment configuration: %s\n' "${missing[*]}" >&2
    return 1
  fi
}

set_deploy_secret() {
  local config_file="$(deploy_config_file)" name="$1" value="$2" temp value_file
  [[ "$name" =~ ^[A-Z0-9_]+$ ]] || {
    printf 'Invalid deployment secret name: %s\n' "$name" >&2
    return 1
  }
  [[ -n "$value" ]] || {
    printf 'Refusing to store an empty deployment secret: %s\n' "$name" >&2
    return 1
  }

  temp="$(mktemp "${config_file}.XXXXXX")"
  value_file="$(mktemp "${config_file}.value.XXXXXX")"
  chmod 600 "$temp" "$value_file"
  cleanup_secret_files() {
    rm -f "$temp" "$value_file"
  }
  trap cleanup_secret_files RETURN
  printf '%s' "$value" > "$value_file"
  awk -v name="$name" -v value_file="$value_file" '
    BEGIN {
      if ((getline value < value_file) < 0) exit 1
      close(value_file)
    }
    index($0, name "=") == 1 { print name "=" value; found = 1; next }
    { print }
    END { if (!found) print name "=" value }
  ' "$config_file" > "$temp"
  chmod 600 "$temp"
  mv "$temp" "$config_file"
  rm -f "$value_file"
  trap - RETURN
}

adopt_environment_secret() {
  local config_file="$(deploy_config_file)" name="$1" source_name="${2:-$1}" current value
  current="$(sed -n "s/^${name}=//p" "$config_file" | head -n 1)"
  [[ -n "$current" ]] && return
  value="${!source_name:-}"
  [[ -n "$value" ]] || return 0
  set_deploy_secret "$name" "$value"
  unset value
}

ensure_deploy_secret() {
  local config_file="$(deploy_config_file)" name="$1" current value
  current="$(sed -n "s/^${name}=//p" "$config_file" | head -n 1)"
  [[ -n "$current" ]] && return
  require_command openssl
  value="$(openssl rand -hex 32)"
  set_deploy_secret "$name" "$value"
  unset value
}

prompt_deploy_secret() {
  local config_file="$(deploy_config_file)" name="$1" label="$2" current value
  current="$(sed -n "s/^${name}=//p" "$config_file" | head -n 1)"
  [[ -n "$current" ]] && return
  if [[ ! -t 0 ]]; then
    printf 'Missing %s; run mise run deploy:secrets interactively to provide it.\n' "$name" >&2
    return 1
  fi
  printf '%s: ' "$label" >&2
  IFS= read -r -s value || {
    printf '\n' >&2
    return 1
  }
  printf '\n' >&2
  if [[ -z "$value" ]]; then
    printf '%s cannot be empty.\n' "$name" >&2
    unset value
    return 1
  fi
  set_deploy_secret "$name" "$value"
  unset value
}

report_ai_fallback_status() {
  local provider enabled key_name capability_status key_status evaluation_status
  for provider in groq gemini cloudflare openrouter; do
    case "$provider" in
      groq) enabled="${FORD_AI_GROQ_ENABLED:-false}"; key_name=GROQ_API_KEY ;;
      gemini) enabled="${FORD_AI_GEMINI_ENABLED:-false}"; key_name=GEMINI_API_KEY ;;
      cloudflare) enabled="${FORD_AI_CLOUDFLARE_ENABLED:-false}"; key_name=CLOUDFLARE_API_TOKEN ;;
      openrouter) enabled="${FORD_AI_OPENROUTER_ENABLED:-true}"; key_name=OPENROUTER_API_KEY ;;
    esac
    if [[ "$enabled" != "true" ]]; then
      printf 'AI fallback %-10s disabled\n' "$provider"
      continue
    fi
    key_status=present
    [[ -n "${!key_name:-}" ]] || key_status=missing-key
    if [[ "$provider" == cloudflare && -z "${CLOUDFLARE_ACCOUNT_ID:-}" ]]; then
      key_status=missing-key-or-account-id
    fi
    capability_status=verified
    case "$provider" in
      groq) [[ "${FORD_AI_GROQ_TOOL_CALLING_VERIFIED:-false}" == true && "${FORD_AI_GROQ_STRUCTURED_OUTPUT_VERIFIED:-false}" == true ]] || capability_status=unverified ;;
      gemini) [[ "${FORD_AI_GEMINI_TOOL_CALLING_VERIFIED:-false}" == true && "${FORD_AI_GEMINI_STRUCTURED_OUTPUT_VERIFIED:-false}" == true ]] || capability_status=unverified ;;
      cloudflare) [[ "${FORD_AI_CLOUDFLARE_TOOL_CALLING_VERIFIED:-false}" == true && "${FORD_AI_CLOUDFLARE_STRUCTURED_OUTPUT_VERIFIED:-false}" == true ]] || capability_status=unverified ;;
      openrouter) [[ "${FORD_AI_OPENROUTER_TOOL_CALLING_VERIFIED:-false}" == true && "${FORD_AI_OPENROUTER_STRUCTURED_OUTPUT_VERIFIED:-false}" == true ]] || capability_status=unverified ;;
    esac
    evaluation_status=not-required
    if [[ "$provider" == openrouter && "${FORD_AI_OPENROUTER_MODEL:-xiaomi/mimo-v2.6-flash}" == "openrouter/free" ]]; then
      evaluation_status=evaluation-only
      [[ "${FORD_AI_EVALUATION_MODE:-false}" == true ]] && evaluation_status=enabled
    fi
    printf 'AI fallback %-10s enabled credential=%s capabilities=%s evaluation=%s\n' \
      "$provider" "$key_status" "$capability_status" "$evaluation_status"
  done
}

report_classification_status() {
  local enabled="${FORD_AI_CLASSIFICATION_ENABLED:-true}"
  local provider="${FORD_AI_CLASSIFICATION_PROVIDER:-typesafe}"
  local mode="${FORD_AI_CLASSIFICATION_MODE:-active}"
  local key_status=not-required sensitive="${FORD_AI_CLASSIFIER_SENSITIVE_DATA_ENABLED:-false}"
  if [[ "$provider" == "typesafe" ]]; then
    key_status=missing
    [[ -n "${TYPESAFE_API_KEY:-}" ]] && key_status=present
  fi
  printf 'classification %s enabled=%s mode=%s credential=%s sensitive_data=%s\n' \
    "$provider" "$enabled" "$mode" "$key_status" "$sensitive"
}

ensure_deploy_state_dir() { mkdir -p "$(deploy_state_dir)"; chmod 700 "$(deploy_state_dir)"; }

ensure_infra_state_dir() { mkdir -p "$(infra_state_dir)"; chmod 700 "$(infra_state_dir)"; }

oci_profile_value() {
  local key="$1" profile="${OCI_CONFIG_PROFILE:-DEFAULT}" config_file="${OCI_CLI_CONFIG_FILE:-$HOME/.oci/config}"
  [[ -r "$config_file" ]] || return 0
  awk -v section="[$profile]" -v key="$key" '
    $0 == section { in_section = 1; next }
    /^\[/ { in_section = 0 }
    in_section && $0 ~ "^" key "[[:space:]]*=" {
      sub("^[^=]+=[[:space:]]*", "")
      print
      exit
    }
  ' "$config_file"
}

render_terraform_vars() {
  ensure_infra_state_dir
  local tenancy_ocid="${OCI_TENANCY_OCID:-}"
  local compartment_ocid="${OCI_COMPARTMENT_OCID:-}"
  tenancy_ocid="${tenancy_ocid:-$(oci_profile_value tenancy)}"
  compartment_ocid="${compartment_ocid:-$tenancy_ocid}"
  umask 077
  cat > "$(infra_vars_file)" <<EOF
tenancy_ocid = "${tenancy_ocid}"
user_ocid = "${OCI_USER_OCID:-}"
fingerprint = "${OCI_FINGERPRINT:-}"
private_key_path = "${OCI_API_PRIVATE_KEY_PATH:-}"
region = "${OCI_REGION:-}"
oci_config_profile = "${OCI_CONFIG_PROFILE:-DEFAULT}"
compartment_ocid = "${compartment_ocid}"
availability_domain = "${OCI_AVAILABILITY_DOMAIN:-}"
existing_subnet_id = "${OCI_EXISTING_SUBNET_OCID:-}"
ssh_public_key = "${OCI_SSH_PUBLIC_KEY:-}"
object_storage_namespace = "${OCI_OBJECT_STORAGE_NAMESPACE:-}"
backup_bucket_name = "${OCI_BACKUP_BUCKET:-emme-business}"
backup_object_prefix = "${OCI_BACKUP_PREFIX:-ford/backups}"
backup_compartment_ocid = "${OCI_BACKUP_COMPARTMENT_OCID:-${OCI_COMPARTMENT_OCID:-}}"
emme_2_instance_ocid = "${EMME_2_INSTANCE_OCID:-}"
backup_dynamic_group_name = "${OCI_BACKUP_DYNAMIC_GROUP_NAME:-ford-backup-emme2}"
backup_policy_name = "${OCI_BACKUP_POLICY_NAME:-ford-backup-emme2-policy}"
create_instances = false
manage_data_volume = false
manage_backup_bucket = ${OCI_MANAGE_BACKUP_BUCKET:-false}
EOF
  chmod 600 "$(infra_vars_file)"
}

render_deploy_inventory() {
  ensure_deploy_state_dir
  local inventory_file="$(deploy_inventory_file)"
  local ansible_user_yaml ansible_host_1_yaml ansible_host_2_yaml key_1_yaml key_2_yaml
  local private_1_yaml private_2_yaml root_yaml mount_yaml data_bind_yaml
  local operator_user_yaml app_image_yaml ghcr_username_yaml
  local backup_bucket_yaml backup_namespace_yaml backup_prefix_yaml backup_region_yaml
  local grafana_endpoint_yaml grafana_instance_yaml langfuse_endpoint_yaml langfuse_public_yaml
  local ford_meta_auto_binding_enabled_yaml ford_meta_provider_account_id_yaml ford_tenant_id_yaml
  local ford_meta_delivery_enabled_yaml ford_meta_whatsapp_endpoint_yaml
  local ford_human_handoff_enabled_yaml
  local ford_conversation_graph_enabled_yaml
  local ford_outbox_enabled_yaml
  local ford_ai_chat_provider_yaml ford_ai_cloud_provider_yaml ford_ai_cloud_enabled_yaml ford_ai_log_provider_errors_yaml ford_ai_local_enabled_yaml
  local ford_ai_local_base_url_yaml ford_ai_local_model_version_yaml ford_ai_local_temperature_yaml ford_ai_cloud_temperature_yaml
  local ford_ai_cloud_base_url_yaml ford_ai_cloud_model_version_yaml ford_ai_provider_max_attempts_yaml ford_ai_provider_max_retry_after_yaml
  local ford_ai_fallback_order_yaml ford_ai_evaluation_mode_yaml
  local ford_ai_groq_enabled_yaml ford_ai_groq_model_yaml ford_ai_groq_tool_calling_verified_yaml ford_ai_groq_structured_output_verified_yaml
  local ford_ai_groq_base_url_yaml
  local ford_ai_gemini_enabled_yaml ford_ai_gemini_model_yaml ford_ai_gemini_tool_calling_verified_yaml ford_ai_gemini_structured_output_verified_yaml
  local ford_ai_gemini_base_url_yaml
  local ford_ai_cloudflare_enabled_yaml ford_ai_cloudflare_model_yaml ford_ai_cloudflare_tool_calling_verified_yaml ford_ai_cloudflare_structured_output_verified_yaml
  local ford_ai_cloudflare_base_url_yaml
  local ford_ai_openrouter_enabled_yaml ford_ai_openrouter_model_yaml ford_ai_openrouter_tool_calling_verified_yaml ford_ai_openrouter_structured_output_verified_yaml
  local ford_ai_openrouter_base_url_yaml
  local cloudflare_account_id_yaml
  local ford_ai_classifier_handoff_enabled_yaml
  local ford_mcp_enabled_yaml ford_mcp_url_yaml ford_mcp_endpoint_yaml
  local ford_ai_ollama_access_id_yaml ford_mcp_access_id_yaml
  ansible_user_yaml="$(yaml_quote "$OCI_SSH_USER")"
  ansible_host_1_yaml="$(yaml_quote "$EMME_1_SSH_HOST")"
  ansible_host_2_yaml="$(yaml_quote "$EMME_2_SSH_HOST")"
  key_1_yaml="$(yaml_quote "${EMME_1_SSH_KEY:-${OCI_SSH_PRIVATE_KEY}}")"
  key_2_yaml="$(yaml_quote "${EMME_2_SSH_KEY:-${OCI_SSH_PRIVATE_KEY}}")"
  private_1_yaml="$(yaml_quote "$EMME_1_PRIVATE_IP")"
  private_2_yaml="$(yaml_quote "$EMME_2_PRIVATE_IP")"
  root_yaml="$(yaml_quote "$EMME_REMOTE_ROOT")"
  mount_yaml="$(yaml_quote "$EMME_DATA_VOLUME_MOUNT")"
  data_bind_yaml="$(yaml_quote "${EMME_2_PRIVATE_IP}")"
  operator_user_yaml="$(yaml_quote "$FORD_OPERATOR_USERNAME")"
  ford_meta_auto_binding_enabled_yaml="$(yaml_quote "${FORD_META_AUTO_BINDING_ENABLED:-false}")"
  ford_meta_provider_account_id_yaml="$(yaml_quote "${FORD_META_PHONE_NUMBER_ID:-${FORD_META_PROVIDER_ACCOUNT_ID:-${FORD_META_PHONE_ID:-}}}")"
  ford_meta_waba_id_yaml="$(yaml_quote "${FORD_META_WHATSAPP_BUSINESS_ACCOUNT_ID:-${FORD_META_WABA_ID:-}}")"
  ford_meta_phone_number_yaml="$(yaml_quote "${FORD_META_PHONE_NUMBER:-}")"
  ford_meta_diagnostics_yaml="$(yaml_quote "${FORD_META_DIAGNOSTIC_STACKTRACES_ENABLED:-false}")"
  ford_tenant_id_yaml="$(yaml_quote "${FORD_TENANT_ID:-00000000-0000-0000-0000-000000000001}")"
  ford_meta_delivery_enabled_yaml="$(yaml_quote "${FORD_META_DELIVERY_ENABLED:-false}")"
  ford_meta_whatsapp_endpoint_yaml="$(yaml_quote "${FORD_META_WHATSAPP_DELIVERY_ENDPOINT:-}")"
  ford_human_handoff_enabled_yaml="$(yaml_quote "${FORD_HUMAN_HANDOFF_ENABLED:-true}")"
  ford_conversation_graph_enabled_yaml="$(yaml_quote "${FORD_CONVERSATION_GRAPH_ENABLED:-true}")"
  ford_outbox_enabled_yaml="$(yaml_quote "${FORD_OUTBOX_ENABLED:-false}")"
  ford_ai_chat_provider_yaml="$(yaml_quote "${FORD_AI_CHAT_PROVIDER:-openai}")"
  ford_ai_cloud_provider_yaml="$(yaml_quote "${FORD_AI_CLOUD_PROVIDER:-openai}")"
  ford_ai_cloud_enabled_yaml="$(yaml_quote "${FORD_AI_CLOUD_ENABLED:-true}")"
  ford_ai_log_provider_errors_yaml="$(yaml_quote "${FORD_AI_LOG_PROVIDER_ERRORS:-false}")"
  ford_ai_local_enabled_yaml="$(yaml_quote "${FORD_AI_LOCAL_ENABLED:-false}")"
  ford_ai_local_base_url_yaml="$(yaml_quote "${FORD_AI_LOCAL_BASE_URL:-https://ollama.example.invalid}")"
  ford_ai_local_model_version_yaml="$(yaml_quote "${FORD_AI_LOCAL_MODEL_VERSION:-qwen3.5:4b}")"
  ford_ai_local_temperature_yaml="$(yaml_quote "${FORD_AI_LOCAL_TEMPERATURE:-0.1}")"
  ford_ai_cloud_temperature_yaml="$(yaml_quote "${FORD_AI_CLOUD_TEMPERATURE:-0.1}")"
  ford_ai_cloud_base_url_yaml="$(yaml_quote "${FORD_AI_CLOUD_BASE_URL:-https://api.deepseek.com}")"
  ford_ai_cloud_model_version_yaml="$(yaml_quote "${FORD_AI_CLOUD_MODEL_VERSION:-deepseek-flash}")"
  ford_ai_provider_max_attempts_yaml="$(yaml_quote "${FORD_AI_PROVIDER_MAX_ATTEMPTS:-2}")"
  ford_ai_provider_max_retry_after_yaml="$(yaml_quote "${FORD_AI_PROVIDER_MAX_RETRY_AFTER:-PT3S}")"
  ford_ai_fallback_order_yaml="$(yaml_quote "${FORD_AI_FALLBACK_ORDER:-openrouter}")"
  ford_ai_evaluation_mode_yaml="$(yaml_quote "${FORD_AI_EVALUATION_MODE:-false}")"
  ford_ai_groq_enabled_yaml="$(yaml_quote "${FORD_AI_GROQ_ENABLED:-false}")"
  ford_ai_groq_model_yaml="$(yaml_quote "${FORD_AI_GROQ_MODEL:-openai/gpt-oss-20b}")"
  ford_ai_groq_base_url_yaml="$(yaml_quote "${FORD_AI_GROQ_BASE_URL:-https://api.groq.com/openai/v1}")"
  ford_ai_groq_tool_calling_verified_yaml="$(yaml_quote "${FORD_AI_GROQ_TOOL_CALLING_VERIFIED:-false}")"
  ford_ai_groq_structured_output_verified_yaml="$(yaml_quote "${FORD_AI_GROQ_STRUCTURED_OUTPUT_VERIFIED:-false}")"
  ford_ai_gemini_enabled_yaml="$(yaml_quote "${FORD_AI_GEMINI_ENABLED:-false}")"
  ford_ai_gemini_model_yaml="$(yaml_quote "${FORD_AI_GEMINI_MODEL:-gemini-3.8-flash}")"
  ford_ai_gemini_base_url_yaml="$(yaml_quote "${FORD_AI_GEMINI_BASE_URL:-https://generativelanguage.googleapis.com/v1beta/openai/}")"
  ford_ai_gemini_tool_calling_verified_yaml="$(yaml_quote "${FORD_AI_GEMINI_TOOL_CALLING_VERIFIED:-false}")"
  ford_ai_gemini_structured_output_verified_yaml="$(yaml_quote "${FORD_AI_GEMINI_STRUCTURED_OUTPUT_VERIFIED:-false}")"
  ford_ai_cloudflare_enabled_yaml="$(yaml_quote "${FORD_AI_CLOUDFLARE_ENABLED:-false}")"
  ford_ai_cloudflare_model_yaml="$(yaml_quote "${FORD_AI_CLOUDFLARE_MODEL:-@cf/openai/gpt-oss-120b}")"
  ford_ai_cloudflare_base_url_yaml="$(yaml_quote "${FORD_AI_CLOUDFLARE_BASE_URL:-https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID:-unconfigured}/ai/v1}")"
  ford_ai_cloudflare_tool_calling_verified_yaml="$(yaml_quote "${FORD_AI_CLOUDFLARE_TOOL_CALLING_VERIFIED:-false}")"
  ford_ai_cloudflare_structured_output_verified_yaml="$(yaml_quote "${FORD_AI_CLOUDFLARE_STRUCTURED_OUTPUT_VERIFIED:-false}")"
  ford_ai_openrouter_enabled_yaml="$(yaml_quote "${FORD_AI_OPENROUTER_ENABLED:-true}")"
  ford_ai_openrouter_model_yaml="$(yaml_quote "${FORD_AI_OPENROUTER_MODEL:-xiaomi/mimo-v2.6-flash}")"
  ford_ai_openrouter_base_url_yaml="$(yaml_quote "${FORD_AI_OPENROUTER_BASE_URL:-https://openrouter.ai/api/v1}")"
  ford_ai_openrouter_tool_calling_verified_yaml="$(yaml_quote "${FORD_AI_OPENROUTER_TOOL_CALLING_VERIFIED:-false}")"
  ford_ai_openrouter_structured_output_verified_yaml="$(yaml_quote "${FORD_AI_OPENROUTER_STRUCTURED_OUTPUT_VERIFIED:-false}")"
  ford_ai_classifier_handoff_enabled_yaml="$(yaml_quote "${FORD_AI_CLASSIFIER_HANDOFF_ENABLED:-false}")"
  cloudflare_account_id_yaml="$(yaml_quote "${CLOUDFLARE_ACCOUNT_ID:-}")"
  app_image_yaml="$(yaml_quote "${EMME_APP_IMAGE:-ford-service:deployment}")"
  ghcr_username_yaml="$(yaml_quote "${GHCR_USERNAME:-}")"
  grafana_endpoint_yaml="$(yaml_quote "$GRAFANA_CLOUD_OTLP_ENDPOINT")"
  grafana_instance_yaml="$(yaml_quote "$GRAFANA_CLOUD_INSTANCE_ID")"
  langfuse_endpoint_yaml="$(yaml_quote "${LANGFUSE_OTLP_ENDPOINT:-${LANGFUSE_BASE_URL:-https://us.cloud.langfuse.com}/api/public/otel}")"
  langfuse_public_yaml="$(yaml_quote "$LANGFUSE_PUBLIC_KEY")"
  ford_mcp_enabled_yaml="$(yaml_quote "${FORD_MCP_ENABLED:-false}")"
  ford_mcp_url_yaml="$(yaml_quote "${FORD_MCP_URL:-https://your-stable-mcp-domain.example.com}")"
  ford_mcp_endpoint_yaml="$(yaml_quote "${FORD_MCP_ENDPOINT:-/mcp}")"
  ford_ai_ollama_access_id_yaml="$(yaml_quote "${FORD_AI_OLLAMA_ACCESS_CLIENT_ID:-}")"
  ford_mcp_access_id_yaml="$(yaml_quote "${FORD_MCP_ACCESS_CLIENT_ID:-}")"
  backup_bucket_yaml="$(yaml_quote "${OCI_BACKUP_BUCKET:-emme-business}")"
  backup_namespace_yaml="$(yaml_quote "${OCI_OBJECT_STORAGE_NAMESPACE:-}")"
  backup_prefix_yaml="$(yaml_quote "${OCI_BACKUP_PREFIX:-ford/backups}")"
  backup_region_yaml="$(yaml_quote "${OCI_REGION}")"
  umask 077
  cat > "$inventory_file" <<EOF
all:
  vars:
    ansible_user: ${ansible_user_yaml}
    ansible_python_interpreter: /usr/bin/python3
    emme_1_private_ip: ${private_1_yaml}
    emme_2_private_ip: ${private_2_yaml}
    data_bind_address: ${data_bind_yaml}
    remote_root: ${root_yaml}
    data_volume_mount: ${mount_yaml}
    ford_operator_username: ${operator_user_yaml}
    ford_meta_auto_binding_enabled: ${ford_meta_auto_binding_enabled_yaml}
    ford_meta_provider_account_id: ${ford_meta_provider_account_id_yaml}
    ford_meta_waba_id: ${ford_meta_waba_id_yaml}
    ford_meta_phone_number: ${ford_meta_phone_number_yaml}
    ford_meta_diagnostic_stacktraces_enabled: ${ford_meta_diagnostics_yaml}
    ford_tenant_id: ${ford_tenant_id_yaml}
    ford_meta_delivery_enabled: ${ford_meta_delivery_enabled_yaml}
    ford_meta_whatsapp_delivery_endpoint: ${ford_meta_whatsapp_endpoint_yaml}
    ford_human_handoff_enabled: ${ford_human_handoff_enabled_yaml}
    ford_conversation_graph_enabled: ${ford_conversation_graph_enabled_yaml}
    ford_outbox_enabled: ${ford_outbox_enabled_yaml}
    ford_ai_chat_provider: ${ford_ai_chat_provider_yaml}
    ford_ai_cloud_provider: ${ford_ai_cloud_provider_yaml}
    ford_ai_cloud_enabled: ${ford_ai_cloud_enabled_yaml}
    ford_ai_log_provider_errors: ${ford_ai_log_provider_errors_yaml}
    ford_ai_local_enabled: ${ford_ai_local_enabled_yaml}
    ford_ai_ollama_access_client_id: ${ford_ai_ollama_access_id_yaml}
    ford_ai_local_base_url: ${ford_ai_local_base_url_yaml}
    ford_ai_local_model_version: ${ford_ai_local_model_version_yaml}
    ford_ai_local_temperature: ${ford_ai_local_temperature_yaml}
    ford_ai_cloud_base_url: ${ford_ai_cloud_base_url_yaml}
    ford_ai_cloud_model_version: ${ford_ai_cloud_model_version_yaml}
    ford_ai_cloud_temperature: ${ford_ai_cloud_temperature_yaml}
    ford_ai_provider_max_attempts: ${ford_ai_provider_max_attempts_yaml}
    ford_ai_provider_max_retry_after: ${ford_ai_provider_max_retry_after_yaml}
    ford_ai_fallback_order: ${ford_ai_fallback_order_yaml}
    ford_ai_evaluation_mode: ${ford_ai_evaluation_mode_yaml}
    ford_ai_groq_enabled: ${ford_ai_groq_enabled_yaml}
    ford_ai_groq_model: ${ford_ai_groq_model_yaml}
    ford_ai_groq_base_url: ${ford_ai_groq_base_url_yaml}
    ford_ai_groq_tool_calling_verified: ${ford_ai_groq_tool_calling_verified_yaml}
    ford_ai_groq_structured_output_verified: ${ford_ai_groq_structured_output_verified_yaml}
    ford_ai_gemini_enabled: ${ford_ai_gemini_enabled_yaml}
    ford_ai_gemini_model: ${ford_ai_gemini_model_yaml}
    ford_ai_gemini_base_url: ${ford_ai_gemini_base_url_yaml}
    ford_ai_gemini_tool_calling_verified: ${ford_ai_gemini_tool_calling_verified_yaml}
    ford_ai_gemini_structured_output_verified: ${ford_ai_gemini_structured_output_verified_yaml}
    ford_ai_cloudflare_enabled: ${ford_ai_cloudflare_enabled_yaml}
    ford_ai_cloudflare_model: ${ford_ai_cloudflare_model_yaml}
    ford_ai_cloudflare_base_url: ${ford_ai_cloudflare_base_url_yaml}
    ford_ai_cloudflare_tool_calling_verified: ${ford_ai_cloudflare_tool_calling_verified_yaml}
    ford_ai_cloudflare_structured_output_verified: ${ford_ai_cloudflare_structured_output_verified_yaml}
    ford_ai_openrouter_enabled: ${ford_ai_openrouter_enabled_yaml}
    ford_ai_openrouter_model: ${ford_ai_openrouter_model_yaml}
    ford_ai_openrouter_base_url: ${ford_ai_openrouter_base_url_yaml}
    ford_ai_openrouter_tool_calling_verified: ${ford_ai_openrouter_tool_calling_verified_yaml}
    ford_ai_openrouter_structured_output_verified: ${ford_ai_openrouter_structured_output_verified_yaml}
    ford_ai_classifier_handoff_enabled: ${ford_ai_classifier_handoff_enabled_yaml}
    cloudflare_account_id: ${cloudflare_account_id_yaml}
    app_image: ${app_image_yaml}
    ghcr_username: ${ghcr_username_yaml}
    grafana_cloud_otlp_endpoint: ${grafana_endpoint_yaml}
    grafana_cloud_instance_id: ${grafana_instance_yaml}
    langfuse_otlp_endpoint: ${langfuse_endpoint_yaml}
    langfuse_public_key: ${langfuse_public_yaml}
    ford_mcp_enabled: ${ford_mcp_enabled_yaml}
    ford_mcp_url: ${ford_mcp_url_yaml}
    ford_mcp_endpoint: ${ford_mcp_endpoint_yaml}
    ford_mcp_access_client_id: ${ford_mcp_access_id_yaml}
    backup_bucket: ${backup_bucket_yaml}
    object_storage_namespace: ${backup_namespace_yaml}
    backup_object_prefix: ${backup_prefix_yaml}
    oci_region: ${backup_region_yaml}
  children:
    app:
      hosts:
        emme-1:
          ansible_host: ${ansible_host_1_yaml}
          ansible_ssh_private_key_file: ${key_1_yaml}
    data:
      hosts:
        emme-2:
          ansible_host: ${ansible_host_2_yaml}
          ansible_ssh_private_key_file: ${key_2_yaml}
EOF
  chmod 600 "$inventory_file"
}

render_backup_inventory() {
  ensure_deploy_state_dir
  local inventory_file="$(backup_inventory_file)"
  local ansible_user_yaml host_yaml key_yaml root_yaml bucket_yaml namespace_yaml prefix_yaml region_yaml
  ansible_user_yaml="$(yaml_quote "$OCI_SSH_USER")"
  host_yaml="$(yaml_quote "$EMME_2_SSH_HOST")"
  key_yaml="$(yaml_quote "${EMME_2_SSH_KEY:-${OCI_SSH_PRIVATE_KEY}}")"
  root_yaml="$(yaml_quote "$EMME_REMOTE_ROOT")"
  bucket_yaml="$(yaml_quote "${OCI_BACKUP_BUCKET:-emme-business}")"
  namespace_yaml="$(yaml_quote "$OCI_OBJECT_STORAGE_NAMESPACE")"
  prefix_yaml="$(yaml_quote "${OCI_BACKUP_PREFIX:-ford/backups}")"
  region_yaml="$(yaml_quote "$OCI_REGION")"
  umask 077
  cat > "$inventory_file" <<EOF
all:
  vars:
    ansible_user: ${ansible_user_yaml}
    ansible_python_interpreter: /usr/bin/python3
    remote_root: ${root_yaml}
    backup_bucket: ${bucket_yaml}
    object_storage_namespace: ${namespace_yaml}
    backup_object_prefix: ${prefix_yaml}
    oci_region: ${region_yaml}
  children:
    data:
      hosts:
        emme-2:
          ansible_host: ${host_yaml}
          ansible_ssh_private_key_file: ${key_yaml}
EOF
  chmod 600 "$inventory_file"
}

yaml_quote() {
  local value="${1//\'/\'\'}"
  printf "'%s'" "$value"
}

ansible_command() {
  local playbook="$1"
  shift
  ANSIBLE_CONFIG="$(deploy_repo_root)/deployment/ansible/ansible.cfg" \
    ansible-playbook -i "$(deploy_inventory_file)" "$playbook" "$@"
}

ansible_inventory_command() {
  ANSIBLE_CONFIG="$(deploy_repo_root)/deployment/ansible/ansible.cfg" \
    ansible-inventory -i "$(deploy_inventory_file)" "$@"
}

deploy_summary() {
  printf 'deployment_config=loaded\n'
  printf 'emme-1 role=app ssh_host=%s private_ip=%s\n' "$EMME_1_SSH_HOST" "$EMME_1_PRIVATE_IP"
  printf 'emme-2 role=data ssh_host=%s private_ip=%s\n' "$EMME_2_SSH_HOST" "$EMME_2_PRIVATE_IP"
  printf 'ssh_keys=present\nruntime_secrets=present\n'
}
