#!/usr/bin/env bash
set -euo pipefail

: "${TF_OCI_TENANCY_OCID:?missing TF_OCI_TENANCY_OCID}"
: "${TF_OCI_USER_OCID:?missing TF_OCI_USER_OCID}"
: "${TF_OCI_FINGERPRINT:?missing TF_OCI_FINGERPRINT}"
: "${TF_OCI_REGION:?missing TF_OCI_REGION}"
: "${TF_OCI_PRIVATE_KEY:?missing TF_OCI_PRIVATE_KEY}"
: "${TF_OBJECT_STORAGE_NAMESPACE:?missing TF_OBJECT_STORAGE_NAMESPACE}"
: "${TF_EMME_1_INSTANCE_OCID:?missing TF_EMME_1_INSTANCE_OCID}"
: "${TF_EMME_2_INSTANCE_OCID:?missing TF_EMME_2_INSTANCE_OCID}"
: "${TF_RUNTIME_VAULT_OCID:?missing TF_RUNTIME_VAULT_OCID}"

umask 077
key_path="$RUNNER_TEMP/terraform-oci-key.pem"
printf '%s\n' "$TF_OCI_PRIVATE_KEY" > "$key_path"
chmod 0600 "$key_path"
mkdir -p "$HOME/.oci"
cat > "$HOME/.oci/config" <<CONFIG
[DEFAULT]
user=$TF_OCI_USER_OCID
fingerprint=$TF_OCI_FINGERPRINT
key_file=$key_path
tenancy=$TF_OCI_TENANCY_OCID
region=$TF_OCI_REGION
CONFIG
chmod 0600 "$HOME/.oci/config"

{
  printf 'TF_VAR_tenancy_ocid=%s\n' "$TF_OCI_TENANCY_OCID"
  printf 'TF_VAR_user_ocid=%s\n' "$TF_OCI_USER_OCID"
  printf 'TF_VAR_fingerprint=%s\n' "$TF_OCI_FINGERPRINT"
  printf 'TF_VAR_private_key_path=%s\n' "$key_path"
  printf 'TF_VAR_region=%s\n' "$TF_OCI_REGION"
  printf 'TF_VAR_compartment_ocid=%s\n' "$TF_OCI_TENANCY_OCID"
  printf 'TF_VAR_oci_config_profile=DEFAULT\n'
  printf 'TF_VAR_availability_domain=unused\n'
  printf 'TF_VAR_existing_subnet_id=unused\n'
  printf 'TF_VAR_ssh_public_key=unused\n'
  printf 'TF_VAR_manage_runtime_vault_identity=true\n'
  printf 'TF_VAR_create_instances=false\n'
  printf 'TF_VAR_manage_data_volume=false\n'
  printf 'TF_VAR_manage_backup_bucket=false\n'
  printf 'TF_VAR_emme_1_instance_ocid=%s\n' "$TF_EMME_1_INSTANCE_OCID"
  printf 'TF_VAR_emme_2_instance_ocid=%s\n' "$TF_EMME_2_INSTANCE_OCID"
  printf 'TF_VAR_runtime_vault_ocid=%s\n' "$TF_RUNTIME_VAULT_OCID"
  printf 'TF_VAR_object_storage_namespace=%s\n' "$TF_OBJECT_STORAGE_NAMESPACE"
} >> "$GITHUB_ENV"
