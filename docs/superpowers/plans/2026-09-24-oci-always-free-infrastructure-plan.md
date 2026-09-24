# OCI Always Free Infrastructure and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Manage the two existing OCI Always Free VMs, their private network and persistent data lifecycle with Terraform, configure them with Ansible, and expose all safe operations through Mise.

**Architecture:** Terraform owns OCI infrastructure and persistent storage; Ansible configures the operating systems, Docker, production Compose files, and backup jobs; Mise calls both tools and provides guardrails. `emme-1` runs the Ford application and Cloudflare Tunnel, while `emme-2` runs PostgreSQL/pgvector and Redis. Compute can be replaced without deleting the protected data volume or Object Storage backups.

**Tech Stack:** Terraform OCI provider, Ansible Core, `community.docker`, Docker Compose v2, PostgreSQL `pg_dump`, Redis persistence, OCI Object Storage, Mise, direct SSH.

## Global Constraints

- Preserve the existing two-VM Always Free target: two `VM.Standard.A1.Flex` instances at 1 OCPU and 6 GB each.
- Keep Terraform in the OCI home region and keep combined boot/data block storage within the 200 GB Always Free allowance.
- Keep VM SSH endpoints, private IPs, OCIDs, credentials, and backup secrets outside Git.
- `terraform destroy` must not delete the protected PostgreSQL data volume or backup bucket by default.
- A destructive data operation requires an explicit `--confirm` value and a recent successful backup.
- Do not expose PostgreSQL or Redis through Cloudflare or the public Internet.
- Do not run the full local observability stack on the two Always Free VMs initially.
- Keep `mise run ...` as the only documented operator interface.
- Do not modify unrelated pre-existing worktree files.

## Target resource model

| Resource | Terraform ownership | Default lifecycle |
|---|---|---|
| VCN, subnets, route tables, NSGs | Managed | Recreated only through an explicit infrastructure destroy |
| `emme-1` compute | Managed | Re-creatable |
| `emme-2` compute | Managed | Re-creatable while data volume is preserved |
| `emme-2` data block volume | Managed and protected | `prevent_destroy = true` |
| Boot volumes | Managed | Preserved during compute replacement unless explicitly removed |
| Object Storage backup bucket | Managed and protected | `prevent_destroy = true` |
| Cloudflare Tunnel | Existing external resource | Configured by Ansible, not recreated by Terraform in this phase |

