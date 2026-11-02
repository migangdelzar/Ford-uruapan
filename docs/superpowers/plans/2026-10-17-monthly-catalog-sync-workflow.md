# Monthly Catalog Sync Workflow Implementation Plan

> **For agentic workers:** Execute this plan task by task with `superpowers:executing-plans`; preserve all unrelated work in both repositories.

**Goal:** Replace the always-running Node cron trigger with a protected monthly GitHub Actions workflow that runs the existing full Ford catalog sync once on emme-2 after a verified same-day backup.

**Architecture:** Ford-scraping keeps ownership of crawling and catalog writes. GitHub Actions owns the monthly/manual trigger and invokes a narrow Ansible playbook over pinned SSH; Ansible checks the backup manifest and artifacts, then runs the already-promoted immutable image as a one-shot container on the existing private Docker network. The hosted runner never receives PostgreSQL credentials or connects to PostgreSQL. The old scheduler is disabled only after the manual workflow path is proven, with its immutable image/trigger retained for rollback.

**Tech Stack:** Node.js/TypeScript, Vitest, Docker Compose, Ansible, GitHub Actions YAML, Python contract tests already used by Ford Service.

## Global Constraints

- Schedule: `17 18 1 * *` UTC (12:17 p.m. America/Mexico_City on the first day); also allow protected manual dispatch.
- Sync command: `node dist/src/cli.js sync`; full sync targets `/Todos/`, `/Seminuevos/`, `/Promociones/`, `/Financiamiento/`; unchanged pages remain skipped for ingestion.
- A nonzero `pagesFailed` or `embeddingFailures` result fails the command/workflow.
- Require a successful backup manifest created on the same UTC date and validate required PostgreSQL/Redis artifacts plus their SHA-256 digests before any sync write.
- Read the promoted immutable image digest from host release state. Do not build, publish, migrate, or change image promotion during a scheduled sync.
- Use host-managed DB/embedding configuration and the existing private data Docker network; keep all DB credentials off the GitHub runner and out of logs/summary.
- Serialize workflow runs and preserve the existing PostgreSQL advisory sync lock.
- Gate schedule-triggered execution behind `FORD_CATALOG_SYNC_ENABLED == 'true'`; manual dispatch is available for pre-cutover validation.
- Preserve unrelated dirty files. Do not manually alter production containers or GitHub secrets/settings; use the protected workflows for those actions.

## File Map

### Ford-scraping Node worktree (`/private/tmp/ford-mia-phase1-mcp`)
- `src/cli.ts`: serialize sync counters to a machine-readable stdout result and set nonzero status on partial failures.
- `tests/unit/cli-sync.test.ts` (or current CLI test convention): CLI output/exit contract.
- `tests/unit/site-synchronization.test.ts`: assert full targets/work and unchanged-page ingestion semantics if current coverage is incomplete.
- `scripts/container-contract.test.mjs`: image must support explicit one-shot `sync` command while retaining current default until cutover.
- `tasks/todo.md`: task status/results; `tasks/lessons.md`: add only if meaningful failure or user correction occurs.

### Ford Service/deployment worktree (`/private/tmp/ford-mia-phase1-design`)
- `deployment/ansible/catalog-sync.yml`: execute host backup gate and one-shot sync role.
- `deployment/ansible/roles/catalog_sync/tasks/main.yml`: validate release state and backup artifacts, run one-shot container, return sanitized counters.
- `deployment/ansible/roles/catalog_sync/templates/compose.yml.j2`: immutable image, one-shot command, host config, `data_default` network.
- `scripts/tests/test_catalog_sync_contract.py`: Ansible/YAML contracts following current Python contract-test conventions.
- `.github/workflows/monthly-catalog-sync.yml`: schedule/manual trigger, protected branch/environment, least permissions, SSH invocation, summary and cleanup.
- `.github/workflows/ci.yml` or existing operations contract runner: include workflow/Ansible contracts.
- `deployment/ansible/roles/mcp/templates/blue-green-compose.yml.j2` and its task role: remove scheduler service/image lifecycle only in the cutover phase; retain the standalone prior-image and rollback record until the new workflow is verified.
- `tasks/todo.md`: if present, maintain implementation status/results in the Node task tracker at the workspace root; avoid duplicating project history.

## Task 1: Make partial sync outcomes fail at the CLI boundary

**Files:** `src/cli.ts`, CLI tests, sync tests.

- [x] Add a focused regression proving the CLI returns nonzero when `pagesFailed > 0` or `embeddingFailures > 0`, while emitting a bounded JSON summary containing the counters.
- [x] Run the focused test and confirm the existing behavior fails the assertion.
- [x] Implement the smallest change in the `sync` command: write the machine-readable summary to stdout, close the application, then set failure for either partial-failure counter.
- [x] Verify success exits zero, partial errors exit nonzero, and sync errors remain nonzero without credentials in output.
- [x] Verify existing SiteSynchronizationService still visits all four roots, refreshes used inventory and financing, and runs embeddings; unchanged fetched pages remain skipped for page ingestion.

## Task 2: Add an Ansible one-shot runner and backup gate

**Files:** new playbook/role/template and Python contract tests in Ford Service.

