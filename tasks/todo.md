# GOAL: End-to-end deploy — local→dev (Actions) + local→prod (emme-1/emme-2) (2026-10-17)

## 2026-10-18 MCP retirement consolidation

- [x] Remove the Spring remote MCP client and tool transport; Spring AI uses in-process catalog tools.
- [x] Remove the Service MCP deployment target, Ansible deployment playbooks/role, local `deploy:mcp` command, and MCP runtime credentials from tracked examples.
- [x] Remove MCP/Ollama health checks from the generic production connectivity preflight; it now checks the app, collector, and PostgreSQL.
- [x] Remove MCP runtime Vault grants from the desired Terraform policy and document the protected apply requirement.
- [x] Update current catalog alert name, prompt, response-template guidance, and architecture to describe catalog tools.
- [x] Reconcile current operator docs with MCP source retirement and current release ownership while preserving dated host/GitHub snapshots as history: credential-storage guidance now distinguishes local retirement from pending production cleanup; production-readiness snapshots are explicitly historical; release-all names Ford-scraping as the catalog writer/scheduler target.
- [ ] From GitHub Actions with protected credentials, inspect emme-1/emme-2 and perform the approved Service-owned catalog tool rollout; Node/MCP parity is not a gate.
- [ ] Verify Spring's catalog tools and monthly sync in production; confirm rollback window and backup.
- [ ] Remove deployed MCP containers, Cloudflare route/tunnel, LaunchAgents, Vault secrets and permissions, and obsolete GitHub secrets/variables only after direct production verification.
- [ ] Run Terraform plan/apply for the reviewed least-privilege Vault grant removal; retain plan/apply evidence.

**Boundary:** Local MCP deploy/runtime code removal is complete in this worktree, but live MCP resources have not been touched. GitHub token/API and local SSH access were previously unavailable; production retirement remains blocked until protected Actions/host access is available. Historical `tasks/todo.md` entries below intentionally retain past MCP architecture and test facts.

## Consolidated end-to-end completion requirements (2026-10-18)

This is the authoritative remaining checklist for the MÍA/Spring AI production cutover, including OCI Vault. Do not mark a gate complete based only on local config or a successful workflow dispatch; retain command/workflow evidence.

Checklist notation: `[-]` means retired from scope by an explicit user decision; it is neither complete nor pending.

### OCI runtime Vault plan guard hardening (2026-10-18)

- [x] Add failing tests proving the Terraform plan guard rejects broad, unexpected, missing, and differently attached/renamed Vault policy changes while accepting the exact configured app/data read grants; broadening and metadata tests failed against the old guard and all 11 focused guard tests now pass.
- [x] Put the allowed runtime identities and secret-name lists in one checked-in contract consumed by both Terraform and the plan guard.
- [x] Require the expected Vault and compartment OCIDs in both the protected plan and apply guard invocations.
- [x] Pass the catalog-reader Vault OCID into the app environment/action only when catalog tools are enabled; keep the exact OCI name-to-grant verification as an explicit pre-enable gate.
- [x] Update the active Terraform plan/spec documentation from the retired MCP-only grant to the current named runtime consumers and guarded exact policy.
- [x] Verify guard and release workflow contracts, Terraform formatting, and whitespace without contacting OCI or applying changes.
- [ ] Run `terraform validate` with the workflow's locked OCI provider version; local init is currently blocked because `registry.terraform.io` cannot resolve and no OCI provider plugin is cached.

Results: `node --test scripts/operations-contract.test.mjs scripts/workflow-contract.test.mjs scripts/mise-contract.test.mjs` passed 83/83; Terraform Vault guard tests passed 11/11; release-all Python tests passed 34/34; Terraform 1.16.6 `fmt -check -recursive` and `git diff --check` passed. The contract retains exactly the prior app/data grant list (20 app secrets, 3 data secrets). `terraform init -backend=false -lockfile=readonly` could not reach `registry.terraform.io`; no OCI plan/apply was run.

Acceptance: plan output contains exactly the configured `read secret-bundles` statements for the named app/data groups and target Vault/compartment; no additional or missing policy entries pass. Dynamic-group safety and resource-address restrictions remain intact. Rollback is fail-closed: any mismatch stops before apply, and reverting the reviewed source change restores the prior validator.

### A. Publish and validate the coordinated source

- [ ] Confirm protected `main` rules require reviewed PRs and required CI; no direct push or force-push for release changes.
- [ ] Keep GitHub Actions job permissions minimal, production credentials environment-scoped, and release-coordinator App credentials in GitHub Actions secrets. Local tooling may use the mode-0600 App key file by path; never copy its contents into `.zshrc`, repo files, or logs.
- [ ] From the authenticated workstation, fetch current GitHub refs and reconcile the local Ford Service and Ford-scraping commits with the latest protected `main`; preserve unrelated worktree files and do not force-push.
- [ ] Publish the Ford Service, Ford-scraping, and Ford-web changes through reviewed PRs; confirm the MCP runtime removal is paired with the in-process Spring AI catalog tools and scheduler-only scraper deployment. Ford-web's `feat/mia-phase-one-web` adds the tenant-scoped shadow-draft review panel and OpenAPI contract; include its protected Pages release in the coordinator after merging to protected `main`.
- [ ] Confirm every third-party/shared action uses the reviewed immutable commit SHA; confirm the pinned OCI blue-green action revision is published and reviewed.
- [ ] Pass required CI, migration, release-contract, container-contract, integration, provenance, backup-before-sync, scheduler rollback, and release coordinator checks.
- [ ] Confirm image builds/scans produce signed provenance attestations and deployment verifies the exact digest and source SHA reachable from protected `main`.
- [ ] Confirm all three versioned release tags point to reviewed commits and the coordinator's read-only preflight accepts the exact Service, scraper, and Web versions.
- [ ] Run the protected coordinated release from `main` through `release-all-approval`; retain coordinator correlation ID/run ID, all three child release run IDs, source SHAs, image digests/deployment IDs, and approval evidence.

### B. Verify the Service-owned catalog tool configuration

- [ ] Define and validate customer-facing tool inputs/outputs against MÍA use cases in Ford Service; Node MCP behavior is historical context, not a target contract.
- [ ] Verify datasource URL/user/password injection, read-only SELECT grants on `ford_read`, exact source evidence, and clear failure behavior when catalog access is unavailable.
- [ ] Verify per-turn evidence-ledger grounding, calibrated abstention for missing/ambiguous facts, and exact-version Spring AI 2.0.1 advisor, retry, and conversation-memory behavior.
- [ ] Use the reviewed Spanish retrieval corpus when supplied to measure retrieval, fact coverage, latency, and abstention quality; this evaluation does not compare against Node/MCP output.
- [ ] Keep production `FORD_CATALOG_TOOLS_ENABLED=false` until the Service-owned configuration and least-privilege access have been reviewed; local development may opt in against a separately configured read-only datasource. No `FORD_CATALOG_PARITY_READY` setting is required.

Local configuration verification (2026-10-18): `cd ford-ai-service && ./gradlew test --offline --no-daemon --tests 'com.ford.service.catalog.CatalogDataSourceConfigurationTest' --tests 'com.ford.service.ai.CatalogToolArchitectureTest' --tests 'com.ford.service.ai.CatalogToolsTest'` passed 25 tests (3 datasource configuration, 1 architecture, 21 tool), with zero failures/errors/skips. This proves the H2-backed local composition path and missing-credential fail-closed behavior; it does not prove a live catalog connection or production grants.

#### Catalog release-gate scope update (2026-10-18)

- [x] Remove `FORD_CATALOG_PARITY_READY` from the protected Service release environment and deployment condition while retaining the opt-in tool flag, JDBC URL/user validation, Vault secret OCID validation, coordinator approval, attestation, and production readiness gates.
- [x] Update the release/action contract to assert the parity gate is absent and the read-only datasource inputs remain required.
- [x] Update the catalog spec, implementation plan, operations guide, shadow rollout guide, and this ledger to use the Service-owned Spring AI contract and keep legacy MCP parity historical/non-gating.
- [x] Verify the release contract red before the workflow edit and green afterward; `git diff --check` passes.
- [ ] Keep production tool callbacks disabled until Service-owned configuration, least-privilege permissions, evidence grounding, and protected host rollout are verified.

### C. Verify OCI Vault and runtime identity end to end

- [ ] Inspect protected `production` environment configuration without exposing secret values. Confirm `FORD_SECRETS_PROVIDER=oci-vault`, `PRODUCTION_DEPLOY_ENABLED=true` only after readiness review, and `RUNTIME_VAULT_READY=true` only after verifying every runtime consumer on both OCI hosts.
- [ ] Verify the required Vault OCID variables and secret names against `release.yml`, `app.env.j2`, and `runtime-vault-identity.tf`: database, Redis, operator password, JWT signing key, DeepSeek/OpenAI-compatible provider key, Meta app/verify/WhatsApp credentials, and observability credentials. Validate optional provider/Meta integrations only when enabled.
- [ ] Verify emme-1 and emme-2 instance principals can read only their named runtime secrets; confirm app/data dynamic groups match the intended instance OCIDs and no application secret value enters GitHub variables, Terraform state, logs, or committed files.
- [ ] Keep Java catalog database credentials separate: when the Service-owned tool configuration is ready, use the dedicated read-only database user, the `ford_read` views, its Vault secret OCID, and production JDBC URL. Verify SELECT succeeds and writes are denied.
- [ ] Before enabling catalog tools, verify the actual OCI Vault secret name behind `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD` and add that exact name to the emme-1 app grant contract. The current policy contract does not include this catalog-reader secret; the release now passes its OCID only when tools are enabled.
- [ ] Run the protected Terraform runtime-Vault workflow from `main` with `plan`; inspect its artifact and ensure it changes only the reviewed least-privilege policy entries (removing obsolete MCP grants while preserving active app/data consumers). Stop on any group replacement, resource creation/destruction, or broader grant.
- [ ] Apply only that reviewed plan through the protected production approval, then verify policy state and both instance-principal reads. Record plan/apply run IDs and retain evidence.
- [ ] Verify Terraform uses the dedicated OCI API user, private/versioned `ford-terraform-state` bucket, state locking, and exact `ford-runtime-secret-read` scope; import all three existing identity resources before Terraform manages them.
- [ ] Keep Terraform API credentials in the dedicated OCI Actions secret and private state; never put OCI private keys or Vault values in `.zshrc`, command output, repository files, Terraform variables, or state.

