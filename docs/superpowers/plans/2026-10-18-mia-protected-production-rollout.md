# MÍA Protected Production Rollout Plan

> **For agentic workers:** Execute this plan through reviewed pull requests and protected GitHub Actions. Never bypass approvals or directly mutate production hosts.

**Goal:** Publish the coordinated Ford Service, Ford-scraping, Ford-web, and OCI action changes, then deploy and verify the approved production release through protected Actions and the supported mise operator flows.

**Architecture:** Ford Service owns Spring AI catalog tools and a separate read-only catalog datasource. Ford-scraping remains the sole catalog writer, migration owner, and scheduler publisher; its protected scheduler release replaces the retired Node MCP runtime only after backup and rollback checks. Ford-web ships the operator UI through its protected Pages workflow. All production deployment changes flow through reviewed `main` commits and the release coordinator.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, TypeScript 5.9, PostgreSQL, Ansible, mise, GitHub Actions, OCI Vault/Terraform, GHCR.

## Global Constraints

- Keep `FORD_CATALOG_TOOLS_ENABLED=false` until datasource, exact Vault secret grant, authorization, evidence, and rollout checks pass.
- Never expose secret values or private key material in shell output, logs, repository files, or this plan.
- Never retire production MCP/tunnel resources before replacement service and scraper scheduler pass protected smoke and rollback checks.
- Preserve Ford-scraping crawler, catalog schema/migrations, writer, and scheduler ownership.
- Use reviewed PRs, immutable digests/provenance, protected environment approvals, and exact rollback images; no direct push to protected `main`.
- Local host SSH probes are diagnostics only; production inspection and mutation use protected Actions.

---

### Task 1: Reconcile release candidates with current remotes

**Files:**
- Inspect: `/private/tmp/ford-service-emme-pages-cutover`
- Inspect: `/private/tmp/ford-mia-phase1-mcp`
- Inspect: `/private/tmp/ford-mia-phase1-web`
- Inspect: `/private/tmp/ford-oci-mia-catalog`

- [ ] Fetch all protected `main` refs; review commit ranges, uncommitted files, merge conflicts, required workflows, and immutable action refs. Preserve unrelated dirty files.
- [ ] Run repository-specific fast checks: Service `./gradlew test bootJar integrationTest`; scraper `npm run lint && npm run test:unit && npm run build` plus Python/workflow contracts; Web `bun run lint && bun run build && bun run pages:check`; OCI action contracts and shell checks.
- [ ] Fix only confirmed release-blocking defects with focused regression tests; rerun the relevant checks.

### Task 2: Publish action and source changes through reviewed PRs

**Files:**
- Publish: `Ford-oci-deployment-action` branch `fix/mia-catalog-runtime-wiring`
- Publish: `Ford-uruapan` branch `feat/mia-phase-one-release-readiness`
- Publish: `Ford-scraping` branch `feat/mia-phase-one-mcp`
- Publish: `Ford-web` branch `feat/mia-phase-one-web`

- [ ] Push each reviewed feature branch and open linked PRs; do not overwrite or force-push protected branches.
- [ ] Verify CI, integration, migration, image scan, provenance, and PR review gates on each PR; fix failures before merge.
- [ ] Merge in dependency order: OCI action pin availability, Service/Scraping/Web source, then verify all protected `main` refs and workflow dispatch contracts.
- [ ] Confirm versioned image tags resolve to reviewed `main` SHAs and the coordinator read-only preflight accepts selected versions.

### Task 3: Verify runtime configuration and guarded Vault policy

**Files:**
- Inspect: `deployment/terraform/runtime-vault-identity.tf`
- Inspect: `deployment/terraform` runtime secret policy contract and plan guards
- Inspect: Service release workflow and app environment template
- Inspect: protected GitHub environments without retrieving secret values

- [ ] Confirm environment reviewers, required variables/secrets, SSH host keys, registry access, deployment enablement, and Vault readiness. Do not print secret values.
- [ ] Verify exact catalog reader secret name/OCID and read-only `ford_read` SELECT grant; leave catalog callbacks disabled if name, scope, or access is unverified.
- [ ] Run the protected Terraform plan and inspect its artifact for only approved least-privilege Vault policy edits; stop for unexpected create/destroy/replacement or widened grants.
- [ ] Defer applying MCP-only grant revocation until Task 5 confirms old consumers are stopped. Keep the shared `ford-prod-postgres-password` grant for the scheduler and preserve grants needed for old-process rollback until retirement is complete.

### Task 4: Protected preflight and coordinated production release

**Files:**
- Run: protected deployment connectivity preflight
- Run: release-all coordinator from protected `main`
- Run: backup-gated catalog sync and scraper scheduler release

- [ ] Run the read-only preflight from `main`; record both host health, current scheduler, backup age/manifest, and rollback image evidence.
- [ ] Ensure exactly one scheduler owner and no overlapping manual/monthly sync; retain the old scheduler until replacement image migration and health checks pass.
- [ ] Dispatch the coordinator for the reviewed Service, scraper, and Web versions; complete required environment approvals and retain correlation/run IDs, source SHAs, digests, and deployment results.
- [ ] Stop and roll back through the protected workflow if any health, attestation, backup, scheduler, datasource, or Pages smoke gate fails.

### Task 5: Verify production and retire legacy footprint safely

**Files:**
- Update: `tasks/todo.md` in Ford-scraping with exact evidence and remaining blockers
- Update: Service release-readiness ledger with exact run IDs and host evidence

- [ ] Verify Service health, database/Redis connectivity, Vault resolution, catalog read-only permissions, source-grounded synthetic conversation, and Web Pages/API/assets.
- [ ] Verify one intended monthly scheduler, successful backup-gated catalog sync, and rollback readiness on the data host.
- [ ] Only after all replacement checks pass, stop/remove old MCP containers/routes/tunnel/LaunchAgents via reviewed protected procedures; rerun health/scheduler checks while preserving the data scheduler's PostgreSQL grant.
- [ ] After MCP consumers are confirmed stopped, apply the reviewed MCP-only Vault grant revocation through protected approval and verify remaining app/data instance-principal reads and denied writes.
- [ ] Record deployment and cleanup run IDs, digests, backup identity, both-host evidence, rollback image, and any remaining user-provided operational inputs.

## Acceptance Criteria

- All PRs merged by review into protected `main`; coordinated CI and provenance checks pass.
- Catalog callbacks remain disabled unless exact least-privilege datasource access and customer-facing evidence checks are proven.
- Protected coordinator deploy succeeds for Service, scraper scheduler, and Web; both hosts and public smoke checks pass.
- Exactly one approved scheduler is active; backup-before-sync and rollback are verified.
- Legacy MCP resources are removed only after replacement is healthy and rollback evidence exists.
- No secret values or credentials appear in repository history, logs, or artifacts.

## Operational Stop Conditions

- GitHub or OCI API unavailable, missing protected approvals, missing secret/host configuration, failed attestation, uncertain backup, unknown Vault secret name, denied read-only query, failed health/smoke, or ambiguous scheduler ownership: stop before the corresponding mutation and preserve evidence.
- Rollback must use the protected workflow and previously recorded immutable digest; no local SSH or ad hoc command may replace that process.
