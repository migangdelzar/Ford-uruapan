# GitHub Release Pipelines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task by task. Keep each repository’s unrelated changes untouched.

**Goal:** Add verified, approval-gated releases for Ford Service, Ford-scraping, and Ford-web, with blue-green rollback for OCI services and a controlled release-all coordinator.

**Architecture:** Each repository owns its verification and production deployment jobs. The production job owns that repository’s protected `production` environment and invokes a version-pinned shared OCI composite action from Ford Service. OCI app and MCP candidates are checked beside the live version, traffic switches only after checks pass, and failed post-switch checks return traffic to the retained prior version. Ford-web deploys and can roll back immutable Pages deployments. Release-all dispatches the selected repos and waits for their independent approvals and results.

**Tech Stack:** GitHub Actions; GitHub Environments; GitHub App installation token; OCI Compute; Docker Compose; Ansible; GHCR; Spring Boot/Gradle; Node 22/npm; Bun; Cloudflare Pages/Wrangler.

## Global Constraints

- A successful `main` verification can publish an immutable artifact; production deployment waits for approval in that target repository’s `production` environment.
- Each target repository keeps its own approval gate, deployment record, and environment-scoped deployment credentials.
- Ford Service hosts shared OCI deployment logic as a composite action; callers pin it to a reviewed immutable commit SHA.
- Do not expose runtime DB, model-provider, Meta, or observability secrets to GitHub Actions.
- Keep production deploy jobs disabled until every production runtime consumer uses its intended OCI Vault/runtime retrieval path.
- OCI blue-green uses one candidate container on the existing VM; do not create a second VM. Enable it only after measured CPU/memory headroom, ingress routing, and dual-worker behavior pass checks.
- Never run automatic down migrations or database restore during an app rollback.
- Keep the catalog scheduler at one replica; replace it serially and retain its prior image for recovery.
- Run catalog migrations once before MCP/scheduler replacement; ordinary MCP container startup does not migrate.
- Gate each deploy job behind a repository variable `PRODUCTION_DEPLOY_ENABLED` that is absent or `false` by default. Set it to `true` only after that repo’s `production` environment exists with required reviewers and all readiness gates pass.
- Preserve current staged, unstaged, and untracked user changes in all three repositories.

---

## Repository Boundaries

| Repository | Owns |
| --- | --- |
| `Ford-uruapan` | Shared OCI Ansible deployment and blue-green switch, shared composite action, Ford Service release, release-all coordinator, operations docs. |
| `Ford-scraping` | Node verification, immutable MCP/scheduler image release caller, repo-specific smoke tests. |
| `Ford-web` | Bun verification, immutable Pages deploy and rollback caller. |

The shared action must run inside the target repository’s environment-protected job. Do not move the approval to a reusable workflow in Ford Service: GitHub’s reusable-workflow contract cannot receive the caller’s `environment`; environment secrets declared in the called workflow come from the called workflow repository. See [GitHub reusable workflow environment and secret behavior](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows).

## Task 1: Verify production rollout prerequisites

**Files:**
- Create: `docs/operations/production-rollout-readiness.md`
- Review: `deployment/terraform/compute.tf`
- Review: `deployment/ansible/roles/app/templates/compose.yml.j2`
- Review: `deployment/ansible/roles/mcp/templates/compose.yml.j2`
- Review: `ford-ai-service/src/main/java/com/ford/service/outbox/OutboxDeliveryJob.java`
- Review: `ford-ai-service/src/main/java/com/ford/service/outbox/OutboxRecoveryJob.java`
- Review: `ford-ai-service/src/main/java/com/ford/service/ingestion/adapter/in/scheduling/FordSyncJob.java`
- Review: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationPublicationRecovery.java`
- Review: `ford-ai-service/src/main/java/com/ford/service/conversations/application/graph/ConversationGraphCheckpointCleanup.java`
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/deployment/BlueGreenBackgroundWorkSafetyIntegrationTest.java`

