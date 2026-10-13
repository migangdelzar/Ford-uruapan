# Production Rollout Readiness

Status: **blocked from production enablement**. Local workflow and deployment implementation may proceed, but no production deploy job may run until every blocking gate below is cleared and rechecked.

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
| Cloudflare Pages | Project name is `ford-web-ui`; its Pages hostname is `ford-crm-ui.pages.dev`. The active Git integration deploys production from `feat-ford-crm-auth-integration`, while the Actions release workflow targets `main`. A feature-branch Pages preview now deploys and passes UI/API smoke from GitHub Actions. Production ownership still needs to move to one aligned path before release enablement. |

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

## Gates before enabling production

- [ ] Create or identify the intended GitHub repository and remote for Ford-scraping; confirm owner and visibility before pushing its local branch.
- [ ] Publish reviewed release workflow/action changes to the appropriate default branches; local working-tree workflows cannot run on GitHub yet.
- [ ] Configure protected `production` environments and required reviewers in all three target repositories; verify the account plan supports those rules for each repository’s visibility.
- [ ] Allow the three caller repositories to use the private Ford Service composite action.
- [x] Authenticate Wrangler and verify Pages project ownership and current Git integration. Wrangler access is confirmed; Git integration is currently the production owner and conflicts with Actions. Select one owner and align the production branch before enabling Actions deploys.
- [ ] Read the live Ford Service ingress and MCP Tunnel origin; configure and test a stable upstream switch point without changing public URLs.
- [ ] Measure peak host resources with Ollama and existing services active; confirm blue-green candidate headroom on both OCI VMs. Read-only idle snapshots are recorded above.
- [x] Prove scheduled/background work remains single-effect while old and candidate Ford Service versions overlap in PostgreSQL integration tests.
- [ ] Verify Spring, data, MCP, and Collector all retrieve runtime secrets from the intended Vault/runtime path.
- [ ] Verify the backup command and catalog migration run successfully from the production deployment context.

## Recheck record

Append the date, command or evidence source, result, and operator for each gate. Never include credential values, private keys, API tokens, or customer data.

- **2026-10-13 — Ford-web Actions preview:** Commit `b64fbcee44bb5c173b7375a33f962fa8d3a4b27a`; CI run `38348983403` and Pages preview run `38348983417` both succeeded. The preview deployed to `https://actions-e2e-preview.ford-crm-ui.pages.dev`; the workflow found deployment `f46477f8-25b6-4821-be35-c08c2895dea7` through the Cloudflare API and passed UI and API health checks. Production was unchanged.
- **2026-10-13 — VM connectivity preflight:** Ford Service run `38348341955` succeeded on branch `chore/platform-secret-management`; GitHub Actions connected to both existing VMs and checked app/MCP, PostgreSQL, and Ollama health. It was read-only and did not deploy.
- **2026-10-13 — local release checks:** Ford Service `./gradlew check --no-daemon --no-configuration-cache` and 23 focused action/Ansible contract tests passed. Ford-scraping build and 274 unit tests passed locally. Neither local result is a GitHub Actions run.
- **2026-10-13 — branch pipelines:** Ford-scraping commit `b414810f1cc8dde075ee81a124b19e621b28f771` passed GitHub CI run `38351439105`, including migrations and PostgreSQL integration tests. Its prior run `38351293296` exposed missing `FORD_BASE_URL`; the deployment contract now asserts that configuration. Ford Service commit `94b42716a98b008bdf4ba82261d98d18bde4d7bb` passed its quality/unit job in run `38351294011`; PostgreSQL integration and deterministic AI evaluation were still running at the time of this check. The separate read-only emme preflight run `38351293719` passed for both existing VMs.
