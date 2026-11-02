# Production Rollout Readiness

> **Historical snapshot (2026-10-13; superseded for current cutover):** This document captures the earlier MCP-based production topology and must not be used as a current inventory or deployment procedure. The active MÍA/Spring AI cutover checklist is [`tasks/todo.md`](../../tasks/todo.md). Current source removes the MCP runtime; production MCP resources remain until the protected Service + scraper + Ford-web release, both-host verification, and rollback gates pass. Preserve this snapshot as historical evidence only.

Status: **blocked from public production cutover**. The isolated `emme-crm-ui` Pages origin may be deployed and verified independently. Do not release Ford Service to emme-1, change public DNS, or switch customer traffic until every blocking runtime gate below is cleared and rechecked.

## Ford catalog and database cutover readiness — 2026-11-01

**Current decision: no production deployment, migration, snapshot load, or tool enablement.** This section is the active catalog-specific gate sequence; the broader repository, Vault, ingress, host-capacity, and protected-environment gates remain listed below and must also pass.

### Evidence checked locally

- Local feature commit: `0ed1cf5` (`feat: add schema-safe Ford catalog search`). It remains local to the feature branch; this session did not push or merge it. No deployment was attempted.
- Fresh local verification on 2026-11-02: 663 Gradle unit tests passed, 88 PostgreSQL integration tests passed, and 10 catalog exporter script tests passed. These cover local logic only, not production state.
- Approved candidate archive exists locally and its SHA-256 matches the approved value: `a1fe738999b3d1148d70d8cb89b4b8e998bfcdc3f7f6304570f06e8c74c1106d`.
- A fresh isolated restore of the approved archive (SHA-256 still matches `a1fe738999b3d1148d70d8cb89b4b8e998bfcdc3f7f6304570f06e8c74c1106d`) reproduced the exporter refusal. Row counts match, but `ford_capture.retrieval_document_manifest` expected `e9896d550932a028a0ec52e46e08610dbcf2c6b0a2edc0a50e850ad08a3d6070`, actual `7c63aae0a8dddf7700ef7e620761c08fa8256db875b50363d3cebe5f7e37ab48`; `ford_read.catalog_documents_v1` expected `b92fe6dff8c4e7fee81f31f072abc441e964aa6f2792ff8b7e4efbdc03836ad9`, actual `00d06c384effa7b8bb0bb26ce64b8609bcd7db444fba8846ef3646a017d77853`. Exporter refused and wrote no SQL. Preserve archive and expected hashes; source/data owner must resolve this integrity conflict.
- Default Docker context pointed to stopped Colima profile `emme`. After approval, Testcontainers and the archive restore ran through existing local `testbox`; no existing source container, shared database, or remote host was modified. Do not change archive/hash values to clear the gate.
- The disposable restore reported relocatable `vector` 0.8.7 and `pg_trgm` 1.6, both owned by `ford_catalog_admin`. Production extension ownership/relocation permission, production Liquibase history, backups, and target object inventory were not checked.
- The committed defaults keep `FORD_CATALOG_TOOLS_ENABLED=false`. Integration tests prove `ford_metadata`/`ford_catalog` placement, reader-denied metadata access, and a present, empty `public` schema. These tests are not production evidence.
- Unrelated local edits remain unstaged and uncommitted. Do not include them in release artifacts.
- `docs/superpowers/plans/2026-11-01-ford-database-cutover-release.md` was aligned on 2026-11-02 to keep built-in `public` present and empty. Its `ford_service` retirement step remains future-only and needs separate approval after successful acceptance; this readiness work does not authorize it.

### Live read-only preflight — 2026-11-02

SSH checks to `emme-1` and `emme-2` and SQL probes on `emme-2` were read-only; SQL ran inside `BEGIN READ ONLY` and ended with `ROLLBACK`. No secrets were printed. These findings are a stop signal, not authorization to reconcile production in place.

