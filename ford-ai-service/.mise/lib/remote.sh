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
deploy_config_file() { printf '%s\n' "$(deploy_repo_root)/deployment/config/oci.env"; }
deploy_state_dir() { printf '%s\n' "$MISE_PROJECT_ROOT/.mise/state/deploy"; }
deploy_inventory_file() { printf '%s\n' "$(deploy_state_dir)/inventory.yml"; }
backup_inventory_file() { printf '%s\n' "$(deploy_state_dir)/backup-inventory.yml"; }
infra_state_dir() { printf '%s\n' "$MISE_PROJECT_ROOT/.mise/state/infra"; }
infra_vars_file() { printf '%s\n' "$(infra_state_dir)/terraform.tfvars"; }

load_deploy_config() {
  local config_file="$(deploy_config_file)"
  local preserved_postgres_password="${POSTGRES_PASSWORD:-}"
  local preserved_redis_password="${REDIS_PASSWORD:-}"
  local preserved_operator_password="${FORD_OPERATOR_PASSWORD:-}"
  local preserved_meta_app_secret="${FORD_META_APP_SECRET:-}"
  local preserved_meta_verify_token="${FORD_META_VERIFY_TOKEN:-}"
  local preserved_ghcr_username="${GHCR_USERNAME:-}"
  local preserved_ghcr_token="${GHCR_TOKEN:-}"
  local preserved_openai_api_key="${OPENAI_API_KEY:-}"
  local preserved_mcp_token="${MCP_HTTP_BEARER_TOKEN:-}"
  local preserved_grafana_token="${GRAFANA_CLOUD_API_TOKEN:-}"
  local preserved_langfuse_secret="${LANGFUSE_SECRET_KEY:-}"
  if [[ ! -f "$config_file" ]]; then
    echo "Missing $config_file; copy deployment/config/oci.env.example first." >&2
    return 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$config_file"
  set +a
  [[ -n "$preserved_postgres_password" ]] && export POSTGRES_PASSWORD="$preserved_postgres_password"
  [[ -n "$preserved_redis_password" ]] && export REDIS_PASSWORD="$preserved_redis_password"
  [[ -n "$preserved_operator_password" ]] && export FORD_OPERATOR_PASSWORD="$preserved_operator_password"
  [[ -n "$preserved_meta_app_secret" ]] && export FORD_META_APP_SECRET="$preserved_meta_app_secret"
  [[ -n "$preserved_meta_verify_token" ]] && export FORD_META_VERIFY_TOKEN="$preserved_meta_verify_token"
  [[ -n "$preserved_ghcr_username" ]] && export GHCR_USERNAME="$preserved_ghcr_username"
  [[ -n "$preserved_ghcr_token" ]] && export GHCR_TOKEN="$preserved_ghcr_token"
  [[ -n "$preserved_openai_api_key" ]] && export OPENAI_API_KEY="$preserved_openai_api_key"
  [[ -n "$preserved_mcp_token" ]] && export MCP_HTTP_BEARER_TOKEN="$preserved_mcp_token"
  [[ -n "$preserved_grafana_token" ]] && export GRAFANA_CLOUD_API_TOKEN="$preserved_grafana_token"
  [[ -n "$preserved_langfuse_secret" ]] && export LANGFUSE_SECRET_KEY="$preserved_langfuse_secret"
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
    [[ -n "${FORD_META_PROVIDER_ACCOUNT_ID:-${FORD_META_PHONE_ID:-}}" ]] || missing+=("FORD_META_PROVIDER_ACCOUNT_ID")
    [[ -n "${FORD_TENANT_ID:-00000000-0000-0000-0000-000000000001}" ]] || missing+=("FORD_TENANT_ID")
    [[ "${FORD_META_PROVIDER_ACCOUNT_ID:-${FORD_META_PHONE_ID:-}}" =~ ^[0-9]+$ ]] || missing+=("numeric:FORD_META_PROVIDER_ACCOUNT_ID")
    is_valid_tenant_id "${FORD_TENANT_ID:-00000000-0000-0000-0000-000000000001}" || missing+=("uuid:FORD_TENANT_ID")
  fi
  if [[ "${FORD_META_DELIVERY_ENABLED:-false}" == "true" ]]; then
    [[ -n "${FORD_META_WHATSAPP_DELIVERY_ENDPOINT:-}" ]] || missing+=("FORD_META_WHATSAPP_DELIVERY_ENDPOINT")
    [[ -n "${FORD_META_WHATSAPP_ACCESS_TOKEN:-}" ]] || missing+=("FORD_META_WHATSAPP_ACCESS_TOKEN")
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
  local config_file="$(deploy_config_file)" name="$1" current value
  current="$(sed -n "s/^${name}=//p" "$config_file" | head -n 1)"
  [[ -n "$current" ]] && return
  value="${!name:-}"
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
  local postgres_yaml redis_yaml operator_user_yaml operator_password_yaml
  local meta_secret_yaml verify_token_yaml openai_key_yaml app_image_yaml ghcr_username_yaml ghcr_token_yaml
  local backup_bucket_yaml backup_namespace_yaml backup_prefix_yaml backup_region_yaml
  local grafana_endpoint_yaml grafana_instance_yaml grafana_token_yaml langfuse_endpoint_yaml langfuse_public_yaml langfuse_secret_yaml
  local ford_meta_auto_binding_enabled_yaml ford_meta_provider_account_id_yaml ford_tenant_id_yaml
  local ford_meta_delivery_enabled_yaml ford_meta_whatsapp_endpoint_yaml ford_meta_whatsapp_access_token_yaml
  local ford_outbox_enabled_yaml
  local ford_ai_chat_provider_yaml ford_ai_cloud_provider_yaml ford_ai_cloud_enabled_yaml ford_ai_local_enabled_yaml
  local ford_ai_cloud_base_url_yaml ford_ai_cloud_model_version_yaml
  local ford_mcp_enabled_yaml ford_mcp_url_yaml ford_mcp_endpoint_yaml mcp_token_yaml
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
  postgres_yaml="$(yaml_quote "$POSTGRES_PASSWORD")"
  redis_yaml="$(yaml_quote "$REDIS_PASSWORD")"
  operator_user_yaml="$(yaml_quote "$FORD_OPERATOR_USERNAME")"
  operator_password_yaml="$(yaml_quote "$FORD_OPERATOR_PASSWORD")"
  meta_secret_yaml="$(yaml_quote "${FORD_META_APP_SECRET:-}")"
  verify_token_yaml="$(yaml_quote "${FORD_META_VERIFY_TOKEN:-}")"
  ford_meta_auto_binding_enabled_yaml="$(yaml_quote "${FORD_META_AUTO_BINDING_ENABLED:-false}")"
  ford_meta_provider_account_id_yaml="$(yaml_quote "${FORD_META_PROVIDER_ACCOUNT_ID:-${FORD_META_PHONE_ID:-}}")"
  ford_tenant_id_yaml="$(yaml_quote "${FORD_TENANT_ID:-00000000-0000-0000-0000-000000000001}")"
  ford_meta_delivery_enabled_yaml="$(yaml_quote "${FORD_META_DELIVERY_ENABLED:-false}")"
  ford_meta_whatsapp_endpoint_yaml="$(yaml_quote "${FORD_META_WHATSAPP_DELIVERY_ENDPOINT:-}")"
  ford_meta_whatsapp_access_token_yaml="$(yaml_quote "${FORD_META_WHATSAPP_ACCESS_TOKEN:-}")"
  ford_outbox_enabled_yaml="$(yaml_quote "${FORD_OUTBOX_ENABLED:-false}")"
  ford_ai_chat_provider_yaml="$(yaml_quote "${FORD_AI_CHAT_PROVIDER:-openai}")"
  ford_ai_cloud_provider_yaml="$(yaml_quote "${FORD_AI_CLOUD_PROVIDER:-openai}")"
  ford_ai_cloud_enabled_yaml="$(yaml_quote "${FORD_AI_CLOUD_ENABLED:-true}")"
  ford_ai_local_enabled_yaml="$(yaml_quote "${FORD_AI_LOCAL_ENABLED:-false}")"
  ford_ai_cloud_base_url_yaml="$(yaml_quote "${FORD_AI_CLOUD_BASE_URL:-https://api.openai.com/v1}")"
  ford_ai_cloud_model_version_yaml="$(yaml_quote "${FORD_AI_CLOUD_MODEL_VERSION:-gpt-4o-mini}")"
  openai_key_yaml="$(yaml_quote "${OPENAI_API_KEY:-}")"
  app_image_yaml="$(yaml_quote "${EMME_APP_IMAGE:-ford-service:deployment}")"
  ghcr_username_yaml="$(yaml_quote "${GHCR_USERNAME:-}")"
  ghcr_token_yaml="$(yaml_quote "${GHCR_TOKEN:-}")"
  grafana_endpoint_yaml="$(yaml_quote "$GRAFANA_CLOUD_OTLP_ENDPOINT")"
  grafana_instance_yaml="$(yaml_quote "$GRAFANA_CLOUD_INSTANCE_ID")"
  grafana_token_yaml="$(yaml_quote "$GRAFANA_CLOUD_API_TOKEN")"
  langfuse_endpoint_yaml="$(yaml_quote "${LANGFUSE_OTLP_ENDPOINT:-${LANGFUSE_BASE_URL:-https://us.cloud.langfuse.com}/api/public/otel}")"
  langfuse_public_yaml="$(yaml_quote "$LANGFUSE_PUBLIC_KEY")"
  langfuse_secret_yaml="$(yaml_quote "$LANGFUSE_SECRET_KEY")"
  ford_mcp_enabled_yaml="$(yaml_quote "${FORD_MCP_ENABLED:-false}")"
  ford_mcp_url_yaml="$(yaml_quote "${FORD_MCP_URL:-https://your-stable-mcp-domain.example.com}")"
  ford_mcp_endpoint_yaml="$(yaml_quote "${FORD_MCP_ENDPOINT:-/mcp}")"
  mcp_token_yaml="$(yaml_quote "${MCP_HTTP_BEARER_TOKEN:-}")"
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
    postgres_password: ${postgres_yaml}
    redis_password: ${redis_yaml}
    ford_operator_username: ${operator_user_yaml}
    ford_operator_password: ${operator_password_yaml}
    ford_meta_app_secret: ${meta_secret_yaml}
    ford_meta_verify_token: ${verify_token_yaml}
    ford_meta_auto_binding_enabled: ${ford_meta_auto_binding_enabled_yaml}
    ford_meta_provider_account_id: ${ford_meta_provider_account_id_yaml}
    ford_tenant_id: ${ford_tenant_id_yaml}
    ford_meta_delivery_enabled: ${ford_meta_delivery_enabled_yaml}
    ford_meta_whatsapp_delivery_endpoint: ${ford_meta_whatsapp_endpoint_yaml}
    ford_meta_whatsapp_access_token: ${ford_meta_whatsapp_access_token_yaml}
    ford_outbox_enabled: ${ford_outbox_enabled_yaml}
    ford_ai_chat_provider: ${ford_ai_chat_provider_yaml}
    ford_ai_cloud_provider: ${ford_ai_cloud_provider_yaml}
    ford_ai_cloud_enabled: ${ford_ai_cloud_enabled_yaml}
    ford_ai_local_enabled: ${ford_ai_local_enabled_yaml}
    ford_ai_cloud_base_url: ${ford_ai_cloud_base_url_yaml}
    ford_ai_cloud_model_version: ${ford_ai_cloud_model_version_yaml}
    openai_api_key: ${openai_key_yaml}
    app_image: ${app_image_yaml}
    ghcr_username: ${ghcr_username_yaml}
    ghcr_token: ${ghcr_token_yaml}
    grafana_cloud_otlp_endpoint: ${grafana_endpoint_yaml}
    grafana_cloud_instance_id: ${grafana_instance_yaml}
    grafana_cloud_api_token: ${grafana_token_yaml}
    langfuse_otlp_endpoint: ${langfuse_endpoint_yaml}
    langfuse_public_key: ${langfuse_public_yaml}
    langfuse_secret_key: ${langfuse_secret_yaml}
    ford_mcp_enabled: ${ford_mcp_enabled_yaml}
    ford_mcp_url: ${ford_mcp_url_yaml}
    ford_mcp_endpoint: ${ford_mcp_endpoint_yaml}
    mcp_http_bearer_token: ${mcp_token_yaml}
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