### D. Deploy and verify both production hosts

- [ ] Confirm the required production environment settings pass the release workflow: operator username; official Meta Graph messages endpoint and matching phone number ID; Meta delivery and durable outbox enabled; required secret OCIDs; Grafana Cloud and Langfuse endpoints/keys; deployment hosts/users/SSH secrets; and registry access.
- [ ] Confirm environment reviewers/approvals are configured for `release-all-approval`, `production`, `manual-release-approval`, and Terraform plan/apply; keep production deployment disabled until readiness review is recorded.
- [ ] Keep the existing production scheduler active until the protected replacement workflow and backup-gated catalog sync have succeeded. Verify `deployment-preflight` backup age/manifest, restore/rollback readiness, no overlapping manual/monthly sync, and failure behavior before cutover.
- [ ] Deploy the versioned Service, scraper, and Web releases through the protected coordinator. Verify Ford Service health, image/source identity, DB/Redis connectivity, Vault resolution, service logs, exactly one intended scheduler on emme-1/emme-2, and Ford-web Pages production smoke/asset/API checks.
- [ ] Verify catalog tools with read-only production smoke cases after Service configuration and database permissions are approved; verify monthly sync, backup-before-sync, scheduler ownership, and rollback window on the data host.
- [ ] Run a synthetic authenticated conversation for formal `usted`, current catalog evidence/source URLs, memory continuity, and advisor handoff; do not message a real customer. Verify Meta inbound/outbound delivery separately and record its evidence.
- [ ] Record both-host health and permissions, scheduler status, backup identity, release/run IDs, image digests, and rollback image. Exercise rollback if any protected gate or smoke check fails.

### E. Retire the old production MCP footprint after successful cutover

- [ ] Only after both hosts pass the checks above, remove old MCP containers, MCP-only Cloudflare route/tunnel configuration, MCP LaunchAgents, MCP-specific Vault secrets/grants, and obsolete GitHub secrets/variables.
- [ ] Verify no remaining MCP endpoint, process, route, secret consumer, or alert is active; retain catalog migrations, crawler, catalog writer, and the approved scheduler/refresh ownership.
- [ ] Re-run health, catalog sync, scheduler, and rollback checks after cleanup; attach evidence and update this checklist with exact completion dates and run IDs.

### Current status and constraints

- Local branch preparation (2026-10-18): Service is on `feat/mia-phase-one-release-readiness`; it contains release/readiness commit `cb229bb` plus this ledger commit, eight total commits beyond the last fetched `origin/main` (the branch includes the prior local design, implementation, and dispatcher commits). Ford-scraping is on `feat/mia-phase-one-mcp` at `9836223`, four commits ahead. Ford-web is on `feat/mia-phase-one-web` at `3e18489`, three commits ahead. The scraper and Web worktrees are clean; Service has only the pre-existing untracked `.tgrep/` directory, which was not staged. These are local commits only; no push, PR, merge, release tag, or deployment occurred.
- OCI catalog-secret recheck (2026-10-18): no `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD` value is present in the active environment or local deployment config; no catalog secret mapping file exists in the checked deployment/service config paths. The read-only `oci iam compartment list` query using the configured `migueldelzar` profile remained live beyond 52 seconds with 5-second connection/10-second read timeouts and returned no data; the local query process was terminated without making an OCI change. The catalog secret's exact name/OCID and app-grant contract remain unverified; do not guess or enable production callbacks.
- Publication recheck (2026-10-18): local `gh auth status` reports an invalid token and `api.github.com` fails DNS resolution. Release-coordinator App ID/client ID/key-path variables are configured in `~/.zshrc`, and the referenced private key is mode `0600`; network access is required to mint a short-lived installation token. No push, protected workflow dispatch, or production change occurred.
- OCI placement recheck (2026-10-18): the read-only OCI compartment-list request using the configured `migueldelzar` session profile timed out without returning compartment data. Vault/compartment placement and live instance-principal permissions remain unverified; no OCI write request was made.
- Local release-dispatch recheck (2026-10-18): `node --test scripts/mise-contract.test.mjs` passed 8/8; `python3 -m unittest discover -s ford-ai-service/scripts/tests -p 'test_release_all*.py'` passed 34/34; dispatcher `bash -n` and `git diff --check` passed. These checks do not prove publication or production readiness.
- Local Spring AI tool recheck (2026-10-18): `cd ford-ai-service && ./gradlew test --tests 'com.ford.service.ai.CatalogToolArchitectureTest' --tests 'com.ford.service.ai.CatalogToolsTest'` passed 22 tests (1 architecture, 21 tool tests; zero failures/errors/skips). This verifies local code only; the production flag remains false and deployment is unverified.
- Local Ford-scraping MCP-removal commit: `f4f1880` on `feat/mia-phase-one-mcp`; Ford Service dispatcher/docs commits: `e362d3b` and `24e42db` on local `main`. All are unpushed and must be reconciled with current GitHub refs before PRs.
- GitHub authentication and `git ls-remote` succeeded in the user's terminal; the agent runtime still cannot use the host keychain, SSH exits with UID 501 lookup failure, and its Docker package/network workaround stalled. Use the authenticated workstation to fetch/publish if agent networking remains unavailable.
- Service-owned catalog datasource and production verification remain incomplete. Production was not changed; catalog callbacks must remain off and deployed MCP resources must remain until the protected replacement passes.
- User direction and implementation (2026-10-18): do not pursue Node-to-Java parity work. The Spring AI `CatalogTools` implementation exists locally; production callbacks remain opt-in pending Service-owned configuration and least-privilege datasource verification. Removed the parity-only release variable and condition; Vault, datasource, approval, provenance, and rollout checks remain.
- The `.zshrc` exports only the release coordinator App ID, Client ID, and private-key path. Keep the private key in its mode-0600 file and use the protected GitHub App secrets for Actions.
- Older dated task sections below are historical records; the consolidated checklist above controls current release readiness. Preserve their evidence, but treat an old unchecked item as current only when it is represented in this checklist.

### Subtitle: Make both deploy lanes work, contracted, and verified. Local implementation is in progress; protected production cutover is still blocked by host/GitHub access and outstanding evidence gates.

## Findings (probed 2026-10-17)
- Tools present: mise, ansible-playbook/inventory/galaxy, rsync, ssh, docker, gh. On 2026-10-18 `gh auth status` reported an invalid token and `gh api user` could not connect; SSH to both production aliases failed locally with `No user exists for uid 501`.
- Local deploy config exists only in the main checkout: `deployment/config/oci.env` (mode 600) + `deployment/config/vault-secret-ocids.env` (20 OCID mappings). Absent in worktrees.
- `mise run deploy:check` (main checkout) FAILS: missing `POSTGRES_PASSWORD REDIS_PASSWORD FORD_META_APP_SECRET FORD_META_VERIFY_TOKEN GRAFANA_CLOUD_API_TOKEN LANGFUSE_SECRET_KEY FORD_META_WHATSAPP_ACCESS_TOKEN FORD_MCP_TOKEN FORD_SECURITY_JWK_PRIVATE_KEY FORD_RESEND_API_KEY`.
- Local deploy tasks: `deploy:all` = config+bootstrap+data(emme-2)+app(emme-1); `deploy:app [ghcr|local]` (local ARM64 build or remote build on emme-1) → `site.yml --tags common,app,verify --limit app`; `deploy:data` → `--limit data`; `deploy:status` shows remote `docker ps`. `deploy:catalog-sync` is the one-shot sync lane. The MCP runtime deployment path has been removed from source; deployed containers/routes remain until protected cutover and direct host verification.
- GitHub environments: `production`, `deployment-preflight`, `manual-release-approval`, `release-all-approval`, `terraform-runtime-vault-approval`. **No development environment.** Repo vars only `PRODUCTION_DEPLOY_ENABLED`, `RUNTIME_VAULT_READY`; the rest are env-scoped under `production`.
- `release.yml` deploys prod via pinned shared OCI blue-green action; gates = `PRODUCTION_DEPLOY_ENABLED` + `RUNTIME_VAULT_READY` + manual/coordinator approval. `monthly-catalog-sync.yml` runs on emme-2 from main.

## Plan
### Lane 1 — local→prod (emme-1/emme-2)
- [x] Decide secret-provisioning model (Vault pull vs plaintext oci.env vs runtime Vault).
  - **Actions Applied**
    - Read `.mise/lib/remote.sh:109` `require_deploy_config` (main checkout) — local Ansible path demands plaintext secrets; prod runtime separately uses Vault OCIDs via `app.env.j2`.
    - Confirmed all 10 missing keys have OCID mappings in `deployment/config/vault-secret-ocids.env`.
  - **Verification**
    - `oci secrets secret-bundle get` via session profile `migueldelzar` returned base64 content (MCP token decoded = 64B).
- [x] Make `mise run deploy:check` pass (`require_deploy_config` gate / Vault materialization).
  - **Actions Applied**
    - Backed up `deployment/config/oci.env` → `oci.env.bak.20261017215652` (mode 600).
    - Fetched the 11 required secrets from OCI Vault and wrote them with the repo's own `set_deploy_secret` writer (single-line safe); `chmod 600`.
  - **Verification**
    - `mise run deploy:check` → "Deployment tooling is ready." + inventory graph `@app emme-1`, `@data emme-2`.
    - sha256 of 8 keys matched `.worktrees/enterprise-agentic-runtime/deployment/config/oci.env` (authoritative).
- [ ] Verify `deploy:bootstrap` → `deploy:data` (emme-2) → `deploy:app` (emme-1) → catalog sync cutover with `deploy:status` + health. User authorization to proceed is recorded; actual deployment is blocked by the failed auth/SSH probes below, so no host mutation was attempted.
  - **Actions Applied**
    - `mise run deploy:status` (read-only) rendered inventory and ran remote `docker ps`.
  - **Verification**
    - emme-1: `ford-service-green` (18082), `ford-service-blue` (18081), `ford-service-proxy`, `app-otel-collector`. emme-2: `ford-mcp-blue/green`, `ford-scheduler`, `data-postgres-1` (healthy), `data-redis-1` (healthy). Deploy mutation NOT run yet — awaiting go-ahead.
