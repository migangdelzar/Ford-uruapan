# Adopt the existing runtime identities by importing all three resources before
# enabling Terraform management. The default leaves OCI identity unchanged.
locals {
  runtime_vault_policy_contract = jsondecode(file("${path.module}/runtime-vault-policy.json"))

  runtime_vault_instances = {
    app  = { name = local.runtime_vault_policy_contract.instances.app, instance_ocid = var.emme_1_instance_ocid }
    data = { name = local.runtime_vault_policy_contract.instances.data, instance_ocid = var.emme_2_instance_ocid }
  }
  runtime_vault_secret_names = local.runtime_vault_policy_contract.secret_names
}

resource "oci_identity_dynamic_group" "runtime_vault" {
  for_each       = var.manage_runtime_vault_identity ? local.runtime_vault_instances : {}
  compartment_id = var.tenancy_ocid
  name           = each.value.name
  description    = "Runtime instance principal for named Ford production secrets."
  matching_rule  = "ALL {instance.id = '${each.value.instance_ocid}'}"

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = can(regex("^ocid1\\.instance\\.", each.value.instance_ocid))
      error_message = "Both runtime instance OCIDs are required before managing Vault identity."
    }
  }
}

resource "oci_identity_policy" "runtime_vault" {
  count          = var.manage_runtime_vault_identity ? 1 : 0
  compartment_id = var.tenancy_ocid
  name           = "ford-runtime-secret-read"
  description    = "Named runtime secret-bundle reads from the Ford Vault."
  statements = flatten([
    for runtime_key, secret_names in local.runtime_vault_secret_names : [
      for secret_name in secret_names :
      "Allow dynamic-group ${oci_identity_dynamic_group.runtime_vault[runtime_key].name} to read secret-bundles in compartment id ${var.compartment_ocid} where all {target.vault.id = '${var.runtime_vault_ocid}', target.secret.name = '${secret_name}'}"
    ]
  ])

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = can(regex("^ocid1\\.vault\\.", var.runtime_vault_ocid)) && can(regex("^ocid1\\.(compartment|tenancy)\\.", var.compartment_ocid))
      error_message = "The existing Vault and secret compartment OCIDs are required before managing Vault identity."
    }
  }
}
