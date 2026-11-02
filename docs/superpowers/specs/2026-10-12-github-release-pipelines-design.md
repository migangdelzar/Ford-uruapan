# GitHub Release Pipelines Design

| Field | Detail |
| --- | --- |
| Date | 2026-10-12 |
| Status | Draft for user review; implementation not started |
| Scope | `ford-uruapan`, `ford-scraping`, and `ford-web` |

## Goal

Give each repo a tested, protected path to release its own production service, provide one controlled “release all” entry point, and run the Ford catalog refresh at the start of each month.

## Approved choices

- A successful `main` build may proceed to a production deployment, but deployment pauses for approval in that repository’s protected GitHub `production` environment.
- Production uses a health-gated rollout per repo: one approval before deployment, a candidate version validated before traffic moves, and a retained prior version for rapid traffic rollback. OCI services use blue-green containers on the existing host when measured capacity allows; Pages uses its immutable deployment versions.
- Each repo normally deploys only its own service. A separate “release all” workflow coordinates selected releases across repos.
- Ford-scraping needs a production scheduler; it does not currently have one in the checked-in OCI deployment.
- The scheduler runs on day 1 at 12:00 in `America/Mexico_City`.
- An MCP catalog migration runs once before MCP and scheduler containers are updated. Normal container startup does not run migrations.
- Ford Service keeps Spring Liquibase’s existing startup migration behavior, with deployment health checks gating completion.
- Runtime application, database, provider, messaging, and observability credentials stay out of GitHub Actions. Actions get only deployment credentials and narrowly scoped integration tokens.

## Current state

- Ford Service already has reusable Java, security, observability, and container workflows. Its `reusable-deploy.yml` is a placeholder.
- Ford-scraping has Node build, test, lint, and `migrate` commands but no GitHub Actions workflow. Its current Compose setup runs a scheduler, while OCI Ansible defines only the MCP deployment target.
- The MCP Compose service runs `node dist/src/cli.js migrate` every time it starts, then starts `mcp-http`.
- Ford Service’s Spring application has Liquibase changelogs and runs migrations during application startup.
- Ford-web has build, lint, tests, and Pages Functions checks, but no GitHub Actions workflow. Its Wrangler config targets Cloudflare Pages.
- The OCI Vault runtime migration is incomplete: the Spring loader exists, but data, MCP, and Collector do not yet retrieve secrets directly from Vault. Current Ansible deployment also renders secret-bearing runtime configuration from the deploy controller.

## Architecture

### Per-repository pipelines

Each repo owns its application checks and its `main` release caller. Pull requests run verification only. A successful `main` run builds/tests and publishes an immutable release artifact. A production deploy job then references the protected `production` environment; GitHub waits for approval before exposing that environment’s secrets or running the deployment job.

Ford Service remains the home of shared OCI deployment mechanics. Each target repository owns its production deployment job and attaches its own protected `production` environment; that job invokes a version-pinned composite action hosted in Ford Service. The action contains the common Ansible/blue-green deployment logic, while each repo keeps its build and project-specific checks. This keeps the approval gate and environment-scoped deployment secrets in the target repo. GitHub reusable workflows cannot receive an environment from the caller; an environment declared inside the called workflow would use that workflow repository’s environment secrets. A composite action runs inside the caller’s environment-protected job and avoids moving approvals or credentials to Ford Service. See [reusable workflow environment and secret behavior](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows).

Ford-web owns its Pages deployment job and production environment and uses the Cloudflare Wrangler deployment action because Pages is a different platform and a generic OCI deploy abstraction would add indirection without reuse.

