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

- [x] Add failing deployment-configuration tests for blue/green selection, distinct candidate ports, and retained image references; confirm the tests fail before implementation.
- [x] Run `python -m unittest scripts.tests.test_blue_green_deploy -v` from `ford-ai-service`; new deployment assertions failed before implementation and pass now.
- [x] Add Compose/Ansible paths that start and health-check candidates before cutover, switch the stable local proxy, and retain the prior slot.
- [x] Add post-switch checks and automatic route restoration with prior-service verification.
- [x] Add operator rollback limited to the previously recorded immutable digest and the same health checks.
- [x] Pull with temporary GHCR Docker configuration when a caller token is supplied; log out and delete the temporary credential directory.
- [x] Keep credential-bearing tasks and MCP protocol smoke output redacted.
- [x] Run deployment contract and inventory-secret tests, Compose validation, Nginx `-t`, and Ansible syntax checks for both blue-green playbooks and `site.yml`; all pass.

**Acceptance:** Candidate failure before switch leaves live traffic untouched. Failed health after switch automatically returns traffic to the prior healthy slot. Manual rollback selects the prior immutable image and does not touch database schema.

## Task 1A: Clear CI and repository prerequisites

Complete these gates before publishing or enabling release workflows. They are separated from production runtime readiness so CI can be made reliable without touching production.

- [x] Diagnose Ford Service's latest filesystem Trivy failure: SARIF output included a LOW `DS-0026` note despite the HIGH/CRITICAL threshold, causing the action's exit code 1. Set `limit-severities-for-sarif: true` and add a workflow contract test; local test passes. The threshold and exit code remain HIGH/CRITICAL and blocking.
- [ ] Re-run Ford Service CI through the Trivy job and record the successful run and source SHA in the readiness log.
- [ ] Establish Ford-scraping `main` through a reviewed, non-destructive branch/default-branch transition. Preserve `chore/platform-secret-management` and its history; first verify CI on the intended `main` commit, then change the remote default branch and branch protections.
- [ ] Reduce the Ford-scraping lint and PostgreSQL integration backlogs in small, measured batches. Record baseline and remaining counts; make lint blocking when clean and require all integration tests to pass before image publishing.
- [ ] Resolve the shared-action hosting boundary: public Ford-scraping cannot consume the private Ford Service action. Review and approve a public home for the action, publish the reviewed action there, pin callers to a full commit SHA, and run action contract checks from each caller. Do not place credentials in the public repository.
- [ ] Add a release workflow to Ford-scraping after `main` and shared-action publication are verified; publish immutable multi-platform images only from successful `main` verification.

**Acceptance:** The intended default branch is `main`, required checks are green, the scanner remains an enforced gate, and the public caller resolves the shared action at a reviewed full SHA. No production job is enabled by this task.

## Task 3: Prove Ford Service is safe with two app versions

**Files:**
- Review/modify only as required: the five scheduled job classes listed in Task 1
- Review: existing outbox, sync-run, event-publication, and graph-checkpoint persistence adapters
- Create or modify: focused tests beside each affected class under `ford-ai-service/src/test/java/com/ford/service/`
- Modify: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml` only if a backward-compatible lock/lease table is required

- [x] Write PostgreSQL integration tests that run two app-version instances against the same persistence boundary and prove one sync, outbox delivery, lease-recovery action, or publication resubmission executes.
- [x] Run focused Gradle unit and integration tests for the coordination paths.
- [x] Reuse outbox record claims and cleanup idempotency; add session-scoped PostgreSQL advisory locks for sync, outbox delivery plus recovery, and publication recovery. No schema migration is required.
- [ ] Verify candidate Spring startup does not run an incompatible destructive schema change. Split destructive contract changes into a later release after the old version is retired.
- [x] Run `./gradlew test --no-daemon --no-configuration-cache` and `./gradlew integrationTest --no-daemon --no-configuration-cache` from `ford-ai-service`; both pass.

**Acceptance:** Old and candidate may overlap during the traffic observation window without duplicate outbox delivery, ingestion, event recovery, or cleanup side effects. Both versions can run against the expanded schema.

## Task 4: Package shared OCI deployment action

**Files:**
- Create: `.github/actions/oci-blue-green/action.yml`
- Create: `.github/actions/oci-blue-green/deploy.sh`
- Modify: `deployment/ansible/roles/app/tasks/main.yml`
- Modify: `deployment/ansible/roles/mcp/tasks/main.yml`
- Modify: `.github/workflows/reusable-deploy.yml` (remove the placeholder contract or convert it to non-production validation only)
- Create: `ford-ai-service/scripts/tests/test_oci_action_contract.py`
- Create: `deployment/ansible/backup-now.yml`

- [x] Define action inputs for target, immutable image, host/user, SSH key and known-hosts, registry token, and existing private IP. Validate target and reject mutable image tags.
- [x] Keep the composite action free of workflow permissions; callers must set only the package-read permission required for the short-lived image pull.
- [x] Invoke the shared Ansible playbook and emit a redacted summary with source SHA, active/prior image digests, slot, migration status, health checks, and rollback behavior.
- [x] Add tests for missing digests, unknown targets, mutable tags, MCP private-IP validation, rollback resolution, and secret leakage.
- [x] Run the OCI action contract tests, deployment tests, workflow contract tests, YAML parsing, and Ansible syntax checks; all pass.
- [x] The composite action has no third-party GitHub Action dependencies. Its Ansible runtime package is pinned.

**Acceptance:** Target repos can invoke the shared action inside their own `environment: production` job; cross-repo SHA pinning remains gated on making the private action available from the intended Ford Service main branch. No approval or environment secret moves to Ford Service.

## Task 5: Add Ford Service release and rollback jobs

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`
- Modify: `.github/workflows/reusable-container.yml` only if image output/digest must be exposed to the release job
- Test: `.github/workflows/release.yml` with actionlint
- Test: `.github/workflows/ci.yml` with actionlint