The two default 50 GB boot volumes consume 100 GB of the 200 GB Always Free
block-volume allowance. The remaining allowance is reserved for the data
volume; Object Storage backups are subject to the current Always Free object
storage quota and retention policy. [OCI Always Free resources](https://docs.oracle.com/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)

## Task 1: Pin local infrastructure tooling

**Files:**

- Modify: `ford-ai-service/mise.toml`
- Create: `deployment/ansible/requirements.yml`
- Create: `deployment/ansible/ansible.cfg`
- Modify: `ford-ai-service/mise.toml` with the pinned Terraform tool declaration

**Interfaces:**

- Produces `terraform`, `ansible-playbook`, `ansible-inventory`, and the pinned `community.docker` collection for later tasks.

- [ ] Add pinned Terraform and Ansible tool declarations to the existing Mise configuration without changing the Java tool declaration.
- [ ] Pin `community.docker` in `deployment/ansible/requirements.yml` and configure Ansible to use the repository-local roles and inventory paths.
- [ ] Configure Ansible SSH defaults to use strict host-key checking and the deployment key supplied by the generated inventory.
- [ ] Add `mise run infra:check` and `mise run deploy:check` as read-only prerequisite checks.
- [ ] Verify with:

```bash
mise install
mise run infra:check
mise run deploy:check
ansible-galaxy collection list | rg 'community.docker'
```

Expected result: all required binaries exist, the collection version is pinned, and no remote mutation occurs.

## Task 2: Create the variable and inventory boundary

**Files:**

- Create: `deployment/config/oci.env.example`
- Create: `deployment/ansible/inventory/hosts.yml.tmpl`
- Create: `deployment/ansible/group_vars/all.yml.tmpl`
- Modify: `.gitignore`
- Create: `ford-ai-service/.mise/lib/remote.sh`
- Create: `ford-ai-service/.mise/tasks/deploy/config`

**Interfaces:**

- `deployment/config/oci.env` is the ignored operator input.
- `remote.sh` exports `deploy_inventory`, `deploy_env`, `ansible_command`, `target_host`, and `require_deploy_config`.
- `deploy:config` renders inventory and group variables under `.mise/state/deploy/` with mode `0600`.

The operator file must define these names:

```dotenv
OCI_COMPARTMENT_OCID=
OCI_TENANCY_OCID=
OCI_USER_OCID=
OCI_FINGERPRINT=
OCI_REGION=
OCI_AVAILABILITY_DOMAIN=
OCI_SSH_USER=ubuntu
OCI_SSH_PRIVATE_KEY=/absolute/path/to/oci-emme
EMME_1_SSH_HOST=
EMME_2_SSH_HOST=
EMME_1_PRIVATE_IP=
EMME_2_PRIVATE_IP=
EMME_REMOTE_ROOT=/opt/ford-uruapan
EMME_DATA_VOLUME_MOUNT=/var/lib/ford-data
OCI_BACKUP_BUCKET=
OCI_OBJECT_STORAGE_NAMESPACE=
POSTGRES_PASSWORD=
REDIS_PASSWORD=
FORD_OPERATOR_USERNAME=operator
FORD_OPERATOR_PASSWORD=
FORD_META_APP_SECRET=
FORD_META_VERIFY_TOKEN=
OPENAI_API_KEY=
```

- [ ] Validate that all required values are non-empty except optional AI values.
- [ ] Render `emme-1` and `emme-2` with `ansible_host` from SSH endpoints and private service addresses as group variables.
- [ ] Ensure rendered files are ignored and never printed with secret values.
- [ ] Add `deploy:config` output that lists only hostnames, roles, and whether required values are present.
- [ ] Test with a temporary sanitized environment file and assert that the inventory contains both hosts but no secret value is printed.

## Task 3: Add Terraform infrastructure modules with safe destruction

**Files:**

- Create: `deployment/terraform/versions.tf`
- Create: `deployment/terraform/providers.tf`
- Create: `deployment/terraform/variables.tf`
- Create: `deployment/terraform/network.tf`
- Create: `deployment/terraform/compute.tf`
- Create: `deployment/terraform/storage.tf`
- Create: `deployment/terraform/outputs.tf`
- Create: `deployment/terraform/terraform.tfvars.example`
- Create: `ford-ai-service/.mise/tasks/infra/init`
- Create: `ford-ai-service/.mise/tasks/infra/plan`
- Create: `ford-ai-service/.mise/tasks/infra/apply`
- Create: `ford-ai-service/.mise/tasks/infra/status`
- Create: `ford-ai-service/.mise/tasks/infra/destroy`
- Create: `ford-ai-service/.mise/tasks/infra/destroy-data`

**Interfaces:**

- `infra:init` initializes the OCI provider and state backend.
- `infra:plan` is read-only and produces a plan file under `.mise/state/infra/`.
- `infra:apply` applies only the reviewed plan file.
- `infra:destroy` removes compute/network resources while retaining protected data resources.
- `infra:destroy-data -- --confirm DESTROY-DATA` removes the protected data volume and backup bucket only after a backup verification.

- [ ] Define the OCI provider from environment variables and do not put API keys in Terraform variables or committed files.
- [ ] Model a VCN, an app subnet, a data subnet, NSGs allowing SSH from the configured administrator CIDR and PostgreSQL/Redis only from `emme-1` private IP.
- [ ] Model two ARM64 `VM.Standard.A1.Flex` instances with 1 OCPU and 6 GB memory each.
- [ ] Model a separate data block volume attached to `emme-2`, with `prevent_destroy = true`.
- [ ] Model an Object Storage bucket for backups with `prevent_destroy = true` and a lifecycle rule matching the configured retention window.
- [ ] Export `emme_1_private_ip`, `emme_2_private_ip`, `data_volume_id`, and `backup_bucket_name` for Ansible.
- [ ] Add validation preventing a requested OCPU/memory total above 2 OCPUs/12 GB and preventing data volume plus boot volumes above 200 GB.
- [ ] Add `infra:destroy` guardrails that reject execution unless the plan contains no protected data deletion.
- [ ] Add explicit confirmation and a successful backup marker check to `infra:destroy-data`.
- [ ] Validate with:

```bash
mise run infra:init
mise run infra:plan
terraform -chdir=deployment/terraform validate
terraform -chdir=deployment/terraform fmt -check
```

The initial adoption path must support importing the existing VMs and volumes before Terraform manages them. It must not recreate a manually created VM during the first `infra:plan`.

## Task 4: Separate production runtime Compose files

**Files:**

- Create: `deployment/runtime/data/compose.yml`
- Create: `deployment/runtime/app/compose.yml`
- Create: `deployment/runtime/data/.env.template`
- Create: `deployment/runtime/app/.env.template`

**Interfaces:**

- Data Compose starts only PostgreSQL/pgvector and Redis with persistent bind mounts under `/var/lib/ford-data`.
- App Compose starts only the ARM64 Ford image and binds `127.0.0.1:8080`.
- App Compose consumes `FORD_DATABASE_URL`, `FORD_REDIS_HOST`, `FORD_REDIS_PORT`, and the production secret variables generated by Ansible.

- [ ] Add PostgreSQL health checks using `pg_isready` and Redis health checks using authenticated `redis-cli`.
- [ ] Enable Redis append-only persistence and mount both service data directories on the protected data volume.
- [ ] Remove all public host bindings for PostgreSQL and Redis from the production Compose file; OCI NSGs remain the network boundary.
- [ ] Remove `depends_on` references to local services and OTEL from production app Compose.
- [ ] Disable OTEL metric export and set trace sampling to zero by default for this two-VM footprint.
- [ ] Ensure the production Compose files pass `docker compose config` with generated non-secret test values.

## Task 5: Build Ansible roles for VM configuration and deployment

**Files:**

- Create: `deployment/ansible/site.yml`
- Create: `deployment/ansible/roles/common/tasks/main.yml`
- Create: `deployment/ansible/roles/common/handlers/main.yml`
- Create: `deployment/ansible/roles/data/tasks/main.yml`
- Create: `deployment/ansible/roles/data/templates/compose.yml.j2`
- Create: `deployment/ansible/roles/data/templates/data.env.j2`
- Create: `deployment/ansible/roles/app/tasks/main.yml`
- Create: `deployment/ansible/roles/app/templates/compose.yml.j2`
- Create: `deployment/ansible/roles/app/templates/app.env.j2`
- Create: `deployment/ansible/roles/app/handlers/main.yml`

**Interfaces:**

- `site.yml --tags common` configures prerequisites on both VMs.
- `site.yml --limit emme-2 --tags data` configures storage and data services.
- `site.yml --limit emme-1 --tags app` deploys the application image and app service.
- `site.yml --tags verify` runs remote connectivity and health checks.

- [ ] Install or verify Docker Engine and Compose v2 using OS-aware Ansible tasks for Ubuntu and Oracle Linux.
- [ ] Mount the Terraform-managed data volume at `/var/lib/ford-data` without reformatting an existing filesystem.
- [ ] Create `/opt/ford-uruapan/{app,data,backup}` with ownership and mode `0750`.
- [ ] Render environment files with mode `0600` and notify service restart only when configuration changes.
- [ ] Use `community.docker.docker_compose_v2` to start, stop, restart, and inspect Compose services.
- [ ] Transfer the locally built ARM64 image to `emme-1` without building on the VM.
- [ ] Verify `emme-1` can reach `emme-2:5432` and `emme-2:6379` over private networking.
- [ ] Verify the local app health endpoint on `emme-1` and the public Cloudflare endpoint after deployment.

## Task 6: Add database backup and restore automation

**Files:**

- Create: `deployment/ansible/roles/backup/tasks/main.yml`
- Create: `deployment/ansible/roles/backup/templates/backup-databases.sh.j2`
- Create: `deployment/ansible/roles/backup/templates/ford-database-backup.service.j2`
- Create: `deployment/ansible/roles/backup/templates/ford-database-backup.timer.j2`
- Create: `deployment/ansible/roles/backup/templates/restore-database.sh.j2`
- Create: `ford-ai-service/.mise/tasks/backup/run`
- Create: `ford-ai-service/.mise/tasks/backup/list`
- Create: `ford-ai-service/.mise/tasks/backup/verify`
- Create: `ford-ai-service/.mise/tasks/backup/restore`

**Interfaces:**

- `mise run backup:run` triggers a remote backup on `emme-2`.
- `mise run backup:list` lists retained Object Storage objects without exposing secrets.
- `mise run backup:verify` downloads a selected dump to a temporary location and runs `pg_restore --list`.
- `mise run backup:restore -- --confirm RESTORE-DATABASE <object>` restores only after stopping the app and explicit confirmation.

- [ ] Use `pg_dump --format=custom --no-owner --file=<timestamp>.dump` for PostgreSQL.
- [ ] Capture Redis persistence state with `redis-cli --rdb` or a consistent RDB snapshot; document Redis as a rebuildable cache rather than the source of truth.
- [ ] Compress and encrypt backup archives before upload; never log database contents, credentials, or object names containing customer identifiers.
- [ ] Upload to the Terraform-managed Object Storage bucket using an OCI instance principal or a least-privilege deployment identity.
- [ ] Retain seven daily PostgreSQL backups and remove expired objects only after listing and verifying the retention set.
- [ ] Write a successful backup marker consumed by `infra:destroy-data`.
- [ ] Test backup creation, object listing, archive verification, and restore into a disposable PostgreSQL container before enabling production restore.

Terraform provisions the bucket and access policy, but it never performs the database backup itself.

## Task 7: Add Mise orchestration commands

**Files:**

- Create: `ford-ai-service/.mise/tasks/deploy/config`
- Create: `ford-ai-service/.mise/tasks/deploy/bootstrap`
- Create: `ford-ai-service/.mise/tasks/deploy/data`
- Create: `ford-ai-service/.mise/tasks/deploy/app`
- Create: `ford-ai-service/.mise/tasks/deploy/all`
- Create: `ford-ai-service/.mise/tasks/deploy/status`
- Create: `ford-ai-service/.mise/tasks/deploy/logs`
- Create: `ford-ai-service/.mise/tasks/deploy/restart`
- Create: `ford-ai-service/.mise/tasks/deploy/stop`
- Create: `ford-ai-service/.mise/tasks/infra/check`
- Create: `ford-ai-service/.mise/tasks/backup/*`

**Interfaces:**

```bash
mise run infra:plan
mise run infra:apply
mise run deploy:all
mise run deploy:status
mise run backup:run
mise run backup:verify
```

- [ ] Make every task load the generated inventory and fail before mutation when configuration is missing.
- [ ] Make `deploy:all` run `common`, then `data`, then `app`, then `verify` in that order.
- [ ] Make `deploy:stop` stop services but never remove volumes.
- [ ] Make `infra:destroy-data` and `backup:restore` require explicit confirmation strings.
- [ ] Keep all temporary inventory, plan, rendered environment, and image archive files under ignored `.mise/state/`.
- [ ] Add a `--check` path for Ansible tasks and a `--limit` pass-through for targeted operations.

## Task 8: Documentation and verification

**Files:**

- Modify: `ford-ai-service/README.md`
- Modify: `ford-ai-service/docs/operations.md`
- Create: `docs/oci-deployment.md`
- Create: `scripts/infra-contract.test.mjs`

- [ ] Document the exact first-time flow: OCI prerequisites, local `.env` creation, Terraform import, `infra:plan`, Ansible bootstrap, deployment, and Cloudflare validation.
- [ ] Document the safe destroy/redeploy flow and the explicit data-destruction flow.
- [ ] Document the backup schedule, retention, verification, restore procedure, and Redis rebuild behavior.
- [ ] Add contract tests for required Mise tasks, Terraform files, Ansible roles, protected lifecycle declarations, and ignored secret paths.
- [ ] Run:

```bash
bash -n ford-ai-service/.mise/lib/remote.sh
find ford-ai-service/.mise/tasks -type f -print0 | xargs -0 -n1 bash -n
terraform -chdir=deployment/terraform fmt -check
terraform -chdir=deployment/terraform validate
ansible-playbook -i .mise/state/deploy/inventory.yml deployment/ansible/site.yml --syntax-check
node scripts/infra-contract.test.mjs
mise run test
```

- [ ] With real OCI variables loaded, run `mise run infra:plan`, review that no existing data volume is destroyed, run `mise run deploy:all`, verify the Cloudflare API endpoint, run `mise run backup:run`, and verify the backup object.

## Rollout checkpoints

1. Tooling and generated inventory work without remote mutation.
2. Terraform adoption plan is zero-destroy for existing VMs and data volumes.
3. Ansible check mode succeeds for both hosts.
4. Data services deploy and survive a data VM recreation with the protected volume.
5. App deploys and reaches health through Cloudflare Tunnel.
6. Backup verification succeeds before any destructive lifecycle task is enabled.