- [ ] Record image digests + rollback image.

### Lane 2 — local→dev via Actions
- [x] Define dev target (local compose vs emme dev project vs new VM).
  - **Actions Applied**
    - Chosen: local Docker Compose dev stack (per user decision) booted in Actions; no new host.
  - **Verification**
    - `.mise/tasks/full/start` + `docker-compose.yml` confirm `ford-service` on `localhost:18080`.
- [x] Add `development` environment + variables/secrets.
  - **Actions Applied**
    - Workflow declares `environment: development` (GitHub auto-creates on first run); no secrets needed (self-generated `.env`).
  - **Verification**
    - Contract test asserts `environment: development`.
- [x] Add `deploy-dev.yml` (build → publish → deploy → smoke) + contract tests.
  - **Actions Applied**
    - `.github/workflows/deploy-dev.yml` — generates `.env`, `docker compose config --quiet`, `up --build -d postgres redis ford-service`, waits `/actuator/health`, asserts `UP`, `down -v`.
    - `scripts/workflow-contract.test.mjs` — registered the workflow + 2 new tests.
  - **Verification**
    - `node --test scripts/workflow-contract.test.mjs` → 39/39 pass.
    - Local boot of the exact app env on Colima/emme → `{"groups":["liveness","readiness"],"status":"UP"}` after 7 polls.

### Gates
- [x] `mise run deploy:check` passes.
- [x] workflow contract tests pass (39/39).
- [x] Production gates unchanged and still enforced (`PRODUCTION_DEPLOY_ENABLED`, `RUNTIME_VAULT_READY`, approval envs; `deploy-dev.yml` touches none).

### Note
- Full local Compose boot cannot be verified from `/private/tmp` worktrees: Colima does not share `/private/tmp` into its VM, so host file bind-mounts (`otel-collector` config) fail. Verify Compose boots from a worktree under `$HOME`, or rely on the CI (ubuntu-24.04) run.

# Humanized Ford conversation voice (2026-10-16)