- [x] Write contract tests first for backup state/artifacts/checksums, private network, one-shot command, no migration, sanitized counters, and failure propagation.
- [x] Run contracts to confirm the new role/playbook is absent or violates the required contract.
- [x] Implement host-side backup validation and `docker compose run --rm` for `node dist/src/cli.js sync` on `data_default`, using the active immutable image.
- [x] Use `no_log` for secret-bearing operations, parse only approved counters, and fail on command or partial-sync errors.
- [x] Verify syntax, templates, role contracts, and no migration/image pull/build in the sync path.

## Task 3: Add the protected monthly GitHub workflow

**Files:** `.github/workflows/monthly-catalog-sync.yml`, CI/contract runner.

- [x] Add workflow contract tests for cron/manual trigger, `main`, protected environment, least permissions, concurrency, SSH-only secrets, no DB secrets, sanitized summary, and cleanup.
- [x] Run tests to verify failure against the missing workflow.
- [x] Implement monthly/manual workflow with pinned SSH material, Ansible invocation, bounded summary, and unconditional cleanup.
- [x] Require `deployment-preflight` and exact `refs/heads/main`; no PostgreSQL connection or credentials are configured on runner.
- [x] Wire contracts into the existing contract test runner and verify YAML/contract checks.

## Task 4: Stage and perform trigger cutover safely

**Files:** MCP Compose and scheduler release/deploy paths, documentation, workflow.

- [x] Add regression contracts proving ordinary MCP blue-green rollout no longer introduces/restarts the long-running cron service, while preserving the prior immutable scheduler digest and a protected rollback operation.
- [ ] Ensure the monthly workflow can be invoked manually against the currently promoted digest before retiring the old trigger; do not enable overlapping schedules.
- [ ] Run the protected manual workflow and verify same-day backup, successful one-shot container, expected summary counters, and no leaked config in Actions logs.
- [ ] Only after that run succeeds, use the protected scheduler release/deactivation path to disable the long-running Node scheduler and preserve its prior digest/rollback mechanism. Then confirm the next monthly schedule remains enabled.
- [ ] Set the production `FORD_CATALOG_SYNC_ENABLED` Actions variable to `true` only after the old trigger has been retired; keep it unset/false before cutover.
- [ ] Verify no active duplicate trigger exists and rollback restores the old scheduler trigger/image without deleting catalog data or migrations.

## Task 5: Verification and production readiness

- [x] Run Node CLI/service tests, full Node unit tests, build, container contracts, Service Python operations/workflow contracts, Ansible syntax/template checks, and scoped diff review.
- [x] Exercise backup rejection and partial sync in disposable infrastructure; no production data was used for failure-path tests.
- [ ] Confirm exact production image digest is pullable and equals host release state; confirm the backup manifest/artifact/hash contract on emme-2 using read-only inspection.
- [ ] Confirm Java catalog parity/release gates are complete before retiring Node MCP runtime; this sync workflow itself does not close parity.
- [ ] Use only protected GitHub release/deployment workflows for production changes. Confirm production state from workflow run and read-only host/runtime checks.
- [ ] Update this plan and `tasks/todo.md` with actual evidence; do not mark any production/cutover item complete without evidence.

## Dependencies and Rollback

- Task 1 precedes Task 2 because Ansible relies on the CLI exit/JSON contract.
- Tasks 2 and 3 can be implemented sequentially in the same deployment checkout; Task 4 depends on both and on immutable host release state.
- Production cutover requires a successful manual run and same-day backup verification; if it fails, keep the current scheduler active and fix the failed contract.
- Rollback restores the previously recorded immutable scheduler image/trigger via protected Ansible release workflow. Do not reverse schema migrations or remove catalog data.

## Execution Results (2026-10-17)

- Tasks 1–3: complete locally. Fresh checks: Node unit 142/142, lint, TypeScript build, container contracts 5/5; Service Python contracts 98/98, workflow contracts 36/36, and Ansible syntax parse passed.
- Task 4 implementation: complete locally. Shared OCI scheduler-disable/rollback commits are `323db0f` and `c6dd161`; these are local only. Production manual-run, disable, and enable-variable steps remain blocked until the action and coordinated workflow changes can be published and approved.
- Task 5 local checks: complete. Disposable PostgreSQL + Ansible verified successful sync, propagation of partial page failure, and backup checksum rejection before Docker execution. Fresh Java `test bootJar compileIntegrationTestJava --rerun-tasks` passed; shared action tests passed 23/23.
- Production preflight and protected release: not run. `gh auth status` reports the configured GitHub token is invalid; no production host/config/container was modified. Do not retire the old scheduler until the protected manual sync succeeds.
- Java catalog parity remains a separate open release gate and is not completed by this sync workflow.

## Definition of Done

- [ ] All code/workflow/Ansible contracts pass, including negative backup and partial-sync cases.
- [ ] Node unit suite/build and deployment checks pass.
- [ ] Protected manual production run succeeds with bounded summary and verified same-day backup.
- [ ] Old scheduler is retired only after that run, rollback is intact, and there is one active monthly trigger.
- [ ] Production scheduled workflow is enabled and visible in Actions; Java parity/runtime-retirement gates are tracked separately and remain open until independently verified.