- Both hosts are active: `emme-1` runs Ford Service blue/green containers; `emme-2` runs the PostgreSQL/Redis stack and Ford scraping/MCP services. Root disk was 69% used (9.5 GB free) on emme-1 and 85% used (4.7 GB free) on emme-2.
- Both running Ford Service containers report database name `ford_service`, unset `currentSchema`, and `FORD_CATALOG_TOOLS_ENABLED=false`.
- On emme-2, database `ford_service` exists; database `ford` does not. Databases also include `ford_catalog_test` and `postgres`.
- `ford_service` currently has schemas `ford`, `ford_catalog`, `ford_graph`, `ford_read`, and `public`. Preliminary relation counts: `ford` 67 tables/174 indexes/10 sequences; `ford_catalog` 12 tables/4 views/4 indexes; `ford_graph` 3 tables/7 indexes/1 sequence; `ford_read` 19 views; `public` 58 tables/137 indexes/2 sequences. This is not yet the required full object/dependency/ownership inventory.
- Extensions in `ford_service`: `vector` 0.8.6 and `pg_trgm` 1.6 are relocatable but live in `public`, owned by role `ford`; `pgcrypto` 1.3 also lives in `public`; `plpgsql` is in `pg_catalog`. This differs from the approved design and disposable restore (which had `vector` 0.8.7 and extension owner `ford_catalog_admin`).
- `ford_service` has Liquibase ledgers in both `ford` (21 changesets) and `public` (33 changesets). Neither ledger contains changeset IDs `025-ford-metadata-schema` or `026-ford-extension-schemas`. The running database therefore does not match the assumed single-ledger migration baseline. Do not run 025/026 until the ledger provenance/checksums and app-version compatibility are reconciled on a restored clone.
- Confirmed migration incompatibility: changeset 025 explicitly rejects any listed relation found outside `public`, `ford_metadata`, or system schemas. Live DB has `ford.ai_execution` and `public.ai_execution` (and many other app-table duplicates across `ford`/`public`), so 025 will raise `Relation ai_execution exists in an unreviewed schema` before it can complete. Candidate startup with Liquibase enabled will fail against this baseline; do not bypass the guard or change the live search path to hide it.
- Second extension incompatibility: live `pgcrypto` 1.3 is relocatable but remains in `public`; changeset 026 relocates only `vector` and `pg_trgm`, then requires `public` to contain zero objects. Even after a separately verified 025 reconciliation, 026 will fail its public-object audit unless an approved migration relocates `pgcrypto` to an approved application schema and validates its dependents.
- The live `ford` role is superuser, CREATEDB, CREATEROLE, REPLICATION, and BYPASSRLS. This is not an acceptable least-privilege migration/runtime role for the approved deployment design. `ford_catalog_reader` exists and is non-superuser, but its effective grants were not yet audited.
- `ford_catalog_test` contains `ford_capture`, `ford_read`, and `public`; it has `vector` 0.8.6 and `pg_trgm` 1.6 in `public`, and no Liquibase ledger tables. It is not evidence that the production snapshot is ready.
- Deploy inventory `.mise/state/deploy/inventory.yml` is absent, so the repository's Ansible deploy entrypoint is not ready from this workspace. The attempted `postgres` role login failed because that role does not exist; no changes followed.

**Live-preflight result: BLOCKED.** Do not deploy, migrate, snapshot-load, enable tools, alter extensions, or clean schemas on either host. Current changeset 025 is proven incompatible with the live `ford` schema and would abort Liquibase; bypassing it risks app outage and split data. Resolve source hash mismatch, reconcile duplicated schema data and both ledgers on a verified restore, establish least-privilege roles, and rehearse corrected changesets before any production change. emme-2 disk headroom is also a release concern; do not delete data to address it without separate review.

### Ordered release plan and gates