Cross-repo callers must be allowed to use the shared private workflow repository. Pin callers to a reviewed release tag or commit SHA rather than a moving branch. GitHub permits shared private reusable workflows only when the called repository’s Actions access policy allows the caller; called workflows cannot elevate the caller’s token permissions. See [reusable workflow access and permissions](https://docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations).

### Release-all coordinator

Add a `workflow_dispatch` coordinator in Ford Service. For each selected repo, it resolves the selected annotated SemVer tag, verifies the exact source commit and successful CI, then presents the identities before one protected coordinator approval. After approval, it dispatches selected workflows serially in dependency order: Ford MCP, Ford Service, Ford Web. Each target rechecks its release identity, immutable artifact, production readiness, and deployment health. The coordinator waits for a complete successful target run before dispatching the next. It records selected and skipped targets, SHAs, run links, artifacts, and per-repo outcomes. A failed target stops the sequence; already successful targets are not automatically rolled back.

Each target keeps its secrets in its own `production` environment. Direct per-repository deployments require a separate `manual-release-approval` environment first. The `production` environment retains its secrets and `main` branch policy but no required reviewer; coordinator-dispatched child workflows use a request ID and the verified GitHub App actor, so they proceed only after the single coordinator approval. The coordinator approval environment contains no deployment secrets.

The coordinator uses a GitHub App installation token limited to these repos and the permissions required to read release runs and dispatch workflows. A repository’s `GITHUB_TOKEN` is scoped to that repository; cross-repo dispatch therefore uses a narrowly scoped app token, not a personal access token. GitHub documents `workflow_dispatch` as an event that can trigger a workflow when created by a token. See [workflow trigger behavior](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).

The release-all dispatch accepts an explicit repo selection, records the exact selected versions and SHAs, and requires one coordinator approval before any target deploys. Only checked repositories run, always MCP → Service → Web. A failed or timed-out target stops later dispatches; successful earlier targets remain in place. Direct per-repository releases retain their independent approval through `manual-release-approval`. Rejecting either approval leaves production unchanged. If a candidate fails before cutover, traffic stays on the active version; if post-cutover checks fail, the workflow switches ingress back to the retained prior version and reports the failure. Neither automatic nor manual rollback reverses database migrations.

For OCI application traffic, use blue-green rather than percentage canary routing: start the candidate beside the active container on an alternate local port, validate it directly, then switch the stable local ingress to the candidate. Canary traffic splitting adds routing and metrics complexity, and no traffic-splitting layer appears in the checked-in deployment. Keep the prior container available through a short observation window. If post-switch smoke or health checks fail, automatically switch ingress back; an operator can also request rollback during the observation window. Do not claim zero downtime until the actual public ingress path has been verified. The checked-in Compose config binds Ford Service to loopback port 8080 and does not define an app proxy; the MCP tunnel targets its current local service. Implementation must identify the live routing configuration and add or reuse a stable switch point before enabling blue-green deployment.

Blue-green is one extra container on the existing VM, not one extra OCI VM. The checked-in Terraform budget allocates two 1 OCPU / 6 GB VMs, so another VM exceeds the configured Always Free budget. A second container still needs enough temporary CPU and memory headroom; measure the app and MCP hosts before enabling this path. Ford Service also has scheduled outbox, ingestion, and recovery jobs. Candidate startup must not run duplicate side effects: either prove the jobs are safe across instances with shared coordination or keep candidate workers disabled until promotion. Catalog scheduler stays single-instance and is replaced serially, not blue-green.

Each target workflow must deploy its own build’s immutable image digest or exact web commit SHA; it must not resolve a mutable `latest` tag at deploy time. Each target run summary records its artifact digest, migration status, and smoke-check result. The release-all summary links to those target summaries. If one target fails, the coordinator reports individual results; it does not try to undo completed deployments or database changes automatically.

### Service release lanes

| Repo / target | Verification and artifact | Production sequence |
| --- | --- | --- |
| Ford Service app | Existing Java checks and security gates; build, scan, attest, and publish the ARM64 image by immutable digest. | Wait for approval; back up before schema changes; apply only backward-compatible Liquibase expansion; start candidate beside active version with duplicate workers disabled or coordinated; validate candidate; switch ingress; automatically switch back if post-switch checks fail; retain old image/container through the observation window. |
| Ford-scraping MCP + scheduler | Add Node lint, build, unit/integration tests, and container scan; publish the ARM64 image by immutable digest. | Wait for approval; back up before catalog schema changes; run `node dist/src/cli.js migrate` once; validate candidate MCP on an alternate local port and switch the stable tunnel origin; replace the single scheduler serially; restore old MCP/scheduler image on failure without down-migrating. |
| Ford-web Pages | Run lint, tests, production build, API contract check, and Pages Functions build check. Deploy output from the selected commit. | Wait for approval; deploy with Wrangler to the existing Pages project; verify the public UI and same-origin API routes; retain the prior Pages deployment for rollback. |

The Ford-scraping scheduler is a single container on the existing MCP host and its Docker network, subject to confirming host capacity and configuration during implementation. It uses the repo’s existing timezone-aware scheduler with `SYNC_SCHEDULE=0 12 1 * *` and `SYNC_TIMEZONE=America/Mexico_City`. Keep it at one replica. The scheduler code already passes `noOverlap: true` to its cron adapter; deployment must not start overlapping scheduler replicas. Record each scheduled run’s start, completion, outcome, and changed-record counts through the existing container log path. Alert on failure or a missing monthly success; provide a manual `sync` recovery command rather than silently running a missed monthly job at an arbitrary time.

Run the catalog migration as a one-shot Ansible deployment task using the exact image being released and the existing Compose DB/network configuration. Then start the MCP service with only `mcp-http` as its command and add a scheduler service using the same image with the `scheduler` command. This preserves one migration owner and prevents every MCP restart or replica from trying to migrate concurrently.

Keep Spring Liquibase on application startup. A standalone migration-only Spring runner is not part of this design because the current app has no verified migration-only launch mode. The deploy workflow waits for app health before reporting success.

## Credentials and permissions

- GHCR publication uses the repository-scoped `GITHUB_TOKEN` with `packages: write`; no manually managed registry PAT.
- OCI host access uses a production-environment-scoped SSH deployment key and non-secret connection variables, unless a supported OCI workload-federation path is verified during implementation.
- Cloudflare uses a production-environment-scoped API token limited to Pages deployment for this project; the account identifier is a variable, not a secret.
- Release-all uses a GitHub App installation token scoped to the three repos and only the required Actions/Contents permissions.
- Do not copy runtime DB, MCP, model-provider, Meta, signing, or observability values into GitHub secrets. Keep those values in OCI Vault and load them from the runtime consumers.
- Keep production deployment disabled until Spring, data, MCP, and Collector all use their intended Vault/runtime retrieval paths and health checks pass. Test-only `ford-test-*` secrets are never used by production workflows.

GitHub Environments support reviewer gates and environment-scoped secrets, but required reviewers for private repositories depend on the account plan. Confirm repository visibility and plan before relying on this gate; if unavailable, keep production deployment manual and fail closed. See [GitHub deployment environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).

Before enabling the Pages workflow, inspect the Cloudflare project’s current Git integration. Use one production deployment owner: if GitHub Actions deploys Pages, disable the existing automatic Git integration to avoid duplicate production deployments.

## Rollout, migration, and rollback policy

- Migration changes are serialized with deployment concurrency per production database.
- Before applying changed DB migrations, use the existing backup path and require a successful backup result.
- Apply catalog migrations once before changing MCP/scheduler containers. Ford Service Liquibase currently runs at application startup; blue-green requires every automatic schema change at startup to be backward-compatible with both active and candidate versions. If that cannot be guaranteed, introduce a separately controlled expansion step before candidate startup.
- Prefer backward-compatible expand/contract schema changes. Keep destructive contract migrations in a later release after the rollback window closes. Never run down migrations as part of rollback.
- On candidate startup or health failure, leave active traffic on the old version. If checks fail after traffic switches, automatically point ingress back to the retained old version. An operator can request the same switch-back during the observation window. Database restoration is a separate, explicitly reviewed recovery operation using the existing backup/restore process.
- Record old/candidate artifact IDs, migration status, ingress target, failed checks, switch-back result, and final smoke result in each deployment summary. Keep the old container/artifact until the observation window closes and the new version is confirmed healthy.
- A manual catalog sync is a separate operator action from deployment. It runs the existing sync command once, reports its outcome and changed-record counts, and does not change the scheduled day-1 cadence.
- Emit an observable scheduler start/result record with run time, outcome, and changed-record counts, without logging customer data or credentials. Alert on a missing or failed monthly run.

## Security and reliability controls

- Use least-privilege job permissions and declare them per workflow/job.
- Use protected production environments, branch restrictions to `main`, one deployment concurrency group per service/database, and approval before environment secrets become available.
- Never deploy pull-request code or mutable tags to production.
- Generate per-repo release summaries containing repo, commit, artifact digest (for images), migration status, approver/deployment URL, and smoke-check result. Release-all links to each summary.
- Run post-deploy smoke checks before marking a release successful.
- Retain the previous immutable artifact and deployment metadata so traffic can return to the prior version during the observation window. Verify the active public ingress route and measure host capacity before enabling OCI blue-green rollout.
- Keep operator workflows narrow: release selected repos, explicitly switch traffic back to the prior app artifact, or run catalog sync now. Do not add standalone migration or database-restore buttons; migrations remain part of the gated release sequence, and restore stays a separately reviewed recovery procedure.

## Implementation gates and open checks

1. Verify all three GitHub repos can use the private shared composite action and that the account plan supports required reviewer gates for their visibility.
2. Create/install the narrowly scoped GitHub App for release-all workflow dispatch.
3. Finish production Vault retrieval for data, MCP, and Collector; remove GitHub/controller dependence on runtime secret values before enabling deploy jobs.
4. Confirm the MCP host has capacity and required DB/Ollama/network settings for one scheduler container.
5. Inspect Cloudflare Pages Git integration and select GitHub Actions as the sole production deploy owner.
6. Confirm the service and catalog migration backup commands work from the production workflow context.

## Acceptance criteria

- Pull requests in each repo run their verification lane and cannot deploy.
- A production release after a successful `main` build waits for approval and deploys only the selected repo’s immutable version.
- Direct per-repository deployments have one protected approval; release-all has one protected coordinator approval for the selected ordered rollout. Every candidate has health checks before traffic switches, clear failure details, automatic traffic switch-back on failed post-switch checks, and an operator rollback action during the observation window.
- Release-all selects exact versions, requests one approval, deploys only selected repos in dependency order, stops on the first failure, and records per-repo outcomes.
- Catalog migrations run once before the MCP/scheduler update; ordinary container restarts do not run migrations.
- One scheduler runs monthly on day 1 at 12:00 `America/Mexico_City`; overlapping executions are prevented and failures/missed runs are observable.
- Spring Liquibase migration completes during the approved app rollout and app health checks pass before success is reported.
- A failed candidate check leaves traffic on the old version; a failed post-switch check returns traffic to the old version. Neither path runs an automatic down migration.
- OCI blue-green deploy is enabled only after ingress routing, worker singleton behavior, and temporary host capacity are verified.
- An operator can run the catalog sync manually without changing the monthly schedule.
- Runtime credentials stay out of GitHub Actions; production deployment remains disabled until all runtime consumers are migrated to Vault.
- Cloudflare production deploy has one owner and verifies the site/API routes after deployment.

## References

- [GitHub reusable workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations)
- [GitHub deployments and environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
- [GitHub workflow triggers](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)
- [Cloudflare Pages direct upload with CI](https://developers.cloudflare.com/pages/how-to/use-direct-upload-with-continuous-integration/)
- [OCI workload identity federation token exchange](https://docs.oracle.com/en-us/iaas/Content/Identity/api-getstarted/token_exchange_grant_type_workload-id-federation.htm)