- [ ] Inspect the actual public Ford Service ingress and MCP Cloudflare Tunnel origin. Record the stable local listener and how its upstream can be switched without changing the public URL.
- [ ] Measure idle and peak CPU/memory on the app VM and MCP VM. Record candidate headroom while Ollama and current services run. Keep the configured two VM / 2 OCPU / 12 GB budget unchanged.
- [ ] Audit every scheduled and background job in the listed Ford Service classes. Demonstrate with tests or DB-level coordination that running old and candidate versions together cannot duplicate external sends, sync work, or recovery publications.
- [ ] Add the concurrency cases to `BlueGreenBackgroundWorkSafetyIntegrationTest` using the project’s PostgreSQL Testcontainers setup; run `./gradlew integrationTest --tests '*BlueGreenBackgroundWorkSafetyIntegrationTest' --no-daemon --no-configuration-cache` and require one effective delivery/run for each concurrent pair.
- [ ] Verify GitHub plan/repo visibility supports required reviewers and that the three repositories can access the private Ford Service composite action.
- [ ] Inspect Cloudflare Pages Git integration and identify the single production deployment owner.
- [ ] Verify whether the production Vault migration is complete for Spring, data, MCP, and Collector. If not, leave every production deploy job disabled.
- [ ] Record results and concrete blockers in `docs/operations/production-rollout-readiness.md`; do not include secret values.

**Acceptance:** Every switch, resource, reviewer, private-action access, and runtime-secret prerequisite is recorded as verified or explicitly blocked. No production deployment runs in this task.

## Task 2: Add blue-green OCI service deployment primitives

**Files:**
- Modify: `deployment/ansible/roles/app/tasks/main.yml`
- Modify: `deployment/ansible/roles/app/templates/compose.yml.j2`
- Create: `deployment/ansible/roles/app/templates/proxy.conf.j2` (only if Task 1 finds no existing stable switch point)
- Modify: `deployment/ansible/roles/mcp/tasks/main.yml`
- Modify: `deployment/ansible/roles/mcp/templates/compose.yml.j2`
- Modify: `ford-ai-service/scripts/tests/test_deploy_inventory_secrets.py`
- Create: `ford-ai-service/scripts/tests/test_blue_green_deploy.py`
- Test: `deployment/ansible/site.yml`

- [ ] Add a failing deployment-configuration test for selecting blue or green, assigning a distinct candidate port, and retaining the previous image reference.
- [ ] Run `python -m unittest scripts.tests.test_blue_green_deploy -v` from `ford-ai-service`; confirm the new assertion fails before implementation.
- [ ] Add Compose/Ansible behavior for launching the candidate without stopping the active service, checking candidate health directly, switching the stable ingress, and retaining the prior container for the configured observation window.
- [ ] Add the post-switch check. If it fails, point the stable ingress back to the prior slot, verify prior health, and fail the release with both artifact IDs and switch-back result in output.
- [ ] Add an operator-triggered switch-back path that accepts only the recorded prior immutable image and uses the same health checks.
- [ ] Use the caller’s short-lived `GITHUB_TOKEN` with `packages: read` for the image pull; run `docker logout ghcr.io` after pull and ensure the token is never stored in the VM’s Docker config or deployment summary.
- [ ] Ensure logs and deployment summaries report health and routing status without printing SSH keys, runtime credentials, or customer data.
- [ ] Run `python -m unittest scripts.tests.test_blue_green_deploy scripts.tests.test_deploy_inventory_secrets -v` from `ford-ai-service` and `ansible-playbook -i 'localhost,' deployment/ansible/site.yml --syntax-check` from the repository root; expect all tests and syntax checks to pass.

**Acceptance:** Candidate failure before switch leaves live traffic untouched. Failed health after switch automatically returns traffic to the prior healthy slot. Manual rollback selects the prior immutable image and does not touch database schema.

## Task 3: Prove Ford Service is safe with two app versions

