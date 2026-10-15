# OCI infrastructure

Terraform is intentionally in adoption mode for the two existing Always Free
instances. It can validate the OCI provider and later create replacement
instances, the protected `emme-2` data volume, and an Object Storage backup
bucket. The defaults do not create or destroy anything.

Configure `deployment/config/oci.env` locally, including the OCI API key path
and tenancy/user/fingerprint values, or an existing OCI CLI profile, then use
the single Mise interface:

```bash
cd ford-ai-service
mise run infra:check
mise run infra:init
mise run infra:plan
```

Review `.mise/state/infra/planned.tfplan` before applying:

```bash
mise run infra:apply -- --confirm APPLY-INFRA
```

For the approved emme-2 backup capability use the narrower workflow:

```bash
mise run infra:backup-plan
mise run infra:backup-apply
mise run deploy:backup
mise run backup:identity-check
```

This creates the private `emme-business` bucket, retains objects under
`ford/backups/` for fourteen days, and grants the exact emme-2 instance access
through the `ford-backup-emme2` dynamic group. The VM authenticates with an
Instance Principal; do not copy an OCI API key to it.

`create_instances`, `manage_data_volume`, and `manage_backup_bucket` remain
false during adoption. Existing instance OCIDs are recorded in the ignored
local OCI config and are used by Ansible/operations; they are not committed.

The existing Vault instance-principal access is also available for adoption.
`manage_runtime_vault_identity` defaults to `false`. It describes the existing
`ford-runtime-app-emme1` and `ford-runtime-data-emme2` dynamic groups and the
`ford-runtime-secret-read` tenancy policy. Each group matches one existing VM;
each policy statement grants `read secret-bundles` for one production secret
name in the configured Vault. The removed BFF credential is excluded from the
desired policy. No Vault values enter Terraform state.

Import all three existing resources before any apply. Set the existing
`emme_1_instance_ocid`, `emme_2_instance_ocid`, `runtime_vault_ocid`,
`compartment_ocid`, and `tenancy_ocid` in a private tfvars file along with
`manage_runtime_vault_identity = true`. Use the three OCIDs returned by OCI
Identity for the existing dynamic groups and policy; never substitute the VM
or Vault OCIDs for the import IDs:

```bash
cd deployment/terraform
terraform init
terraform import -var-file=private.tfvars 'oci_identity_dynamic_group.runtime_vault["app"]' '<existing-app-dynamic-group-ocid>'
terraform import -var-file=private.tfvars 'oci_identity_dynamic_group.runtime_vault["data"]' '<existing-data-dynamic-group-ocid>'
terraform import -var-file=private.tfvars 'oci_identity_policy.runtime_vault[0]' '<existing-policy-ocid>'
terraform plan -var-file=private.tfvars -out=runtime-vault.tfplan
```

Review the entire plan and state before applying. The initial policy plan
should remove the stale BFF secret-name grant while preserving all other named
grants. If any group replacement, resource creation, or broader policy grant
appears, stop and reconcile the imported state and desired configuration.
Keep the private tfvars file and Terraform state outside version control.
Third-party chat-provider API keys use the separate private Mise/Ansible
application-environment path; they are not Terraform variables or state.
Before Terraform owns an existing instance, import it into a reviewed state
using its exact resource address and a reviewed configuration. Do not enable
creation or destroy plans until the image, subnet, compartment, and
availability-domain values have been filled in.

Before the application deployment, add OCI ingress rules to the subnet/NSG
that hosts `emme-2`: allow TCP 5432 and 6379 only from `10.0.0.107/32`.
The Ansible role also enforces the same restriction in firewalld. No public
ingress rule is required for either database port.

The protected data resources have `prevent_destroy = true`. The normal
destroy task refuses a plan containing them, and data destruction additionally
requires a verified backup marker plus a separately reviewed target plan.