- [x] Keep PR runs verification-only and publish the ARM64 image only from successful `main` verification; expose the image digest and resolve it from the verified source SHA for release.
- [x] Add a caller-owned `production` job that invokes the shared local OCI action. The action is not yet available to external repositories by reviewed SHA.
- [x] Add manual dispatch for a full source SHA and operation (`deploy` or `rollback`). Deploy requires successful CI on `main`; rollback resolves only the recorded prior artifact.
- [x] Run and verify the existing on-VM database backup before app/MCP deploys that may change schema; gate on runtime Vault readiness.
- [x] Require both `PRODUCTION_DEPLOY_ENABLED == 'true'` and `RUNTIME_VAULT_READY == 'true'`; the environment is not created by a skipped job.
- [ ] Run `actionlint .github/workflows/ci.yml .github/workflows/release.yml` and `./gradlew check --no-daemon --no-configuration-cache`; actionlint is unavailable locally, and the complete `check` task remains to be run.

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
- [x] Set the local scheduler default and Compose schedule to `0 12 1 * *` in `America/Mexico_City`; migration is no longer run during scheduler container startup. The monthly contract test, unit suite, and TypeScript build pass.
- [ ] On verified `main`, publish the ARM64 image by digest; keep PR jobs unable to publish or deploy.
- [ ] Add a Ford-scraping-owned production deployment job that invokes the remote Ford Service OCI action pinned to its reviewed full commit SHA for `ford-mcp`.
- [ ] Require `vars.PRODUCTION_DEPLOY_ENABLED == 'true'` before entering the production deployment job.
- [x] Remove migration from the local normal scheduler startup path. The shared Ford Service blue-green deploy runs `node dist/src/cli.js migrate` once as a one-shot before an MCP/scheduler release; production execution remains gated.
- [ ] Start candidate MCP on an alternate local port, check its MCP health endpoint, switch the stable Tunnel origin/proxy, and test a read-only catalog question with source URL evidence. Switch back automatically if post-switch smoke fails.
- [ ] Replace the production monthly scheduler serially with one replica, `SYNC_SCHEDULE=0 12 1 * *`, `SYNC_TIMEZONE=America/Mexico_City`; if it fails, restore the previous image without running a second scheduler concurrently.
- [ ] Clear the known lint baseline and embedding integration failures, then run `npm run lint && npm run build && npm run test:unit && npm run test:integration` from `../Ford-scraping`; expect all commands to pass.

**Acceptance:** MCP startup does not migrate, MCP traffic can switch back to the prior digest, only one monthly scheduler runs, and the live read-only MCP check returns supported facts with source links.

**Current external blockers:** This task depends on Task 1A for the Ford-scraping `main` branch, clean blocking CI, and a public shared-action home. Keep its release job absent or disabled until those are complete.

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

**Configuration prerequisite:** Ford Service currently reports `Actions private-repository access: none`; a public caller cannot consume a private action regardless. The coordinator also needs a narrowly scoped GitHub App installation token and workflow access in each target repository. Do not store this token in the public shared-action repository.

## Task 8A: Configure GitHub repository controls

- [ ] Confirm `main` exists and is protected in each repository before release workflows rely on it.
- [ ] Create a `production` environment in each target repo with required reviewers, allowed deployment branches, and only that repo's deployment credentials. Do not create or populate production credentials until runtime secret retrieval and host prerequisites pass.
- [ ] Give the coordinator's GitHub App access only to the three target repositories and only the permissions needed to read workflow runs and dispatch releases; verify token expiry and repository scope in a non-production dispatch test.
- [ ] Record environment IDs/settings and a permission check result in the readiness document without recording secret values.

**Acceptance:** A test dispatch can identify the exact successful `main` SHA and reach each target workflow, while production remains blocked behind its own reviewer gate.

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

Execution order for the first release: Ford-web preview and production-owner alignment; Ford Service app; Ford-scraping MCP; Ford-scraping scheduler; then release-all. For each step, verify health and observe before proceeding. A failed gate stops the sequence; rollback uses the retained artifact and never reverses migrations automatically.

**Acceptance:** All production gates have evidence; deploy, automatic switch-back, operator rollback, monthly scheduler, release-all partial failure, and read-only MCP smoke have been exercised without exposing runtime secrets.

## Dependencies

```text
Task 1 readiness
  ├─ Task 2 OCI blue-green primitives ─ Task 3 Ford Service dual-worker safety ─ Task 4 shared action
  │                                                                        ├─ Task 5 Ford Service release
  │                                                                        └─ Task 1A prerequisites ─ Task 6 Ford-scraping release
  ├─ Task 7 Ford-web release
  └─ Tasks 5, 6, 7 ─ Task 8 release-all ─ Task 8A GitHub controls ─ Task 9 production enablement
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
