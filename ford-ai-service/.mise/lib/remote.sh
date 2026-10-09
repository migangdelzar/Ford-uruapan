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
  if [[ ! -f "$config_file" ]]; then
    echo "Missing $config_file; copy deployment/config/oci.env.example first." >&2
    return 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$config_file"
  set +a
}

require_deploy_config() {
  local missing=() name
  for name in OCI_REGION OCI_SSH_USER OCI_SSH_PRIVATE_KEY EMME_1_SSH_HOST \
    EMME_2_SSH_HOST EMME_1_PRIVATE_IP EMME_2_PRIVATE_IP EMME_REMOTE_ROOT \
    EMME_DATA_VOLUME_MOUNT POSTGRES_PASSWORD REDIS_PASSWORD \
    FORD_OPERATOR_USERNAME FORD_OPERATOR_PASSWORD \
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
  if [[ "${FORD_MCP_ENABLED:-false}" == "true" ]]; then
    for name in FORD_MCP_SERVER_URL FORD_MCP_TENANT_ID MCP_HTTP_BEARER_TOKEN; do
      [[ -n "${!name:-}" ]] || missing+=("$name")
    done
  fi
  if (( ${#missing[@]} > 0 )); then
    printf 'Missing deployment configuration: %s\n' "${missing[*]}" >&2
    return 1
  fi
}

ensure_deploy_secret() {
  local config_file="$(deploy_config_file)" name="$1" current value temp
  current="$(sed -n "s/^${name}=//p" "$config_file" | head -n 1)"
  [[ -n "$current" ]] && return
  require_command openssl
  value="$(openssl rand -hex 32)"
  temp="$(mktemp "${config_file}.XXXXXX")"
  awk -v name="$name" -v value="$value" '
    index($0, name "=") == 1 { print name "=" value; found = 1; next }
    { print }
    END { if (!found) print name "=" value }
  ' "$config_file" > "$temp"
  chmod 600 "$temp"
  mv "$temp" "$config_file"
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
  local mcp_enabled_yaml mcp_url_yaml mcp_tenant_yaml mcp_token_yaml mcp_timeout_yaml
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
  mcp_enabled_yaml="$(yaml_quote "${FORD_MCP_ENABLED:-false}")"
  mcp_url_yaml="$(yaml_quote "${FORD_MCP_SERVER_URL:-http://127.0.0.1:3001}")"
  mcp_tenant_yaml="$(yaml_quote "${FORD_MCP_TENANT_ID:-}")"
  mcp_token_yaml="$(yaml_quote "${MCP_HTTP_BEARER_TOKEN:-}")"
  mcp_timeout_yaml="$(yaml_quote "${FORD_MCP_REQUEST_TIMEOUT:-8s}")"
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
    openai_api_key: ${openai_key_yaml}
    ford_mcp_enabled: ${mcp_enabled_yaml}
    ford_mcp_server_url: ${mcp_url_yaml}
    ford_mcp_tenant_id: ${mcp_tenant_yaml}
    mcp_http_bearer_token: ${mcp_token_yaml}
    ford_mcp_request_timeout: ${mcp_timeout_yaml}
    app_image: ${app_image_yaml}
    ghcr_username: ${ghcr_username_yaml}
    ghcr_token: ${ghcr_token_yaml}
    grafana_cloud_otlp_endpoint: ${grafana_endpoint_yaml}
    grafana_cloud_instance_id: ${grafana_instance_yaml}
    grafana_cloud_api_token: ${grafana_token_yaml}
    langfuse_otlp_endpoint: ${langfuse_endpoint_yaml}
    langfuse_public_key: ${langfuse_public_yaml}
    langfuse_secret_key: ${langfuse_secret_yaml}
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