**Files:**
- Review/modify only as required: the five scheduled job classes listed in Task 1
- Review: existing outbox, sync-run, event-publication, and graph-checkpoint persistence adapters
- Create or modify: focused tests beside each affected class under `ford-ai-service/src/test/java/com/ford/service/`
- Modify: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml` only if a backward-compatible lock/lease table is required

- [ ] Write tests that start two job instances against the same persistence boundary and prove one external action/run is created for one due item.
- [ ] Run each focused Gradle test and confirm the concurrency assertion fails if the existing persistence does not serialize the operation.
- [ ] Reuse existing DB claims/leases where they already guarantee single execution. For any failing path, add the smallest database-backed coordination mechanism and a Liquibase change that is safe for both old and candidate app versions.
- [ ] Verify candidate Spring startup does not run an incompatible destructive schema change. Split destructive contract changes into a later release after the old version is retired.
- [ ] Run `./gradlew test --no-daemon --no-configuration-cache` from `ford-ai-service`; expect all unit tests to pass.

**Acceptance:** Old and candidate may overlap during the traffic observation window without duplicate outbox delivery, ingestion, event recovery, or cleanup side effects. Both versions can run against the expanded schema.

## Task 4: Package shared OCI deployment action

**Files:**
- Create: `.github/actions/oci-blue-green/action.yml`
- Create: `.github/actions/oci-blue-green/deploy.sh`
- Modify: `deployment/ansible/roles/app/tasks/main.yml`
- Modify: `deployment/ansible/roles/mcp/tasks/main.yml`
- Modify: `.github/workflows/reusable-deploy.yml` (remove the placeholder contract or convert it to non-production validation only)
- Create: `ford-ai-service/scripts/tests/test_oci_action_contract.py`

- [ ] Define action inputs for service target, immutable image reference, deployment host/user, SSH private key, and known-hosts value. Validate target against `ford-service` and `ford-mcp`; reject mutable image tags.
- [ ] Keep the action free of repository-wide privileges. Its caller sets only `contents: read` and the minimal package read permission required by the selected deploy method.
- [ ] Make the action invoke the shared Ansible role and emit a redacted summary containing source SHA, image digest, migration result, selected slot, health checks, and rollback result.
- [ ] Add tests that reject missing image digests, unknown targets, mutable tags, and credential values in captured output.
- [ ] Run `python -m unittest scripts.tests.test_oci_action_contract -v` and the Ansible syntax command from Task 2; expect all checks to pass.
- [ ] Pin any external GitHub Actions used by the action to reviewed full commit SHAs.

**Acceptance:** Target repos can invoke one version-pinned action inside their own `environment: production` job; no approval or environment secret moves to Ford Service.

## Task 5: Add Ford Service release and rollback jobs

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`
- Modify: `.github/workflows/reusable-container.yml` only if image output/digest must be exposed to the release job
- Test: `.github/workflows/release.yml` with actionlint
- Test: `.github/workflows/ci.yml` with actionlint

- [ ] Keep PR runs verification-only. On successful `main`, publish the ARM64 image by immutable digest and pass that digest to a deploy job in the Ford Service `production` environment.
- [ ] Put `environment: production` on the caller-owned deploy job and invoke the Ford Service OCI action from that job, pinned to the reviewed full commit SHA produced after Task 4 is reviewed.
- [ ] Add manual `workflow_dispatch` inputs for a reviewed source SHA and operation (`deploy` or `rollback`). Rollback resolves only the previous artifact recorded by a successful deployment; do not accept arbitrary tags.
- [ ] Add required checks for backup success before schema changes and fail closed when runtime Vault readiness is false.
- [ ] Require `vars.PRODUCTION_DEPLOY_ENABLED == 'true'` on the production deploy job so a missing environment cannot be auto-created without reviewer protection and immediately deployed.
- [ ] Run `actionlint .github/workflows/ci.yml .github/workflows/release.yml` and `./gradlew check --no-daemon --no-configuration-cache` from `ford-ai-service`; expect valid workflows and passing checks.

**Acceptance:** PRs cannot deploy; main deployment pauses at Ford Service’s own production approval; artifact digest and rollback target appear in deployment summary.

## Task 6: Add Ford-scraping CI, MCP blue-green deploy, and single scheduler release