1. **Resolve source integrity — BLOCKED.** Source/data owner investigates both mismatched hashes against the approved archive and manifest. Preserve archive bytes and expected hashes. Resume only when a fresh exporter run passes all database identity, relation inventory, row count, and content-hash checks. Never hand-edit hashes to clear this gate.
2. **Review release scope and source identity.** Review commit `0ed1cf5`, obtain required code review, push/merge only through the existing protected release path, and record the exact source SHA and immutable image digest. Keep unrelated workspace edits out. Existing repo/workflow/environment gates in this document still apply.
3. **Production read-only preflight — PARTIAL; BLOCKED.** The 2026-11-02 checks above confirmed target DB/schema/extension/ledger discrepancies on emme-2, but did not establish full object dependencies, grants, checksums for all ledger rows, data counts, or migration-role permissions. Complete that audit through an authorized DBA; preserve read-only mode and do not guess which ledger is authoritative.
4. **Backup and restore proof — NOT RUN.** Take the approved external backup, hash it, and restore it into an isolated production-like PostgreSQL instance. Verify the backup contains the expected application data and migration history. No production writes before restore proof.
5. **Migration rehearsal — NOT RUN.** Apply the exact committed changesets to the restored copy. Verify 025 preserves/moves reviewed app objects and Liquibase history to `ford_metadata`; 026 relocates `vector` and `pg_trgm` to `ford_catalog`; all catalog relations use the approved schema; reader can select only approved catalog relations and cannot use/read metadata; `public` remains present and contains no application/extension objects. Any unknown object, permission error, checksum conflict, data loss, or need for `CASCADE` stops the release.
6. **Snapshot generation and load — BLOCKED.** Only after Gate 1 passes, generate the immutable SHA-identified snapshot changeset. Review it, test repeatability/rollback on a disposable copy, and verify exact row counts/content hashes and unchanged `ford_metadata`. Do not load the currently mismatched candidate.
7. **Production deployment with tools off — BLOCKED.** After all prior gates, backup proof, migration compatibility review, required protected approvals, CI, Vault/runtime-secret, image, health, and rollback checks pass, deploy the approved immutable artifact with `FORD_CATALOG_TOOLS_ENABLED=false`. Apply Liquibase only through the approved migration owner. No DNS, Cloudflare, routing, scraper cadence, or customer-facing scraper changes.
8. **Post-deploy verification.** Confirm database/schema identity, applied Liquibase checksums, extension locations, empty-but-present `public`, metadata preservation, snapshot counts/hashes, reader grants/denials, health, and synthetic exact-model/trim/offer/citation/no-match behavior. Keep tools off while checking.
9. **Explicit enablement and observation.** Enable catalog tools only after all gates pass and a release owner approves. Canary and monitor. On any integrity, ACL, health, or behavior failure, disable the feature and use the approved application rollback plus verified external database restore plan; do not automatically reverse Liquibase migrations.

### Stop conditions

- Either approved source hash still differs, or source identity/counts are not independently verified.
- Production Liquibase history/checksums, 025 status, extension ownership/permissions, or current public-object inventory are unknown or conflict with the rehearsal.
- Backup cannot be restored; migration breaks active-version compatibility; application data changes unexpectedly; or any step requires `CASCADE` or dropping built-in `public`.
- `ford_catalog_reader` can access `ford_metadata`, private capture/provenance, or write catalog data.
- Any protected release, Vault, host-capacity, image, health, or rollback gate below remains open.

**Current restart point:** Gates 1 and 3. Resolve both candidate content-hash mismatches and reconcile the live `ford_service` schema/dual-ledger baseline on a verified restore. Production remains untouched until all gates pass and approvals are recorded.

Checked on 2026-10-13 from the local workspace, authenticated GitHub CLI session, read-only SSH sessions, and read-only Cloudflare Pages API/CLI queries. No secret values were read or recorded.

## Verified repository and GitHub state

