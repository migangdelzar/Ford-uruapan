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

## Protected GitHub Actions runtime Vault policy

`.github/workflows/terraform-runtime-vault.yml` is the protected Actions path
for the narrow runtime Vault IAM policy. It is manually dispatched from `main`
and uses the `production` environment review gate for both planning and applying.
It imports the two existing runtime dynamic groups and the existing policy into
remote state, then rejects any plan that changes compute, storage, or either
dynamic group. Only an in-place update to `ford-runtime-secret-read` is allowed.

The workflow uses a dedicated OCI API user. Do not use a developer/admin key.
Its OCI native backend is the private, versioned `ford-terraform-state` bucket,
with state locking and the key `ford-uruapan/runtime-vault-identity/terraform.tfstate`.
Do not add a lifecycle expiration rule to this bucket.

The OCI bootstrap identity needs these three policy statements, scoped to the
exact resources it operates on:

```text
Allow group ford-terraform-actions to manage objects in tenancy where target.bucket.name = 'ford-terraform-state'
Allow group ford-terraform-actions to read dynamic-groups in tenancy
Allow group ford-terraform-actions to manage policies in tenancy where target.policy.name = 'ford-runtime-secret-read'
```

The production GitHub environment needs these non-secret variables:

- `TF_OCI_TENANCY_OCID`, `TF_OCI_USER_OCID`, `TF_OCI_FINGERPRINT`, `TF_OCI_REGION`
- `TF_OBJECT_STORAGE_NAMESPACE`
- `TF_EMME_1_INSTANCE_OCID`, `TF_EMME_2_INSTANCE_OCID`, `TF_RUNTIME_VAULT_OCID`
- `TF_RUNTIME_APP_DYNAMIC_GROUP_OCID`, `TF_RUNTIME_DATA_DYNAMIC_GROUP_OCID`
- `TF_RUNTIME_VAULT_POLICY_OCID`

Store only the dedicated user's unencrypted API private key in the environment
secret `TF_OCI_PRIVATE_KEY`. Actions writes it to a permission-restricted runner
file and removes the runner after the job. No credential value is placed in
Terraform variables, state, or the plan.

Dispatch `Terraform runtime Vault policy` with `plan` to adopt and review the
existing resources. Confirm the plan grants only the named runtime secrets
listed in `runtime-vault-identity.tf`. Dispatch `apply` to generate a fresh plan
and apply it after the protected environment approval. The plan artifact
expires after one day. This workflow never deploys application code. The data
instance's former catalog-service secret grants are removed from the desired
policy; apply that reviewed policy change during the protected runtime cleanup.
