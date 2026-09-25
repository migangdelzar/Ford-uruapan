# OCI Backup Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run verified PostgreSQL and Redis backups on `emme-2`, upload them to OCI Object Storage through an instance principal, and expose safe Mise operations.

**Architecture:** Ansible installs a root-owned systemd service/timer on `emme-2`. The service creates PostgreSQL and Redis artifacts, writes a manifest, uploads them with OCI CLI, verifies each remote object, and only then copies/prunes local archives. Terraform owns the protected `emme-business` bucket, lifecycle policy, dynamic group, and IAM policies.

**Tech Stack:** Ansible, systemd, PostgreSQL `pg_dump`, Redis RDB snapshot, OCI CLI, OCI Object Storage, OCI instance principals, Mise.

## Global Constraints

- PostgreSQL is authoritative; Redis is rebuildable cache state.
- The remote `emme-business` bucket retains fourteen daily archives under `ford/backups/`; local storage retains seven days.
- No OCI API private key is copied to `emme-2`.
- A failed upload never deletes the only local archive.
- Restore requires `--confirm RESTORE-DATABASE`, a fresh verified backup, and app quiescence.
- Backup logs never contain credentials, data, prompt content, or customer identifiers.

### Task 1: Model protected Object Storage and instance-principal inputs

**Files:**
- Modify: `deployment/terraform/storage.tf`
- Modify: `deployment/terraform/variables.tf`
- Modify: `deployment/terraform/outputs.tf`
- Modify: `deployment/config/oci.env.example`
- Test: `scripts/operations-contract.test.mjs`

- [ ] **Step 1: Add failing infrastructure assertions**

Require a protected backup bucket, a fourteen-day lifecycle rule, a bucket output,
and no Object Storage API key in the deployment environment example.

- [ ] **Step 2: Implement bucket and policy inputs**

Add the bucket with `prevent_destroy`, standard storage, and an expiration rule.
Document the dynamic group and least-privilege policy needed for the existing
instance OCID. Keep adoption flags disabled by default until an import plan is
reviewed.

- [ ] **Step 3: Validate Terraform**

Run `terraform -chdir=deployment/terraform fmt -check` and
`terraform -chdir=deployment/terraform validate`.

- [ ] **Step 4: Commit**

```bash
git add deployment/terraform deployment/config/oci.env.example scripts/operations-contract.test.mjs
git commit -m "infra: define protected OCI backup storage"
```

### Task 2: Install the backup service and timer

**Files:**
- Modify: `deployment/ansible/roles/backup/tasks/main.yml`
- Modify: `deployment/ansible/roles/backup/templates/backup-databases.sh.j2`
- Create: `deployment/ansible/roles/backup/templates/ford-backup.service.j2`
- Create: `deployment/ansible/roles/backup/templates/ford-backup.timer.j2`
- Modify: `deployment/ansible/roles/backup/templates/restore-database.sh.j2`
- Test: `scripts/operations-contract.test.mjs`

- [ ] **Step 1: Add failing backup assertions**

Require systemd timer scheduling, `pg_dump`, Redis snapshot, manifest hashing,
OCI Object Storage upload, remote verification, and no cron usage.

- [ ] **Step 2: Implement the service**

Install OCI CLI, render the root-owned backup script, install the service/timer,
and run the backup script as root. Use the instance-principal OCI CLI signer;
the VM receives no OCI API private key.

- [ ] **Step 3: Implement safe restore**

Require a verified archive, decrypt to a temporary directory, stop/quiesce the
app service, restore PostgreSQL, and restart the app. Validate the archive name
against a strict basename pattern and remove temporary plaintext data with a
trap.

- [ ] **Step 4: Run static checks**

Run `node --test scripts/operations-contract.test.mjs` and
`ansible-playbook --syntax-check` with a sanitized inventory.

- [ ] **Step 5: Commit**

```bash
git add deployment/ansible/roles/backup scripts/operations-contract.test.mjs
git commit -m "ops: schedule encrypted OCI database backups"
```

### Task 3: Add Mise backup operations and verification

**Files:**
- Modify: `ford-ai-service/.mise/tasks/backup/run`
- Modify: `ford-ai-service/.mise/tasks/backup/list`
- Modify: `ford-ai-service/.mise/tasks/backup/verify`
- Modify: `ford-ai-service/.mise/tasks/backup/restore`
- Modify: `ford-ai-service/.mise/lib/remote.sh`
- Test: `scripts/operations-contract.test.mjs`

- [ ] **Step 1: Add failing operation assertions**

Require bucket object listing, remote manifest verification, explicit restore
confirmation, and no secret printing.

- [ ] **Step 2: Implement operations**

Use direct SSH for the remote systemd service and the local OCI CLI profile for
Object Storage listing/verification. Write only a timestamp marker under
ignored `.mise/state/backup/` after verified success.

- [ ] **Step 3: Verify the contract**

Run `node --test scripts/operations-contract.test.mjs`.

- [ ] **Step 4: Commit**

```bash
git add ford-ai-service/.mise/tasks/backup ford-ai-service/.mise/lib/remote.sh scripts/operations-contract.test.mjs
git commit -m "ops: add verified remote backup operations"
```

### Task 4: Run a real backup and recovery drill

- [ ] **Step 1: Apply only reviewed Terraform changes**

Run `mise run infra:plan`, confirm the plan does not destroy the existing data
volume or bucket, then apply only after the Object Storage policy is present.

- [ ] **Step 2: Install the timer on `emme-2`**

Run `mise run deploy:data` and inspect `systemctl list-timers` and the service
unit. Do not reset or recreate the database volume.

- [ ] **Step 3: Trigger and verify**

Run `mise run backup:run`, `mise run backup:list`, and `mise run backup:verify`.
Confirm the object exists and the dump contains a valid PostgreSQL TOC.

- [ ] **Step 4: Test restore safely**

Restore into a disposable PostgreSQL container first. Only then document the
production restore command requiring the explicit confirmation token.

- [ ] **Step 5: Document operations**

Update `docs/operations.md` with schedule, retention, IAM policy, verification,
restore, and Redis rebuild behavior.