| Check | Result |
| --- | --- |
| Ford Service GitHub repository | `migangdelzar/Ford-uruapan`, private, default branch `main`. |
| Ford-web GitHub repository | `migangdelzar/Ford-web`, public, default branch `main`. |
| Ford-scraping GitHub repository | Public `migangdelzar/Ford-scraping`; branch `chore/platform-secret-management` is pushed and is currently the default branch. A `main` branch does not exist on GitHub yet. CI run `38351439105` passed after adding the required safe `FORD_BASE_URL` to the migration job. |
| Workflows on GitHub | Ford Service and Ford-scraping CI now run on their pushed feature branches. Ford-web CI and Pages preview both passed earlier on its feature branch. Ford-scraping does not yet have a release workflow, and the release-all coordinator is not implemented. |
| Ford Service deployment environments | `talented-purpose / production` exists without protection rules; the exact `production` environment required by the release workflow is not configured. |
| Ford-scraping deployment environments | No GitHub environments are configured. |
| Ford-web deployment environments | Only `pages-preview` is configured. The protected `production` environment and its smoke URL are still not configured. |
| Ford Service private action access | Actions private-repository access level is `none`. Public Ford-scraping cannot call a private action regardless; shared OCI deployment logic needs a reviewed public home before Ford-scraping can use it. |
| GitHub CLI | Authenticated as `migangdelzar`; repository metadata and the preview/preflight environments are configured. Production environments and protections remain outstanding. |
| Cloudflare Pages | Legacy project `ford-web-ui` remains at `ford-crm-ui.pages.dev`; separate replacement project `emme-crm-ui` was created for `emme-crm-ui.pages.dev` with independent deployment history. Both are retained pending verification through the Spring proxy. |

## Latest CI status and ordered remaining work

This record is a readiness snapshot, not an authorization to deploy. The release plan in `docs/superpowers/plans/2026-10-12-github-release-pipelines.md` now sequences remaining work as CI/repository prerequisites, per-repository release workflows, coordinator controls, then production readiness.