**Files:**
- Create: `../Ford-scraping/.github/workflows/ci.yml`
- Create: `../Ford-scraping/.github/workflows/release.yml`
- Modify: `../Ford-scraping/Dockerfile`
- Modify: `../Ford-scraping/docker-compose.yml` only to keep local behavior aligned with the separate one-shot migration and scheduler commands
- Modify: `deployment/ansible/roles/mcp/templates/compose.yml.j2`
- Modify: `deployment/ansible/roles/mcp/tasks/main.yml`
- Create: `../Ford-scraping/tests/unit/deployment-contract.test.ts`

- [x] Add PR checks using Node 22: `npm ci`, baseline-visible `npm run lint`, `npm run build`, `npm run test:unit`, and `npm run test:integration` with pgvector and migrations against the test DB. Lint currently reports 773 existing issues, so it is non-blocking until debt is reduced; build/unit/integration remain blocking. Local integration currently reports 3 existing failures in embedding pending/search setup (2 in `database.test.ts`, 1 suite setup in `search-repository.test.ts`).
- [ ] On verified `main`, publish the ARM64 image by digest; keep PR jobs unable to publish or deploy.
- [ ] Add a Ford-scraping-owned production deployment job that invokes the remote Ford Service OCI action pinned to its reviewed full commit SHA for `ford-mcp`.
- [ ] Require `vars.PRODUCTION_DEPLOY_ENABLED == 'true'` before entering the production deployment job.
- [ ] Run `node dist/src/cli.js migrate` exactly once as a one-shot task from the selected image before changing MCP or scheduler containers. Remove migration from every normal container startup path.
- [ ] Start candidate MCP on an alternate local port, check its MCP health endpoint, switch the stable Tunnel origin/proxy, and test a read-only catalog question with source URL evidence. Switch back automatically if post-switch smoke fails.
- [ ] Replace the monthly scheduler serially with one replica, `SYNC_SCHEDULE=0 12 1 * *`, `SYNC_TIMEZONE=America/Mexico_City`; if it fails, restore the previous image without running a second scheduler concurrently.
- [ ] Clear the known lint baseline and embedding integration failures, then run `npm run lint && npm run build && npm run test:unit && npm run test:integration` from `../Ford-scraping`; expect all commands to pass.

**Acceptance:** MCP startup does not migrate, MCP traffic can switch back to the prior digest, only one monthly scheduler runs, and the live read-only MCP check returns supported facts with source links.

## Task 7: Add Ford-web Pages release and rollback

**Files:**
- Create: `../Ford-web/.github/workflows/ci.yml`
- Create: `../Ford-web/.github/workflows/release.yml`
- Modify: `../Ford-web/wrangler.jsonc` only if project/build configuration requires it
- Create: `../Ford-web/tests/unit/releaseWorkflow.test.ts`
- Create: `../Ford-web/openapi.ford-service.yaml` (versioned service-contract snapshot for self-contained CI)

- [x] Add PR verification with Bun frozen-lock install, lint, unit tests, production build, API contract check against the checked-in Ford Service contract snapshot, and Pages Functions build check.
- [x] Deploy only verified `main` source with Wrangler from a caller-owned `production` environment job. Deployment remains disabled until the production variable is explicitly enabled.
- [x] Require main ref and `vars.PRODUCTION_DEPLOY_ENABLED == 'true'` before entering the production deployment job.
- [x] Store deployment commit, Pages deployment ID, URL, and details in the workflow summary. Rollback rebuilds a full SHA that has successful CI and a successful prior production Pages deployment; Pages retains the prior immutable deployment.
- [x] Add manual rollback by full source SHA; require successful CI and a successful prior production Pages deployment, pass the `production` approval gate, then smoke-check the public UI and `/api/v1/health`. Wrangler authentication and actual Pages access remain readiness gates.
- [ ] Ensure Cloudflare Git integration is disabled if Wrangler Actions owns production deploys; fail readiness if ownership is ambiguous.
- [x] Run `bun install --frozen-lockfile && bun run lint && bun run test && bun run build && bun run api:check -- openapi.ford-service.yaml && bun run pages:check` from `../Ford-web`; all commands pass locally (31 test files, 122 tests).

**Acceptance:** Each Pages deploy waits for Ford-web’s production approval, reports an exact commit/deployment ID, verifies the public UI/API, and exposes a gated rollback to a previous deployment.

## Task 8: Add release-all coordinator