- [x] Audit the CSV and existing prompt, persona, deterministic replies, memory, lead flow, and MCP behavior.
- [x] Keep existing new-vehicle, promotion, price, memory, and handoff policy scope.
- [x] Write and self-review the focused MÍA/formal-usted design.
- [x] User confirmed the scope: keep existing behavior and make it work as expected.
- [x] Write and self-review the TDD implementation plan.
- [x] Add a failing persona test, implement MÍA as the fixed name, and verify the first greeting.
- [x] Add failing tests for formal fixed replies and runtime-prompt precedence.
- [x] Implement the minimum changes; retain runtime-editable prompt support.
- [x] Align model-facing memory, tool retry, source-unavailable, classifier, and compaction instructions with formal address.
- [x] Run focused and full unit tests, deterministic Java AI evaluation, Node AI harness (9 tests), and release coordinator tests. PostgreSQL integration passed in required GitHub CI.
- [x] Merge PR [#59](https://github.com/migangdelzar/Ford-uruapan/pull/59) to main at `5f16ddec3f743eb7d1a592514f3d99574b7435f7`; publish and verify tag `v0.1.23`.
- [x] Complete the Service-only protected blue-green release; release-all run [38997055479](https://github.com/migangdelzar/Ford-uruapan/actions/runs/38997055479) and deployment run [38997076814](https://github.com/migangdelzar/Ford-uruapan/actions/runs/38997076814) succeeded.
- [x] Verify release result artifact: image `sha256:17199e073111497766c54ad2c6eaf862ba9d27a23b3f8255c1c2ea58f33da338`, source SHA `5f16ddec3f743eb7d1a592514f3d99574b7435f7`.
- [x] Verify public health (`UP`), `/` to `/login` redirect, `/login` response, and fingerprinted JS/CSS assets at `ford.emme.business`; no `pages.dev` URL appeared in login HTML.
- [ ] Verify the active and prior blue-green image identities independently and record the rollback image.
- [ ] Run an authenticated synthetic production conversation covering MÍA/formal `usted`, MCP-grounded current data with source URL, memory continuity, and advisor-authored handoff. Do not message a real customer.

### Remaining access check

- Password login reached Spring and was rejected. Production's configured operator email matches the login email, but the login API intentionally returns the same error for a bad password and an inactive identity. The logged-out `/api/auth/me` 401 and blocked Cloudflare Insights beacon do not explain the failed password login.
- The one-time email flow also intentionally hides account existence. A successful authenticated session is still needed for the synthetic production conversation; no password reset or recovery email was triggered.

# Jev classification implementation

## DeepSeek tool-turn response parsing (2026-10-16)

- [x] Confirm the production failure occurs after a successful MCP tool call and inspect Spring AI 2.0.1 call/entity semantics.
- [x] Add regression coverage proving an empty response cannot trigger a second hidden model completion.
- [x] Use only the single response from each provider attempt; preserve plain-text and JSON-text parsing.
- [x] Run focused adapter tests and full Java checks.
- [ ] Run the combined Jev + DeepSeek + MCP live E2E flow after the fix is deployed.
- [ ] Verify Meta inbound/outbound delivery separately; Test Lab does not send Meta messages.
- [ ] Deploy only after the code path and test pass; report Meta delivery separately from Test Lab verification.

## DeepSeek + MCP production follow-up (2026-10-16)

- [x] Reproduce a successful MCP lookup followed by a provider failure without logging content.
- [x] Preserve approved MCP source links on the safe withheld response when an exact requested fact cannot be rendered and the model fails.
- [x] Add allowlisted, content-free diagnostics for assistant rejection reasons.
- [x] Run focused adapter/provider diagnostic tests and `./gradlew check --no-daemon`.
- [ ] Re-run live Jev + DeepSeek + MCP after deploying this follow-up; diagnose successful-answer source-link behavior separately.
- [ ] Verify Meta inbound/outbound delivery separately.

### Results

- Live Test Lab proved authentication/inbox and Jev classification work; MCP calls returned successfully. One DeepSeek tool turn failed during response parsing; another completed but did not include a visible source link. Public MCP read-only tools returned current Ranger data and Ford URLs.
- When generation fails after MCP returned a technical-sheet URL but no renderer-supported requested value, the adapter now returns its safe withheld answer with that exact approved Ford URL. It does not infer the requested spec or leak MCP payload text.
- Provider failure diagnostics can now report a bounded allowlisted reason such as `invalid_tool_response_json`; arbitrary reason strings become `invalid_response`.
- Focused tests and `./gradlew check --no-daemon` passed. This follow-up is not yet deployed. The combined production Jev + DeepSeek + MCP result therefore remains partial; Meta delivery remains unverified.

### Working notes

- Production logs reported `MismatchedInputException` after MCP `get_vehicle` success. Spring AI 2.0.1 documents `entity(...)` as a response conversion operation, but its implementation performs another model request when invoked on the same response spec. Calling `content()` then `entity()` can therefore create a hidden second completion with a different output format.
- The adapter now consumes only `content()` for each model attempt and rejects blank output as `empty_response`; it no longer invokes `entity()` after the initial content read.
- `./gradlew test --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest --no-daemon` and `./gradlew check --no-daemon` passed.
- Keep customer content out of logs. Preserve exact-fact evidence checks and one repair bound.

## Multi-phase workflow and memory/MCP/classifier evaluation (2026-10-08)

- [x] Add structured pending-question confirmation independent of transcript/cache availability.
- [x] Fix classifier reconciliation for short Ford-context follow-ups.
- [x] Add PostgreSQL + Redis synthetic workflows covering vehicle lookup, offer lookup, appointment confirmation/decline, finance, and topic switching.
- [x] Exercise read-only MCP tool authorization/callback invocation and verify appointment decisions do not invoke tools.
- [x] Run focused regressions, deterministic offline AI evaluations, unit suite, and requested integration workflows.
- [x] Document unrelated integration-suite failures; do not claim the full integration suite is green.

### Results

- Three synthetic multi-turn workflows exercise durable lead journey, PostgreSQL message/decision evidence, Redis-cached conversation memory, decision/classifier reconciliation, read-only tool authorization, and topic changes. Provider/classifier and model reply data are deterministic fixtures; no paid LLM API or live tunnel/MCP call is made.
- Added a regression proving an affirmative uses durable `TEST_DRIVE_CONFIRMATION` state even when recent transcript memory is absent. Short follow-up “La XLT” can follow an established Ford context through Jev vehicle routing.
- Focused policy/scope/decision tests pass; `mise run test:ai-evaluation` passes (9 Node flow/eval checks plus Java regression suite); all unit tests pass.
- New workflow integration tests passed against PostgreSQL and Redis Testcontainers. The complete integration suite has three failures: two `MetaInboundWorkflowIntegrationTest` failures caused by its fixed `phone-number-1` binding conflicting across tests, and `ConversationScenarioIntegrationTest` expecting a projection-refresh event not received by the scenario. These failures reproduce in the wider integration run including the new test.
- Deployment preflight and operations contract checks passed. Deployed the ARM64 app image to emme-1 via the supported remote-build path; Ansible reported 20 successful tasks, zero failed/unreachable hosts, and verified app health, Meta route, plus PostgreSQL/Redis connectivity. emme-2 data services were not restarted and no database or conversation data was reset.
- `mise run test:check` remains red from the same three unrelated integration failures. The later live evaluation used only synthetic conversations and free OpenRouter routes; the real MCP tunnel and deployed inbound conversation flow remain untested.
- Follow-up free-model evaluation is recorded in `ford-ai-service/docs/evaluations/2026-10-08-free-model-evaluation.md`: `openrouter/free` passed 3/8 app-level synthetic scenarios and passed the Spring AI synthetic tool-call preflight. The pinned Qwen and Gemma runs were unavailable (HTTP 404/429), not quality failures. No persistent production conversation or database row was created.

## Pending-question lead flow repair (2026-10-08)

- [x] Trace the production conversation and confirm it remained in one conversation.
- [x] Identify the rules fallback, stale lead-journey pending question, and compound prompts.
- [x] Add regression coverage for affirmative test-drive continuation and multi-question replies.
- [x] Implement a deterministic reply to an unambiguous affirmative test-drive offer and persist the next pending slot.
- [x] Tighten the one-information-request-per-turn prompt and quality repair rule.
- [x] Verify focused behavior, full service build/tests, and update results below.

### Acceptance criteria

- A clear affirmative answer to a single recent test-drive offer continues appointment flow; it never returns to generic model discovery.
- Ambiguous answers or multi-option assistant offers do not auto-select an appointment.
- The continuation persists `TEST_DRIVE_APPOINTMENT` and one next pending question.
- Generated replies with multiple questions are rejected and repaired.
- Follow-up wording requests one missing piece of information at a time.

### Results

- `PendingTestDriveReplyPolicy` maps a clear affirmative to a single recent test-drive offer to `TEST_DRIVE_APPOINTMENT`, persists `TEST_DRIVE_DATE` as the next pending question, and replies with one date question without calling a chat model or Jev.
- Ambiguous/multi-option offers and unclear replies continue through the normal classifier/generator path.
- `AssistantResponseQualityPolicy` rejects multiple interrogatives and questions joined by `y`/`o`, then requests one repair; the system prompt also forbids combining details or presenting lead-flow menus.
- Focused regressions, full `./gradlew test --no-daemon`, and `./gradlew bootJar --no-daemon` passed. The code is local only; it has not been committed, pushed, or deployed.

## TypeSafe Jev activation wiring (2026-10-08)

- [x] Verify the exported workstation key and identify the app/deployment variable mismatch.
- [x] Add a no-echo adoption path from `FORD_TYPESAFE_JEV_API_KEY` to private `TYPESAFE_API_KEY`.
- [x] Propagate the canonical key through Mise inventory and Ansible to emme-1.
- [x] Add safe deployment readiness output and regression coverage for key presence/permissions.
- [x] Run an eight-flow synthetic Spanish classification smoke check and tighten option descriptions.
- [x] Reconcile deterministic specialist and scope routes against conflicting Jev tool choices.
- [ ] Confirm TypeSafe account spend limit or prepaid credits with automatic refill disabled.
- [ ] Publish and deploy to emme-1 after the account limit is confirmed.

### Results

- The API key was copied to the ignored local deployment config with mode `0600`; neither secret values nor generated Ansible credentials were printed.
- Jev 1.13 matched all eight synthetic intents and seven of eight raw intent/tool pairs. The app's deterministic route correction prevents a tool call on the one out-of-scope mismatch. Token usage was 7,689 input tokens, approximately $0.000323 at the listed rate.
- Full rollout remains gated on the TypeSafe account credit/refill setting. TypeSafe's API exposes no account billing configuration; production should wait until a $5 cap is set or a $5 credit balance is protected from automatic refill.

## Hybrid approved-knowledge retrieval

- [x] Confirm the existing knowledge vector store, metadata contract, Spring AI version, and PostgreSQL table.
- [x] Add filtered vector and PostgreSQL full-text retrieval, fused with reciprocal rank fusion and capped context.
- [x] Add deterministic fusion tests and a pgvector integration test proving lexical retrieval can recover a document outside vector top 20.
- [x] Document retrieval boundaries and verify focused tests plus build.

### Results

- Knowledge retrieval now combines filtered pgvector and PostgreSQL full-text candidates from Spring AI's default `public.vector_store` table. It returns at most eight approved, tenant/locale/type-matching chunks.
- Integration coverage checks tenant isolation, approval status, and lexical recovery outside the vector candidate set. Vehicle catalog behavior stays structured and unchanged.
- `./gradlew test --tests com.ford.service.knowledge.application.ReciprocalRankFusionTest --tests com.ford.service.ai.SimpleVectorStoreRagTest`, the focused pgvector integration test, and `./gradlew build` passed.

## Input security guardrails

- [x] Add deterministic injection and PII policy tests (`redact`, `block`, `allow`) before implementation.
- [x] Sanitize current text, memory, and journey context before classification and generation; block injection before any model/tool call.
- [x] Keep sensitive turns out of remote classification and honor existing local-provider restrictions.
- [x] Verify repeated-answer output repair regression cases and the complete build.
- [x] Document exact coverage and known limits; deploy only after local verification.

### Results

- `InputSecurityPolicy` now applies before Jev classification, lead qualification, scope evaluation, and Spring AI generation. Direct instruction-override markers are stopped before classifier/tool/model calls. Memory and journey blocks containing those markers are omitted.
- The existing PII policy is now active: `redact` is default; `block` returns a safe request to remove detected data; `allow` preserves it but sensitive turns bypass remote classification and cloud chat providers. Email, Mexican phone, valid Luhn card numbers, labeled CURP/RFC/CLABE/account values, and common credential assignments are recognized.
- The inbound transcript remains unchanged; only data passed into classification and model context is sanitized. Logs record bounded reason/flags only.
- Focused security/scope/repetition/config tests and `./gradlew build` passed. Deployed via the existing Mise/Ansible emme-1 path; no database schema change was required.
- Pattern matching is a limited defense-in-depth control, not a complete prompt-injection or PII detector. Response repetition checks remain heuristic and do not catch all paraphrases.

- [x] Add TypeSafe dependency/configuration and prove property binding.
- [x] Add typed decision vocabulary and policy.
- [x] Add TypeSafe adapter with deterministic fallback.
- [x] Propagate decisions into response generation and tool policy.
- [x] Persist normalized classifier evidence.
- [x] Wire optional TypeSafe startup behavior and diagnostics.
- [x] Add optional guardrail/self-refine configuration.
- [ ] Add end-to-end verification and operations docs.
- [ ] Run full verification and finish branch.

## Working notes

- Keep OpenRouter only for response generation.
- Keep rules fallback safe when TypeSafe is absent or fails.
- Never log or persist raw provider payloads or secrets.
- Human handoff remains controlled by `FORD_HUMAN_HANDOFF_ENABLED`.

## Ordered AI provider fallback and Oracle config

- [x] Add allowlisted Spring AI provider settings, disabled-by-default readiness gates, and per-provider OpenAI-compatible clients.
- [x] Keep primary OpenAI/Ollama routing; try eligible fallbacks in configured order, at most once each, with a six-call ceiling and bounded retry delays.
- [x] Carry provider models, endpoints, settings, and keys through Mise's ignored deployment config and Ansible's mode-0600 app environment; keep keys out of Terraform.
- [x] Add safe provider readiness output, secret adoption coverage, and Oracle/local environment documentation.
- [x] Verify provider config/router tests, deterministic AI evals, ten-flow handoff fixture, operations contracts, Gradle check/build.
- [x] Copy provider keys into ignored Oracle config from a shell where they are exported; run `mise run deploy:check` and deploy after provider-specific live evals.

### Results

- Provider-specific keys were adopted into the ignored Oracle config through Mise without printing key values. Four fallback entries are enabled in Ansible runtime configuration.
- Deterministic ten-flow authorization/payload checks passed. Historical LLM/Ragas results remain in `docs/ford-specialist-handoff.md`; no live model or Ragas evaluation was run here.
- `mise run test:check` initially could not find Docker because Testcontainers ignored its configured socket. It passed after scoping `DOCKER_HOST` to the already-running default Colima socket; no Docker/Colima settings were changed.
- Implementation remains uncommitted because this shared checkout contains unrelated user edits.

## Verify and repair all live AI providers

- [x] Add an opt-in Spring AI preflight that requires a synthetic tool call and checks final app response conversion.
- [x] Disable Gradle up-to-date reuse for live preflight so provider/model changes always execute a real request.
- [x] Verify OpenRouter `openai/gpt-oss-120b` through the app adapter; record only safe capability flags.
- [x] Diagnose Cloudflare, Groq, and Gemini failures using provider-specific model/API contracts.
- [x] Implement the smallest compatible request/response strategy with tests before changing runtime behavior.
- [x] Re-run all four live preflights, then deploy through the existing Ansible/Mise pipeline and verify emme-1.

### Acceptance criteria

- All configured provider entries can complete the same synthetic tool-call plus `AssistantGeneration` response path.
- Live preflight always makes fresh requests and emits no secret, prompt, or provider response body.
- Only a verified provider/model is eligible for production routing.

### Provider verification results (2026-10-06)

- Cloudflare `@cf/meta/llama-3.3-70b-instruct-fp8-fast`, Groq `openai/gpt-oss-20b`, and OpenRouter `openai/gpt-oss-120b` passed the synthetic tool-call and app response-conversion preflight through Spring AI.
- Gemini `gemini-3.8-flash` passed the same preflight once; a later run returned provider HTTP 503 with a temporary high-demand message after the synthetic tool call. The app fallback router is configured to continue to OpenRouter on retryable provider failures.
- Full Gradle tests and bootJar passed. The Mise operation-contract tests, deployment flag tests, and deployment readiness/inventory checks passed.
- Deployed via `mise run deploy:app` with the supported emme-1 ARM64 remote build. Ansible reported zero failed/unreachable hosts; app health, Meta webhook route, PostgreSQL, and Redis checks passed. Container logs show `FordServiceApplication` started; inspection confirms all four fallback providers are enabled in configured order.

## Ford specialist handoff and MCP runtime recovery

- [x] Map the main-app turn path, prompt source/override, Ford information MCP, handoff queue, and MCP/Cloudflare startup configuration.
- [x] Add failing tests for consent authorization from actual recent turns, decline/duplicate handling, typed payload validation and omissions, queue success/failure wording, and app-level conversation behavior.
- [x] Implement a narrowly scoped typed specialist-handoff service over the existing internal queue; keep the Ford information MCP read-only and do not claim an external advisor transfer.
- [x] Enforce direct-request authorization and offer-then-later-affirmative authorization in application code; preserve declines and gate offers to qualifying turns.
- [x] Update the classpath system prompt and runtime configuration guidance; preserve existing tool-routing, grounding, financing, and truthfulness rules.
- [x] Adapt the ten Ford-scraping multistep cases to deterministic main-app policy checks; preserve partial runs and traces, and report LLM/Ragas scores separately.
- [x] Diagnose and fix the MCP startup failure without weakening read-only tool requirements; create a dedicated Cloudflare route for the workstation MCP.
- [x] Run requested unit/integration tests, build, MCP lint, and deterministic eval; document exact results and missing live-integration details.
- [x] Run the Ford information MCP on this Mac, expose it through the dedicated Cloudflare tunnel, and point emme-1's existing MCP client config at that tunnel; keep the app's existing tunnel route unchanged.
- [x] Verify the locally hosted MCP and remote app connection without creating CRM records or contacting customers. Document the workstation-online dependency.

### Acceptance criteria

- Information-provider tools remain read-only and unchanged.
- A model handoff claim cannot create a queue item without a current direct request or a later affirmative following an eligible offer in persisted conversation history.
- Payload values are strict, typed, grounded in customer text, and omit unknown optionals.
- Queue success and failure responses match the actual internal queue result and never promise contact, appointment, or follow-up.
- All ten supplied app fixture cases have deterministic policy results; the separate live-model evaluation remains 6/10 passing with four failures and two incomplete traces.
- Existing unrelated workspace changes are preserved.

## Enterprise architecture baseline and assistant scope guardrails

- [x] Inspect existing EA/runtime designs, current request path, scope/intent/tool policies, prompt, tests, and production observability/MCP topology.
- [x] Agree on the boundary: supported Ford assistance, greetings, and clear Ford-context follow-ups; redirect unrelated and unsupported requests before model calls.
- [x] Write the architecture baseline with C4 context/container and sequence views, data/trust boundaries, current deployment facts, and known gaps.
- [x] Add failing unit/app tests for general knowledge, prompt injection, mixed requests, unsupported service/complaint requests, contextual follow-ups, and optional approved knowledge search.
- [x] Enforce deterministic scope before generation; a model decision cannot widen it; service support is allowed only when `approved_knowledge_search` is registered.
- [x] Align system prompt and mark the earlier runtime design as historical where its status claims are stale.
- [x] Run focused and full regression tests, bootJar, operations contracts, deployment preflight, and diff review.

### Results

- The production `AssistantScopePolicy` previously let unclassified `GENERAL` requests reach model generation and allowed any Ford keyword in memory to make an unrelated question appear in-scope. Regression tests reproduced these cases.
- The target scope now allows only supported deterministic Ford intents, pure greetings, and specific contextual follow-ups with Ford memory. Explicit out-of-scope markers veto the entire mixed request. Unsupported service requests pass only when the approved knowledge-search tool is registered; complaint-status requests remain blocked.
- The app-level service invokes no orchestrator for blocked requests. The classifier cannot upgrade a deterministic general/out-of-scope request.
- Production architecture evidence is split between configuration and observed rollout. The old scrape 401 and disabled-trace state predated the telemetry rollout. The corrected production Collector template (OTLP metrics only) is pending deployment; Grafana signal arrival and ongoing workstation MCP availability remain unverified.

### Verification results (2026-10-07)

- Focused scope/provider/handoff regression tests passed.
- Full `./gradlew test --no-daemon --rerun-tasks` passed with all test tasks executed; `./gradlew bootJar --no-daemon` passed.
- All 24 `node scripts/operations-contract.test.mjs` checks pass after removing the stale production Collector scrape receiver and its unused Collector env credentials.
- `mise run deploy:check` passed and confirmed emme-1/emme-2 inventory. Corrected Collector config has not yet been deployed; live backend signal and alert notification checks remain unverified.
- `git diff --check` passed. Docker-backed integration tests were not run in this pass.
# Workstation Ollama remote access

- [x] Inspect existing Cloudflare tunnel/DNS, MCP HTTP authentication, local Ollama, Ansible inventory, and Spring AI client configuration.
- [x] Present and receive approval for the design: reuse `ford-mcp-workstation`; add Access-protected Ollama and MCP hostnames; use dedicated localhost Ansible plus emme-1 app Ansible.
- [x] Write and self-review design spec and implementation plan.
- [ ] Implement scoped Spring Access headers and tests.
- [ ] Implement localhost Ansible LaunchAgents and deployment config.
- [ ] Provision/read back tunnel routes, Access apps/policies, service tokens; store secrets privately.
- [ ] Deploy/reconcile both ends and verify authorized and denied requests.

### Working notes

- Existing tunnel is healthy; current MCP route is `ford-mcp.emme.business` → localhost:3001.
- No Access applications currently exist for the account.
- Host Ollama is 127.0.0.1:11434; Docker Ollama 127.0.0.1:11435 is embedding-only.
- Keep the extensive pre-existing dirty changes intact; do not commit.

# Gradual chat capacity and lead routing (2026-10-08)

- [x] Implement cloud-first model fallback with Ollama last, bounded Modulith conversation workers, and durable publication recovery.
- [x] Enable Jev lead/handoff classification settings while keeping sensitive-data classification off and authorization deterministic.
- [x] Add HOT-lead offers with remembered decline state, compact-summary rollover, and synthetic conversation routing fixtures.
- [x] Run focused and full test suites; record actual results in `docs/superpowers/plans/2026-10-08-gradual-chat-and-lead-routing.md`.
- [x] Reconcile operations and README docs against effective configuration; inspect diff for unrelated and sensitive-file leakage.

# Ford-web Test Lab authentication rollback (2026-10-09)

- [x] Remove the newly added OIDC client/BFF and restore Ford-web's prior demo login flow.
- [x] Keep `/api/testlab/respond` fail-closed and forward only a caller-supplied bearer token to Ford-service.
- [x] Record the contract: Spring Security validates external JWTs; Ford-service has no login/token-issuing endpoint; the demo JWT is not valid for it.
- [x] Keep Test Lab PostgreSQL session persistence scoped by tenant + operator + stable conversation ID, with bounded context and owner isolation.
- [ ] Provide a valid access-token source to the Test Lab UI before claiming live authenticated end-to-end connectivity.

# Ford CRM backend auth and Pages integration (2026-10-09)

- [x] Confirm Spring Authorization Server in Ford-service issues operator JWTs; passkeys primary; email one-time-token recovery.
- [x] Create feature branches in Ford-service and Ford-web while preserving existing dirty changes.
- [x] Add contract drift tests, BFF OpenAPI overlay, and generated contract synchronization.
- [x] Align canonical OpenAPI auth metadata and endpoint routes with Spring's passkey/OTT flow.
- [ ] Add persistent invited operator identities and claims.
- [ ] Configure persistent Spring Security passkey and one-time-token login.
- [ ] Add Spring Authorization Server and JWT resource-server chains.
- [ ] Add Ford-web BFF sessions and same-origin Pages routing.
- [ ] Connect UI login and Test Lab; migrate only implemented CRM API families.
- [ ] Verify temporary-origin deployment, then present production cutover preflight.

### Working notes

- Use Spring Security's JDBC WebAuthn repositories and JDBC one-time-token service; persist browser auth sessions in Redis.
- Preserve unrelated existing edits in both repos. Never stage `deployment/config/oci.env*`, `.tgrep/`, or other prior user work for this task.
- Production DNS/Tunnel cutover remains gated on a concrete preflight and approval.

# Spring UI proxy and BFF consolidation (2026-10-10)

- [x] Approve the Spring-owned UI proxy + Redis-backed browser-session design; keep bearer JWT clients and the OpenTelemetry Collector.
- [x] Write and review the implementation plan in `ford-ai-service/docs/superpowers/plans/2026-10-10-bff-consolidation.md`.
- [ ] Make Spring session auth work for protected APIs with active identity refresh and CSRF; preserve stateless bearer JWT.
- [ ] Switch Ford-web login/session calls to same-origin Spring auth endpoints.
- [ ] Deploy compatible versions while BFF stays available; verify all login/API/webhook flows.
- [ ] Cut `ford.emme.business` over to emme-1; requires Cloudflare Zone DNS Edit permission.
- [ ] Remove BFF container and `ford-origin` route only after public hostname verification; retain Collector.
# Deterministic MCP price and promotion response templates (2026-10-11)

Goal: Spring fills separate Markdown templates from returned MCP price/promotion facts; the LLM does not rewrite factual values.

- [x] Inspect tool result shapes and current completion seam.
- [x] Add renderer tests for confirmed prices/promotions, source links, and missing evidence.
- [x] Implement MCP evidence parsing and separate Markdown resource rendering.
- [x] Capture only relevant bounded payloads and override generated text for matching questions.
- [x] Run focused tests and check diff.

### Results

- Added independent price, promotion, combined, and no-evidence Markdown templates under `ford-ai-service/src/main/resources/response-templates/`.
- Spring fills separate templates with MCP-confirmed published prices, benefits, validity dates/status, conditions, and Ford source links. It never computes a net price or treats unknown validity as active; the LLM cannot rewrite these factual replies.
- MCP evidence capture is limited to `get_vehicle`, `search_vehicles`, and `list_promotions`, with a 64 KB per-payload cap. Source links are restricted to Ford Uruapan HTTPS hosts and query parameters are stripped.
- The first reply gets a stable Mexican name derived from the conversation ID; later replies in that conversation skip the introduction. The greeting does not claim the assistant is human, and the prompt requires an honest answer if asked.
- Scope policy rejects unrelated questions even if a classifier labels them Ford-related; explicit vehicle shopping requests and genuine in-context follow-ups remain supported.
- Focused persona, scope, renderer, adapter, and assistant-service tests passed; full `./gradlew test --no-daemon` passed (565 tests), including app-context startup. Changes are local; not committed, pushed, or deployed.

# Safe DeepSeek responses and advisor handoff (2026-10-12)

- [x] Keep one-detail assistant replies, log only rejection reasons, bound model repair/attempts, and return safe replies when MCP is unavailable.
- [x] Verify OpenRouter tool/structured-output behavior for the earlier fallback configuration; production routing was later changed to DeepSeek-only.
- [x] Send a customer handoff notice after an advisor claims the conversation; persist sender type/identity and expose it through the API/UI.
- [x] Verify app startup, Modulith listener packaging, DB migration, handoff transcript/outbox, and frontend sender rendering.
- [x] Run a read-only live MCP vehicle search and confirm catalog source URLs are returned.
- [ ] Recheck production logs after rollout; current app image still logged one `ClassNotFoundException` for `ConversationTurnRealtimeListener` in the last 100 lines.

### Results

- Branch: `fix/advisor-handoff-message-senders` in Ford-uruapan and Ford-web, based on each repo's current `origin/main`.
- On successful advisor claim, the service appends one outbound advisor-authored notice: “Hola, ya tomé tu conversación. Te responderé por aquí en cuanto pueda.” Message sender type/name now survive DB/API/UI mapping; existing messages receive a safe sender backfill.
- Historical behavior: DeepSeek was paired with OpenRouter as the only fallback. Response validation logs only a reason, not prompts or generated text. MCP failures/empty results withhold exact volatile facts; confirmed MCP prices/promotions render deterministic source-linked answers. Production was later found running Ollama and is being switched to DeepSeek-only below.
- Verification passed: full backend unit suite, focused backend suite, Ford-web sender Vitest, `bun run lint`, API contract check (47 paths), app context/Liquibase/Modulith tests, handoff/conversation integration tests, end-to-end Meta-to-outbox flow, and `bootJar` packaging check.
- Live read-only MCP `search_vehicles` returned three Ford Ranger results and Ford Uruapan source URLs. No deployment was performed. Production log check found no follow-up-service or classifier-failure signatures, but did find one realtime listener `ClassNotFoundException`; recheck after the feature branch is deployed.

## DeepSeek-only production chat (2026-10-16)

- [x] Verify the existing production OCI Vault API key against DeepSeek without printing or persisting it.
- [x] Set the production profile and Ansible defaults to DeepSeek `deepseek-flash`; disable Ollama and all chat-provider fallbacks.
- [x] Require the DeepSeek Vault secret reference in the protected service-release workflow and stop forwarding unused fallback keys into the app.
- [x] Add production config/workflow regressions and support the primary provider in the opt-in live provider preflight.
- [x] Run the live DeepSeek Spring AI tool-call preflight with a synthetic lookup.
- [ ] Merge the verified changes to `main`, publish a versioned Ford Service release, and deploy through the protected GitHub Actions workflow.
- [ ] Verify deployed provider settings and a real Meta chat turn with MCP evidence; confirm exact volatile facts remain withheld when MCP has none.
- [ ] Add/verify a clearly labeled general-knowledge reply only when separable from MCP-dependent facts; never infer exact specs, prices, promotions, or availability.
- [ ] Verify deterministic MCP-backed source-linked output when DeepSeek and its one repair fail but tool facts are available.
- [ ] Identify the exact production conversation before restarting its LLM memory; preserve the stored transcript.
- [ ] Recheck classifier and Modulith follow-up/realtime listener logs after the release.

### Verification results

- Production `OPENAI_API_KEY` Vault secret authenticated to `https://api.deepseek.com/models` with HTTP 200; the response listed `deepseek-flash`. The secret was piped directly from OCI CLI to the request and never printed.
- The opt-in Spring AI preflight passed with `provider=openai`, `model=deepseek-flash`, and one synthetic tool call.
- Java suite and `bootJar` passed (650 tests, no skipped or failed tests); Node workflow/operations contracts passed (71 tests); Ansible app playbook syntax check passed.
- The production runtime has not changed yet: it still selects Ollama until the approved Actions release is deployed.

# Credential storage hardening (2026-10-12)

- [x] Add OCI Vault-backed Spring secret loading using Compute instance principals, enabled only by explicit provider selection.
- [x] Prevent generated Ansible inventory from storing secret values; sanitize the existing generated inventory without printing its contents.
- [x] Add no-echo OCI Vault create/rotate tooling and save only secret OCIDs in a mode-0600 ignored mapping file.
- [x] Import configured current values into test-only Vault secrets, keeping the production mapping and runtime mode unchanged.
- [x] Disable the legacy task that persisted secret values in `deployment/config/oci.env`; add a macOS Keychain wrapper for local commands.
- [x] Document credential ownership, rotation, and runtime migration requirements.
- [x] Verify Python helper tests, Spring tests/build, Ansible syntax, Jinja templates, shell syntax, and whitespace.
- [ ] Rotate issuer-managed credentials, provision replacement values in OCI Vault, migrate data/MCP/collector runtime retrieval, deploy, and verify production.

### Results

- Added tests for secret request encoding, OCI-shaped OCIDs, test-only current-value import, OCID mapping safety, generated-inventory scrubbing, Keychain handoff, and hidden Vault prompts. `python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v` passed (16 tests).
- `./gradlew test bootJar` passed. Ansible `site.yml` and `mcp.yml` syntax checks passed; four Ansible Jinja templates parsed; changed shell scripts passed `bash -n`; Python scripts parsed; `git diff --check` and cached diff check passed.
- The local generated inventory had 18 credential fields removed while retaining unrelated settings and restrictive permissions. No credential values were printed. At the user's request, 20 current configured values were imported into active `ford-test-*` Vault secrets; the ignored test mapping contains only OCIDs and the production mapping is untouched. Two unconfigured Meta tokens were skipped.
- Production remains on environment mode. OCI browser authentication is active; read-only verification confirmed all 20 test secrets are `ACTIVE`, the test mapping is mode `0600`, and all mapped OCIDs match Vault resource IDs. Issuer-approved production replacements are still needed. Data, MCP, and collector still need direct Vault retrieval before production can switch safely. No deploy, commit, or push was performed.
# Meta reply delivery and public HTTPS login redirects (2026-10-16)

- [x] Add failing contracts for Meta runtime settings through the shared OCI action and Ford Service release workflow.
- [x] Pass delivery/outbox config and Meta Vault secret OCIDs to Ansible; snapshot the protected app `.env` and restore it on failed rollout.
- [x] Validate the Meta endpoint, phone-number ID, flags, and Vault references before production deployment.
- [x] Configure nonsecret GitHub production variables from the existing local deployment config without exposing values.
- [x] Merge the shared action and Ford Service changes into `main`, publish Ford Service `v0.1.15`, and complete the protected production deployment.
- [ ] Fix the still-HTTP login failure redirect in the effective Ford Service Nginx template, add a regression contract, publish a follow-up version, and redeploy through the protected workflow.
- [ ] Verify the public login redirect stays HTTPS and the pending Meta reply is delivered; inspect only redacted delivery status.

### Progress

- Local config passes safety checks; four GitHub production variables were set and three existing Vault OCID variables validated.
- Shared action and Ford Service release contract tests passed. Ford Service `v0.1.15` was tagged, image build/scanning/provenance passed, and the protected production deployment completed successfully.
- A safe invalid-credential probe exposed the remaining login bug: the live app returned `302 Location: http://ford.emme.business/login?error`. The running proxy config still had `X-Forwarded-Proto $scheme`; its source is the Ford Service repo template used by the shared action, not the similarly named template in the action repo.
- The correction branch now tests and sets fixed HTTPS forwarding, clears untrusted `Forwarded`, and sets the forwarded host/port explicitly. Production fix and Meta outbox status verification remain pending.

## Catalog evidence parity continuation (2026-10-17)

- [x] Add only `benefits` and `termConflict` metadata to the versioned Node `offers_v1` projection; append columns to preserve existing view column order.
- [x] Map the metadata as structured JSON through Java offer/promotion adapters and public tool responses.
- [x] TDD: Node view contract failed before the SQL projection; Java adapter H2, tool boundary, and PostgreSQL Testcontainers checks pass.
- [x] Add `vehicle_facts_v2` with exact variant ID, category, normalized fact name, numeric value, and source-complete evidence; switch the Java reader to this view.
- [x] Run Node read-view integration after the migration change in a disposable `ford_catalog_test` database: 6/6 passed. No MCP or production DB was used.
- [x] Run Java H2 adapter/tool tests and PostgreSQL Testcontainers parity: passed, including structured offer metadata and variant-aware facts.
- [-] Retired by user direction (2026-10-18): do not continue Node/Java parity gaps. Keep this earlier entry as historical status only.
- [ ] Publish through GitHub and run protected production rollout; GitHub CLI auth is invalid, device login cannot connect to github.com, and production has not changed.

#### Resume verification (2026-10-17)

- [x] Fresh Ford-scraping verification: `npm run lint`, `npm run test:unit` (144/144), `npm run build`, and `node --test scripts/container-contract.test.mjs` (5/5) pass after formatting the read-view contracts.
- [x] Fresh Service operations verification: Python contracts (98/98), workflow contracts (36/36), and Ansible catalog-sync syntax parse pass.
- [x] Preserve applied Node migration 022 and move parity projections into additive migration 023; focused red/green migration contract passes 6/6.
- [x] Refresh Java fixture setup to match `vehicle_facts_v2`, JSON offer columns, and the narrow datasource context's Jackson dependency; focused rerun passes.
- [x] Run fresh full Java `test bootJar compileIntegrationTestJava`; 704 tests pass and integration sources compile. Synthetic PostgreSQL Testcontainers parity passes 2/2.
- [x] Apply migrations 023 and 024 to the retained restored snapshot; Java parity passed 3/3 with zero skips as a temporary SELECT-only role (SELECT allowed, INSERT denied); role/grants removed.
- [ ] Keep production catalog callbacks disabled until Service-owned datasource permissions, authorization, evidence grounding, and direct host checks pass. Archived callable-output parity and MCP ranking comparisons are out of scope by user direction; see the updated acceptance criteria in `2026-10-17-mia-catalog-evidence-lane.md`.
- [ ] Production stays blocked pending valid GitHub authentication, publication of the coordinated OCI action/workflows, and protected rollout/host verification on emme-1 and emme-2.
# MÍA Phase 1 expanded delivery (2026-10-17)

**Scope sources:** [supplied implementation plan](/Users/miguelangeldelgadillozarate/Downloads/mia-ford-uruapan-plan.md), [approved Phase 1 design](docs/superpowers/specs/2026-10-17-mia-phase-one-assistant-design.md), and the focused plans under `docs/superpowers/plans/`.

The supplied plan broadens the active catalog replacement into the full MÍA Phase 1 outcome. Reuse the existing Ford Service webhook, conversation pipeline, Jev/rules classification, DeepSeek generation, lead journey, BDC queue/outbox, inbox, LangGraph4j, and Spring AI. Do not create parallel orchestration, memory, CRM, or catalog-ingestion systems. Where the supplied draft proposes a separate BGE reranker service or hand-built retrieval stages, follow the approved framework-first design: Spring AI RAG advisor, existing filtered hybrid retrieval/RRF, and the installed Jev document reranker behind an off-by-default flag.

## Milestones and required exit evidence

- [x] **M0 — Foundations baseline:** pin/current stack verified (Spring Boot 4.1.1, Spring AI 2.0.1); existing Node catalog contract and read-only Service adapter identified. Full reviewed fixtures, dealership evidence, and external voice-bank inputs are still incomplete.
- [ ] **M0 — Complete evidence set:** capture/approve 50–100 anonymized conversation examples, dealership voice samples, holiday/special-hours calendar, and price policy sign-off. These are required external inputs; they were not supplied, so do not fabricate them. Node-versus-Java parity fixtures are out of scope per user direction.
- [x] **M1 — Safe skeleton audit:** verified the existing webhook guard, durable receipts, LangGraph4j checkpoint/workers, ownership, and handoff queue; filled only turn-interception and shadow-draft gaps. Full unit/integration suites pass.
- [ ] **M2 — Understanding and conversation behavior:** unit/integration behavior is covered, but verify extracted intent/slots, pending-question context, answer-first behavior, interruptions, one-question limit, and no-reask behavior against the approved real golden set when supplied.
- [ ] **M3 — Catalog read path:** schema-backed adapter and read-only snapshot checks pass. The user explicitly excluded Node/MCP parity; active gates are the Service-owned tool contract, direct read-only datasource credentials/permissions, evidence grounding, and production host verification. Keep production callbacks disabled until these pass.
- [x] **M4a — Writer, RAG, and guardrails:** Spring AI advisor routing (knowledge-search route only), route-scoped retrieval via `KnowledgeRagAdvisorFactory`, volatile-fact evidence enforcement, source citations, and the shared evidence policy wired into the adapter repair loop.
- [x] **M4b implementation — Guardrail checks:** shared policy checks exact model/variant names, numeric claims, URLs, promises, and response length; it reuses `VehicleModelNames` and the existing response-quality policy. Targeted policy/adapter tests pass. **Reviewed real Spanish evaluation set remains blocked** on missing supplied examples.
- [ ] **M5 — Retrieval and quality evaluation:** synthetic guardrail evaluation passes; the reviewed Spanish retrieval set and end-to-end latency/reranker-cost comparison remain open. Existing Spring AI hybrid retrieval/RRF and TypeSafe document reranking are reused; reranking stays disabled by default until evaluation demonstrates a gain.
- [x] **M6 — BDC handoff implementation:** typed evidence-backed packet, partial-packet metadata, transactional/idempotent persistence, direct/complaint/refusal escalation, truthful queued copy, and regression tests are implemented. Real conversation golden-set validation remains blocked on supplied examples. CRM is Ford-web, operated locally; no separate external CRM provider is in scope. The existing BDC queue stays authoritative. The 8:45 digest remains open for delivery through the local CRM workflow.
- [x] **M7 implementation — WhatsApp operations and shadow review:** property-bound dealership hours, response planning, durable debounce, API-only unsent-draft review, and human kill switch are implemented and tested. Live after-hours rollout remains blocked on external calendar/evidence, operator dry-run, privacy, and end-to-end latency gates.
- [x] **Monthly catalog sync implementation:** protected monthly/manual workflow, host backup verification, one-shot runner, rollback path, and local contract tests are implemented. **Production run/cutover remains blocked** until the coordinated workflow is published and its protected manual run succeeds.
- [ ] **Production release:** publish the coordinated Ford-scraping, Ford Service, and OCI workflow changes; pass protected CI/release checks; deploy and verify emme-1 and emme-2, catalog read permissions, and exactly one active monthly trigger on each required host. MCP parity is not a release gate.
- [ ] **Final review:** code review all worktree commits, integrate only reviewed commits, run end-to-end verification, record release/run IDs and both-host evidence, and update the lessons/results ledger.

### Scope reconciliation

- Use these detailed implementation plans as the task source: `2026-10-17-mia-catalog-evidence-lane.md`, `2026-10-17-mia-conversation-handoff.md`, `2026-10-17-mia-whatsapp-operations.md`, `2026-10-17-mia-shadow-evaluation.md`, and `2026-10-17-monthly-catalog-sync-workflow.md`.
- The full supplied M0–M7 plan is the outcome map, not a mandate to rebuild working Ford Service components. Validate existing functionality first and keep changes punctual.
- User scope clarification (2026-10-18): Ford-web is the CRM and will run locally. Do not add a separate external CRM provider integration. Preserve the internal BDC queue; the 8:45 digest should be handled through the local CRM workflow once its recipient and delivery behavior are specified.
- User scope clarification (2026-10-18): catalog tools belong in Ford Service's in-process Spring AI configuration, independent of MCP. Do not implement or require Node-to-Java/MCP parity. Keep catalog access read-only and Ford-scraping-owned. Production callbacks still require Service-owned evidence/authorization checks, least-privilege datasource access, protected release, and host verification.
- Do not call production ready until evidence, safety, release, and host-verification gates pass.

### Ford-web shadow-review surface (2026-10-18)

- [x] Add tenant-scoped draft list and CSRF-protected ACCEPTED/REJECTED annotation calls to `Ford-web`.
- [x] Add the inbox review panel; state plainly that drafts are internal and are never sent; add no send action.
- [x] Sync the generated Ford-web OpenAPI copies from the Service-owned contract.
- [x] Verify web TypeScript lint, full unit/component suite (134 tests), production build, and API contract consistency (49 paths).
- [ ] Merge the reviewed frontend change (`453a8a2` shadow-draft review panel; `6fd7de2` Ford-web local-CRM task ledger) into Ford-web main and include the exact source commit in the protected three-repository release. Current evidence: local focused release/production-smoke contract tests pass 12/12; GitHub publication remains pending because this environment's `gh` token is invalid and SSH Git fails with `No user exists for uid 501`.

Results: frontend changes are in the isolated `/private/tmp/ford-mia-phase1-web` worktree on `feat/mia-phase-one-web`. `bun run lint`, `bun run test` (33 files, 134 tests), `bun run build`, OpenAPI sync/check, and `git diff --check` passed. Production was not changed.

## Resume results (2026-10-17, design worktree)

### Continued work (2026-10-18)

- [x] Verify the previous session's in-progress Spring AI RAG/evidence changes with focused tests.
  - `./gradlew test --tests '*KnowledgeRagAdvisorFactoryTest' --tests '*McpFactResponseRendererTest' --tests '*SpringAiChatCompletionAdapterTest' --offline --no-build-cache --no-daemon` → BUILD SUCCESSFUL.
- [x] Repair two evidence-grounding edge cases: RAG source citations now append even when no catalog tool ran; numeric claims require exact normalized-number matches and single-digit claims are checked.
  - Added regressions to `EvidenceGroundedResponsePolicyTest`.
  - Focused evidence/RAG/adapter suite → BUILD SUCCESSFUL.
- [x] Implement the configurable pure dealership-hours policy from WhatsApp Operations Task 1.
  - Added `DealershipHours`, `DealershipHoursPolicy`, and `DealershipHoursProperties`; defaults are America/Mexico_City, Mon–Fri 09:00–19:00, Sat 09:00–14:00, Sun closed. Explicit date overrides support closure/special hours without embedding holiday dates.
  - Registered the policy through the existing conversation configuration and added fixed-instant boundary, timezone, Sunday/next-opening, and override tests.
  - `./gradlew test --tests '*DealershipHoursPolicyTest' --tests '*ConversationConfiguration*' --offline --no-build-cache --no-daemon` → BUILD SUCCESSFUL.
- [x] Bind/verify weekly and special-date property examples and wire hours information into response planning without suppressing after-hours catalog answers; focused tests plus fresh full Service tests pass.
- [x] Complete local implementation for WhatsApp durable debounce, BDC packet/handoff, shadow review API/evaluation, and monthly sync workflow. Remaining evidence, real frontend integration, production proof, and Service-owned catalog datasource verification stay explicitly open below.

### Task: RAG-route compile break + M4a guardrails

- [x] Fix the compile break left by the prior session (`evidenceStatus` call site missing the `KnowledgeRagEvidenceTracker` argument).
  - **Actions Applied**
    - `SpringAiChatCompletionAdapter.java:281` — passed `knowledgeEvidence` to `evidenceStatus(request, toolResults, knowledgeEvidence)`.
  - **Verification**
    - `./gradlew test --tests '*SpringAiChatCompletionAdapterTest' --tests '*KnowledgeRagAdvisorFactoryTest' --tests '*McpFactResponseRendererTest'` → BUILD SUCCESSFUL.

- [x] Add the deterministic evidence-grounded guardrail and wire it into the single-repair loop.
  - **Actions Applied**
    - New `ai/application/EvidenceGroundedResponsePolicy.java` — rejects unsupported numeric/price/year claims, unsupported URLs, and never-promise/approval language; provides repair instructions.
    - `KnowledgeRagEvidenceTracker.evidenceText()` — exposes retrieved chunk content + metadata for grounding.
    - `SpringAiChatCompletionAdapter.java` — new 12-arg constructor, `rejectionReason`/`repairInstruction`/`evidenceLedger`, policy checked after voice/quality in the same one-shot repair loop.
    - `SpringAiConfiguration.java` — registered `EvidenceGroundedResponsePolicy` bean and passed it to all three provider adapters.
    - Tests: `EvidenceGroundedResponsePolicyTest` (9 cases) + adapter `repairsANeverPromiseAnswerUsingTheEvidenceGroundedPolicy`.
  - **Verification**
    - First context run failed: `Cannot subclass final class EvidenceGroundedResponsePolicy` (AOP CGLIB); fixed by removing `final`.
    - `./gradlew test --no-daemon` → BUILD SUCCESSFUL, 722 tests, 0 failures, 0 errors, 0 skipped.
    - `./gradlew compileIntegrationTestJava bootJar` → BUILD SUCCESSFUL.

### Task: Parallel worktree plans (delegated)

- [x] Handoff plan executed in `.worktrees/mia-conversation-handoff`.
  - **Actions Applied**
    - Packet facts, idempotent BDC packet (typed `BdcHandoffPacket`, migration `025`), `CustomerHandoffEscalationPolicy`, and durable handoff copy.
  - **Verification**
    - `./gradlew cleanTest test --no-daemon` → 688 tests, 0 failures; `compileIntegrationTestJava` BUILD SUCCESSFUL.

- [x] WhatsApp operations plan executed in `.worktrees/mia-whatsapp-operations`.
  - **Actions Applied**
    - `inbound_debounce` table + scheduler/dispatcher release, `DealershipHours`/`Policy`/`Factory`, `DealershipResponsePlanner`, processor wiring.
  - **Verification**
    - `./gradlew test --no-daemon` → 696 tests, 0 failures.

- [x] Shadow evaluation plan executed in `.worktrees/mia-shadow-evaluation`.
  - **Actions Applied**
    - Shadow draft capture/interception (no send/outbox), kill switch, review API, migration `025`, synthetic 234-case evaluation report.
  - **Verification**
    - `./gradlew test --no-daemon` → 685 tests, 0 failures; H2 migration test PASS.

### Open / blocked (not fabricated)

- Migration collisions were resolved in the integrated Service worktree with unique forward-only changesets `025`–`027`; fresh integration tests are the verification gate.
- M0 evidence, M2 real golden-set review, M3 production datasource/config verification, M5 retrieval/latency evaluation, the 8:45 digest in the local CRM, M7 live rollout, monthly-sync production proof, and release/host verification remain open or external. A separate external CRM provider integration is out of scope; Ford-web is the locally operated CRM.
- Shadow plan Task 2 is implemented across Service and `Ford-web`: the inbox loads tenant-scoped unsent drafts and records accept/reject annotations only. Web API contract and frontend tests pass; this does not authorize or perform customer delivery.
- Production stays blocked on valid GitHub authentication and the protected rollout/host verification.

## Resume results (2026-10-17, integration verification — Colima fixed)

### Whole-plan audit (2026-10-18)

- [x] Re-run all local Service and catalog verification after generic naming, integration fixes, and parity-view additions.
  - Service `test`: BUILD SUCCESSFUL (fresh full run); evaluation writer test also passed after correcting the metric label.
  - Service `integrationTest`: 76 tests, 0 failures/errors, 3 expected env-gated snapshot skips.
  - Node: lint, unit (147/147), TypeScript build, and container contracts (5/5) passed.
  - Node PostgreSQL integration: after `npm run migrate` on a disposable `ford_test` database, 24/24 passed. An initial blank-database attempt failed from a test setup race; recorded in `tasks/lessons.md` and corrected by documenting migration-before-suite.
  - Service Python deployment/workflow contracts: 99/99 passed.
  - Restored catalog snapshot: 3/3 passed through the disposable `ford_read` SELECT-only role; INSERT denied; container removed.
- [x] Verify no Java source or test type/file is named with a product or MCP-specific prefix; retain only the immutable historical migration identifier `022_mia_catalog_read_views.sql`.
- [x] Add the requested repository-level `AGENTS.md` workflow and task-management rules; `git diff --check` passes.
- [x] Validate the development Compose configuration with a throwaway `GRAFANA_ADMIN_PASSWORD` value and syntax-check both catalog-sync and scheduler rollback Ansible playbooks.
- [ ] Run the actual development image health workflow; local Docker Hub image pull previously failed DNS at `auth.docker.io`, and GitHub Actions cannot be triggered while GitHub API access/auth is unavailable.
- [ ] M0/M2 real review corpus and dealership/policy inputs, M3 production datasource/config verification, M5 retrieval/latency evaluation, and live M7 operator evidence remain open as listed above.
- [x] Re-audit M5 evidence sources (2026-10-18): Ford-scraping `tests/unit/mcp-conversation-scoring.test.ts` contains synthetic scorer unit inputs; these do not provide reviewed questions paired with real retrieved document IDs/evidence and are not the required ~100-case retrieval corpus. The authoritative plan now states this explicitly to prevent counting unit examples as evaluation evidence. No retrieval or production readiness gate is claimed complete.
- [ ] Production publication/cutover remains blocked: GitHub token invalid/API unavailable and SSH fails locally with `No user exists for uid 501`; no host or production configuration was changed.

### Naming and reuse correction (2026-10-18)

- [x] Remove product-specific names from shadow-review implementation classes, config keys, evaluation labels, test fixtures, and new schema objects. Use `ShadowDraft`, `ShadowReviewGate`, `ShadowDraftService`, `conversation_shadow_draft`, and `FORD_SHADOW_REVIEW_*`.
- [x] Reuse `EvidenceGroundedResponsePolicy` for runtime response checks and shadow-corpus promise evaluation; remove the duplicate draft safety policy.
- [x] Targeted verification: `./gradlew test --offline --no-build-cache --no-daemon --max-workers=1 --rerun-tasks --tests '*ShadowDraft*' --tests '*ShadowReview*' --tests '*AiRegressionEvaluationTest' --tests '*EvidenceGroundedResponsePolicyTest'` → BUILD SUCCESSFUL (30 tests, 0 failures).
- [x] Java classes, test classes, runtime config, evaluation identifiers, and new schema objects use generic domain names. The existing Node migration `022_mia_catalog_read_views.sql` retains its historical identifier to preserve migration history.
- [x] Fresh full verification after renames/guard changes: Service `test` 799/799; `integrationTest` 76, 0 failures/errors, 3 env-gated catalog snapshot tests skipped; disposable restored-snapshot parity harness passed; Node unit 147/147, lint/build/container contract passed, Node PostgreSQL integration 24/24.
- [ ] Protected publication/production host checks remain blocked: `gh auth status` reports the configured token invalid and `gh api user` cannot connect to `api.github.com`; SSH to emme-1/emme-2 fails locally with `No user exists for uid 501`. No production change has occurred.

### Task: Fix Colima so Testcontainers integration runs

- [x] Free host disk and restart Colima `emme` with intact volumes.
  - **Actions Applied**
    - Pruned emme stopped containers (3.233GB), unused images, and 23.75GB build cache; kept all 134 volumes (`DOCKER_HOST=unix:///Users/miguelangeldelgadillozarate/.colima/emme/docker.sock`).
    - `colima ssh -p emme -- sudo fstrim -av` → reclaimed 47.6 GiB (emme datadisk 60G→16G; host 802Mi→45Gi free).
    - `colima stop -p emme` + `colima start -p emme` → fixed broken port forwarding (`http_code` 000→200).
  - **Verification**
    - `PgVectorKnowledgeIntegrationTest` 2/2 and `SchemaIntegrationTest` 1/1 ran on real containers, 0 skipped.
    - Design-worktree `./gradlew integrationTest --no-daemon` → BUILD SUCCESSFUL in 1m38s.

### Task: Full integration verification across every MÍA worktree

- [x] Design worktree (`ford-ai-service` main) — unit + integration green.
  - **Verification**
    - `./gradlew test` → 722 tests, 0 failures; `integrationTest` BUILD SUCCESSFUL.

- [x] Handoff worktree — integration green after fixing a stale assertion.
  - **Actions Applied**
    - `ConversationRepositoryIntegrationTest.java:220` — assert the truthful takeover copy (`...Puede escribirme por aquí.`); service already correct per plan (no response-time promise).
  - **Verification**
    - `./gradlew integrationTest --rerun-tasks` → 68 tests, 0 failures (1m16s); `test` 688, 0 failures.

- [x] WhatsApp worktree — integration green after fixing an exactly-once claim bug.
  - **Actions Applied**
    - `JdbcInboundDebounceRepository.claimDue` — single atomic `WITH due AS (SELECT ... FOR UPDATE SKIP LOCKED) DELETE ... USING due RETURNING ...`.
    - `JdbcInboundDebounceRepositoryIntegrationTest` — `@BeforeEach` truncate (shared static container left rows behind, so the global claim ate leftovers).
  - **Verification**
    - `./gradlew integrationTest --rerun-tasks` → 71 tests, 0 failures (1m17s); `test` 696, 0 failures.

- [x] Shadow worktree — app context fixed, integration green.
  - **Actions Applied**
    - `DefaultConversationTurnProcessor` — `@Autowired` on the 9-arg shadow-mode constructor (two constructors were confusable → `No default constructor found` → context load failure).
  - **Verification**
    - `./gradlew integrationTest --rerun-tasks` → 68 tests, 0 failures (1m14s); `test` 685, 0 failures.

- [x] Catalog repo (`Ford-scraping`) — lint/build/unit/integration green.
  - **Actions Applied**
    - Started throwaway `pgvector/pgvector:pg16` (`ford-test-pg`), created `ford_test`, enabled `vector`+`pg_trgm`, ran `npm run migrate`, then removed the container (no volumes).
  - **Verification**
    - `npm run lint` clean; `npm run build` clean; `npm run test:unit` 278/278; `npm run test:integration` 18/18 (7 files).

## Task: Add a protected MÍA production release dispatcher

- [x] Add executable `ford-ai-service/.mise/tasks/deploy/release-all` that requires explicit Service, Ford-scraping, and Ford-web versions; checks clean `main`, GitHub synchronization/authentication; and validates all three inputs before dispatching the protected release coordinator for all three repos.
- [x] Document usage and state clearly that the task queues `.github/workflows/release-all.yml`; it does not deploy over SSH or bypass approval, provenance, parity, readiness, and rollback controls.
- [x] `bash -n ford-ai-service/.mise/tasks/deploy/release-all`, `node --test scripts/mise-contract.test.mjs` (8/8), `python3 -m unittest discover -s ford-ai-service/scripts/tests -p 'test_release_all*.py'` (34/34), and `git diff --check` pass. The release helper selects all three repositories in the intended order (scraper, Service, Web), requires one SemVer per selected target, validates exact tagged source/CI identity, and stops sequential dispatch after a failed child. Mise task listing was attempted but blocked because `mise.dev.toml` in this temporary worktree is untrusted; no trust setting was changed. Current local main is dirty and GitHub auth is invalid, so the dispatcher did not run and no production release was dispatched.