| Area | Latest evidence | Remaining action |
| --- | --- | --- |
| Ford-scraping CI | Run [`38351439105`](https://github.com/migangdelzar/Ford-scraping/actions/runs/38351439105) passed for commit `b414810f1cc8dde075ee81a124b19e621b28f771`, including migration and PostgreSQL integration checks. | Establish and protect `main`; reduce the known lint backlog and ensure all integration tests pass before image publication. Create its release workflow after shared-action availability is solved. |
| Ford Service CI | Rerun [`38444812639`](https://github.com/migangdelzar/Ford-uruapan/actions/runs/38444812639) passed filesystem Trivy, Java quality/unit, PostgreSQL integration, deterministic evaluation, secret scan, and observability. Its container gate then failed because GHCR rejects uppercase repository names. | Normalize the GHCR image repository to lowercase. Local workflow contract tests pass; hosted full CI rerun is pending. |
| Ford Service VM preflight | Read-only preflight [`38353172875`](https://github.com/Ford-uruapan/actions/runs/38353172875) passed for both existing hosts. | This only verifies access/health; it is not deployment evidence. |
| Ford-web | Earlier CI [`38348983403`](https://github.com/Ford-web/actions/runs/38348983403) and Pages preview [`38348983417`](https://github.com/Ford-web/actions/runs/38348983417) passed. | Resolve production ownership conflict between Cloudflare Git integration and the Actions workflow, then configure protected production environment after other gates. |
| Shared OCI action | Ford Service action access is `none`; Ford-scraping is public and cannot consume a private action. | Review/publish the action in an approved public repository and pin consumers to a full commit SHA. Keep secrets out of the action repository. |
| Release-all | Coordinator workflow not implemented; no scoped GitHub App dispatch credential configured. | Implement exact-SHA dispatch/status reporting, then configure minimal three-repository permissions and verify in a non-production test. |

The public production state remains **not end-to-end release-ready**. Runtime Vault consumption, the active ingress switch point, peak blue-green resource headroom, and live schema-migration compatibility remain unverified. The isolated `emme-crm-ui` origin deployment recorded below does not change customer traffic; no Ford Service release or public route switch has been performed.

## Verified local deployment shape

- Terraform budgets two VMs at 1 OCPU and 6 GB each, with a configured total ceiling of 2 OCPU and 12 GB. A second VM is outside that configured budget.
- Ford Service Compose binds the app to `127.0.0.1:8080`; checked-in files do not define the public Ford Service ingress or a switchable reverse proxy.
- MCP Compose runs Cloudflare Tunnel with host networking and publishes MCP on local port 3001. The active Tunnel ingress mapping is not in the checked-in files.
- Local blue-green Compose and Ansible paths now retain both image digests, health-check candidates before cutover, switch a stable local Nginx listener, verify the switched route, and restore the prior route on failure. The one-time migration from direct app/MCP port binding to the proxy requires a brief listener handoff; later releases switch Nginx upstreams without changing the listener. These paths have not run against a VM.
- Read-only host snapshots found the expected existing services and no second VM was created. emme-1 had about 3.6 GiB available RAM and 9.4 GB disk free; emme-2 had about 4.2 GiB available RAM and 6.0 GB disk free. These are single idle-time snapshots, not peak measurements, and do not prove same-host candidate capacity. Docker image storage was about 3.3 GB on emme-1 and 8.5 GB on emme-2.
- Ford Service scheduled work now uses PostgreSQL advisory locks for catalog sync, outbox delivery/recovery, and incomplete-publication recovery; outbox record leases and idempotent checkpoint cleanup remain in place. The PostgreSQL integration test starts old and candidate job instances and verifies one effective execution for sync, delivery, lease recovery, and event resubmission. It does not measure production resource headroom or prove provider behavior during a host crash.
- MCP migration now runs as a one-shot task before service startup in both the ordinary Ansible path and the blue-green path; normal container startup no longer invokes migrations. The migration has not been run against a live production database.
- Spring Liquibase still runs during candidate Spring startup. No production database migration ledger or candidate changeset set was available for compatibility review, so any app release that introduces a schema change must remain blocked until the changeset is shown to be backward-compatible with the active version.
- Read-only SSH found the Ford Service app and Collector on emme-1, and MCP, Cloudflare Tunnel, Ollama, PostgreSQL, and Redis on emme-2. Ford Service `/api/v1/health` and `/actuator/health` returned HTTP 200; `https://ford-crm-ui.pages.dev/` redirects to `/login` and then returns HTTP 200; the Pages `/api/v1/health` route returns HTTP 200.
- The authenticated public MCP smoke passed: initialize, `tools/list` (12 tools, including the required catalog tools), and a read-only `search_vehicles` call. The response contained source URLs. The test made no writes and printed no tokens or returned customer data.
- The running Ford Service and MCP app containers report no `FORD_SECRETS_PROVIDER` setting and zero `FORD_VAULT_SECRET_OCID_*` references. The local Spring Vault loader is not proof the deployed app uses Vault. The deployment's data/Collector runtime-secret path is also not verified. Keep production Actions disabled until the runtime consumers are migrated and validated.
- The current Pages production deployment is associated with `feat-ford-crm-auth-integration`; a production Actions release using `--branch main` will not match until ownership/settings are reconciled. No production Pages deployment was made.
- Ford-web's Wrangler config now targets project `ford-web-ui`, whose Pages hostname is `ford-crm-ui.pages.dev`; the origin is `https://ford-origin.emme.business`. The `pages-preview` GitHub environment successfully deployed a preview and passed UI plus `/api/v1/health` checks. Production still needs its own environment credentials and `FORD_WEB_SMOKE_URL` variable.
- **2026-10-14 — Pages origin replacement project:** Cloudflare created `emme-crm-ui` (`emme-crm-ui.pages.dev`) as a new Pages project. Cloudflare Pages does not transfer deployment history between projects. The former project `ford-web-ui` (`ford-crm-ui.pages.dev`) remains available; no DNS or public-domain changes were made. See `cloudflare-pages-origin-cutover.md` for staging, verification, and rollback.
- **2026-10-14 — emme Pages origin deployed:** Ford Web Actions run [38588412862](https://github.com/migangdelzar/Ford-web/actions/runs/38588412862) deployed verified Ford Web commit `c8ee6d38ced0b5a4ec87471c0564ffb1c673bd2e` to `emme-crm-ui`; deployment `0056b296-f347-42ad-b73e-31fc735925d1` passed checks for `/`, `/login`, `/dashboard`, and a fingerprinted asset. The Spring upstream and public browser origin were not changed by this deployment. Keep `ford-web-ui` and `ford-crm-ui.pages.dev` available until Spring-proxied verification and the rollback window are complete.

## Gates before enabling production

- [ ] Establish Ford-scraping `main` and branch protections using a reviewed, non-destructive transition; retain the current feature branch and history.
- [x] Confirm the Trivy filesystem scan passes on GitHub Actions with `limit-severities-for-sarif: true`; HIGH/CRITICAL findings remain blocking. Run `38444812639`.
- [ ] Confirm container build, image scan, and full Ford Service CI pass after lowercasing the GHCR repository name.
- [ ] Publish the shared action to an approved public home and pin Ford-scraping to its reviewed full SHA; then publish the Ford-scraping release workflow.
- [ ] Publish reviewed release workflow changes to the appropriate default branches; local working-tree workflows cannot run on GitHub yet.
- [ ] Configure protected `production` environments and required reviewers in all three target repositories; verify the account plan supports those rules for each repository’s visibility.
- [ ] Configure the narrowly scoped GitHub App required by release-all and verify target workflow dispatch without approving a production deployment.
- [ ] Reduce Ford-scraping lint backlog and require all PostgreSQL integration tests to pass before image publishing.
- [x] Authenticate Wrangler and verify Pages project ownership and current Git integration. Wrangler access is confirmed; Git integration is currently the production owner and conflicts with Actions. Select one owner and align the production branch before enabling Actions deploys.
- [ ] Read the live Ford Service ingress and MCP Tunnel origin; configure and test a stable upstream switch point without changing public URLs.
- [ ] Measure peak host resources with Ollama and existing services active; confirm blue-green candidate headroom on both OCI VMs. Read-only idle snapshots are recorded above.
- [x] Prove scheduled/background work remains single-effect while old and candidate Ford Service versions overlap in PostgreSQL integration tests.
- [ ] Verify Spring, data, MCP, and Collector all retrieve runtime secrets from the intended Vault/runtime path.
- [ ] Verify the backup command and catalog migration run successfully from the production deployment context.
- [ ] Run an approved per-repository rollout and rollback exercise, then a release-all partial-failure exercise; retain evidence and exact source/artifact identities.

## Recheck record

Append the date, command or evidence source, result, and operator for each gate. Never include credential values, private keys, API tokens, or customer data.

- **2026-10-13 — Ford-web Actions preview:** Commit `b64fbcee44bb5c173b7375a33f962fa8d3a4b27a`; CI run `38348983403` and Pages preview run `38348983417` both succeeded. The preview deployed to `https://actions-e2e-preview.ford-crm-ui.pages.dev`; the workflow found deployment `f46477f8-25b6-4821-be35-c08c2895dea7` through the Cloudflare API and passed UI and API health checks. Production was unchanged.
- **2026-10-13 — VM connectivity preflight:** Ford Service run `38348341955` succeeded on branch `chore/platform-secret-management`; GitHub Actions connected to both existing VMs and checked app/MCP, PostgreSQL, and Ollama health. It was read-only and did not deploy.
- **2026-10-13 — local release checks:** Ford Service `./gradlew check --no-daemon --no-configuration-cache` and 23 focused action/Ansible contract tests passed. Ford-scraping build and 274 unit tests passed locally. Neither local result is a GitHub Actions run.
- **2026-10-13 — branch pipelines:** Ford-scraping commit `b414810f1cc8dde075ee81a124b19e621b28f771` passed GitHub CI run `38351439105`, including migrations and PostgreSQL integration tests. Its prior run `38351293296` exposed missing `FORD_BASE_URL`; the deployment contract now asserts that configuration. Ford Service commit `94b42716a98b008bdf4ba82261d98d18bde4d7bb` passed its quality/unit job in run `38351294011`; PostgreSQL integration and deterministic AI evaluation were still running at the time of this check. The separate read-only emme preflight run `38351293719` passed for both existing VMs.
- **2026-10-13 — Trivy remediation check:** Ford Service run `38444812639` passed the filesystem vulnerability scan after adding `limit-severities-for-sarif: true`; Java, PostgreSQL integration, deterministic evaluation, secret, and observability checks also passed. The same run then failed container build because GHCR image tags included uppercase `Ford-uruapan`. The reusable workflow now lowercases the repository name and has a passing local contract test; a hosted rerun is pending. This is not production deployment validation.