**Files:**
- Create: `.github/workflows/release-all.yml`
- Create: `ford-ai-service/scripts/tests/test_release_all_selection.py`
- Create: `ford-ai-service/scripts/tests/test_release_all_summary.py`
- Configure outside source: narrowly scoped GitHub App installation token and per-repo workflow access

- [ ] Define `workflow_dispatch` booleans for Ford Service, Ford-scraping, and Ford-web. Reject a request with no repo selected.
- [ ] Resolve and record each selected repo’s latest successful `main` verification SHA; never dispatch a mutable branch or tag as the artifact identity.
- [ ] Use a GitHub App installation token restricted to the three repos and only Actions/Contents permissions required to read release runs and dispatch target workflows.
- [ ] Dispatch each target’s release workflow with its exact source SHA; wait for its run and report approval-pending, success, failure, or cancellation separately.
- [ ] Do not auto-approve deployments and do not roll back successful repos when another repo fails. Link every target run and summary.
- [ ] Test selection validation, dispatch SHA pinning, and partial-failure reporting with mocked GitHub API responses.
- [ ] Run `python -m unittest scripts.tests.test_release_all_selection scripts.tests.test_release_all_summary -v` and `actionlint .github/workflows/release-all.yml`; expect all checks to pass.

**Acceptance:** Release-all selects exact versions, dispatches only checked repos, preserves each target’s separate approval, waits for results, and reports partial failures without undoing successful repos.

## Task 9: Stage production enablement and verify rollback behavior

**Files:**
- Update: `docs/operations/production-rollout-readiness.md`
- Update: `docs/operations/credential-storage.md`
- Create: `docs/operations/production-release-runbook.md`

- [ ] Complete the Vault/runtime readiness gate and configure only SSH/Cloudflare/GitHub App deployment credentials in protected environments.
- [ ] Configure reviewer and branch restrictions on all three `production` environments. Confirm private reusable action access and least-privilege tokens.
- [ ] Run the first production rollout one repo at a time. Approve each target explicitly; record image/commit, backup, migration, candidate health, cutover, post-cutover smoke, and observation result.
- [ ] Exercise automatic switch-back with a controlled failing post-cutover check in a non-production or explicitly approved maintenance window before relying on it in production.
- [ ] Exercise manual rollback to the retained previous immutable artifact; verify public health and critical read/write behavior. Do not run down migrations.
- [ ] Verify scheduler has one replica, runs at day 1 noon `America/Mexico_City`, reports run outcome, and supports explicit manual sync recovery.
- [ ] Verify the live read-only MCP catalog question returns a source link and no unsupported exact facts.
- [ ] Keep production workflows disabled if any gate fails; document the exact blocker and retain the tested CI workflows.

**Acceptance:** All production gates have evidence; deploy, automatic switch-back, operator rollback, monthly scheduler, release-all partial failure, and read-only MCP smoke have been exercised without exposing runtime secrets.

## Dependencies

```text
Task 1 readiness
  ├─ Task 2 OCI blue-green primitives ─ Task 3 Ford Service dual-worker safety ─ Task 4 shared action
  │                                                                        ├─ Task 5 Ford Service release
  │                                                                        └─ Task 6 Ford-scraping release
  ├─ Task 7 Ford-web release
  └─ Tasks 5, 6, 7 ─ Task 8 release-all ─ Task 9 production enablement
```

## Definition of Done

- All task acceptance checks pass and the worktree still contains the user’s pre-existing changes unchanged.
- PR pipelines verify only; protected production environments gate deployments in each target repo.
- OCI candidate and prior versions can coexist, traffic moves only after candidate health, and failed post-switch health returns traffic to the prior version.
- App rollback preserves database changes; any database restore requires its own explicit, reviewed recovery procedure.
- Ford-scraping migrations run once per release and the monthly scheduler remains one replica.
- Release-all deploys only exact selected versions, preserves per-repo approvals, and reports partial outcomes.
- Runtime secrets remain outside GitHub Actions; deployment workflows stay disabled until Vault readiness passes.
- Changes land as small, path-scoped commits; do not push or deploy without the user’s explicit instruction.
