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
    FORD_OPERATOR_USERNAME FORD_OPERATOR_PASSWORD; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  [[ -r "${OCI_SSH_PRIVATE_KEY:-}" ]] || missing+=("readable:OCI_SSH_PRIVATE_KEY")
  [[ -r "${EMME_1_SSH_KEY:-${OCI_SSH_PRIVATE_KEY:-}}" ]] || missing+=("readable:EMME_1_SSH_KEY")
  [[ -r "${EMME_2_SSH_KEY:-${OCI_SSH_PRIVATE_KEY:-}}" ]] || missing+=("readable:EMME_2_SSH_KEY")
  if [[ "${EMME_APP_IMAGE:-}" == ghcr.io/* && -n "${GHCR_TOKEN:-}" && -z "${GHCR_USERNAME:-}" ]]; then
    missing+=("GHCR_USERNAME")
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

render_terraform_vars() {
  ensure_infra_state_dir
  umask 077
  cat > "$(infra_vars_file)" <<EOF
tenancy_ocid = "${OCI_TENANCY_OCID:-}"
user_ocid = "${OCI_USER_OCID:-}"
fingerprint = "${OCI_FINGERPRINT:-}"
private_key_path = "${OCI_API_PRIVATE_KEY_PATH:-}"
region = "${OCI_REGION:-}"
compartment_ocid = "${OCI_COMPARTMENT_OCID:-}"
availability_domain = "${OCI_AVAILABILITY_DOMAIN:-}"
existing_subnet_id = "${OCI_EXISTING_SUBNET_OCID:-}"
ssh_public_key = "${OCI_SSH_PUBLIC_KEY:-}"
object_storage_namespace = "${OCI_OBJECT_STORAGE_NAMESPACE:-}"
create_instances = false
manage_data_volume = false
manage_backup_bucket = false
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
    app_image: ${app_image_yaml}
    ghcr_username: ${ghcr_username_yaml}
    ghcr_token: ${ghcr_token_yaml}
    backup_bucket: $(yaml_quote "${OCI_BACKUP_BUCKET:-}")
    object_storage_namespace: $(yaml_quote "${OCI_OBJECT_STORAGE_NAMESPACE:-}")
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
