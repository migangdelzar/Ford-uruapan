# OCI infrastructure

Terraform is intentionally in adoption mode for the two existing Always Free
instances. It can validate the OCI provider and later create replacement
instances, the protected `emme-2` data volume, and an Object Storage backup
bucket. The defaults do not create or destroy anything.

Configure `deployment/config/oci.env` locally, including the OCI API key path
and tenancy/user/fingerprint values, then use the single Mise interface:

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

`create_instances`, `manage_data_volume`, and `manage_backup_bucket` remain
false during adoption. Existing instance OCIDs are recorded in the ignored
local OCI config and are used by Ansible/operations; they are not committed.
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
