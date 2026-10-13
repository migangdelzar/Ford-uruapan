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
- Production uses a health-gated rollout per repo: one approval before deployment, automated health checks after each change, and a human-triggered rollback to the prior immutable artifact if checks fail. OCI services deploy in-place; Pages deploys the selected commit.
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

Ford Service remains the home of shared OCI deployment workflows because it already contains reusable workflow infrastructure. Ford Service and Ford-scraping callers use the shared OCI deployment contract; their build and project-specific checks stay next to their source code. Ford-web uses the Cloudflare Wrangler deployment action because Pages is a different platform and a generic OCI deploy abstraction would add indirection without reuse.

Cross-repo callers must be allowed to use the shared private workflow repository. Pin callers to a reviewed release tag or commit SHA rather than a moving branch. GitHub permits shared private reusable workflows only when the called repository’s Actions access policy allows the caller; called workflows cannot elevate the caller’s token permissions. See [reusable workflow access and permissions](https://docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations).

### Release-all coordinator

Add a `workflow_dispatch` coordinator in Ford Service. For each selected repo, it resolves the latest successful verification run on `main`, takes that run’s exact source commit, and dispatches the repo’s existing release workflow with that commit. Each target workflow re-runs its release gates against the pinned commit before publishing/deploying. The coordinator waits for target runs and records the selected commits, run links, and per-repo outcomes. It does not implement another deployment path. Each target repo retains its own `production` approval and deployment log; the user approves each selected repo separately.

The coordinator uses a GitHub App installation token limited to these repos and the permissions required to read release runs and dispatch workflows. A repository’s `GITHUB_TOKEN` is scoped to that repository; cross-repo dispatch therefore uses a narrowly scoped app token, not a personal access token. GitHub documents `workflow_dispatch` as an event that can trigger a workflow when created by a token. See [workflow trigger behavior](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).

The release-all dispatch accepts an explicit repo selection and records the selected commit for each target before dispatch. It remains a coordinator: each selected repo pauses independently at its own production approval gate, and approval in one repo does not approve another. For an individual release, the operator can approve or reject the pending production deployment from that repo’s deployment review. Rejecting leaves the current production version in place. If a deployment fails its health gate, the workflow stops and reports the failed check; it does not automatically roll back. An operator can trigger a separate rollback workflow that selects the recorded previous immutable app image (or Pages commit) and runs the same deploy and health checks. The rollback action does not reverse database migrations.

Each target workflow must deploy its own build’s immutable image digest or exact web commit SHA; it must not resolve a mutable `latest` tag at deploy time. Each target run summary records its artifact digest, migration status, and smoke-check result. The release-all summary links to those target summaries. If one target fails, the coordinator reports individual results; it does not try to undo completed deployments or database changes automatically.

### Service release lanes

| Repo / target | Verification and artifact | Production sequence |
| --- | --- | --- |
| Ford Service app | Existing Java checks and security gates; build, scan, attest, and publish the ARM64 image by immutable digest. | Wait for approval; back up before a release that changes Liquibase changelogs; deploy the exact image with Ansible; Spring Liquibase applies pending changes at startup; require app health checks. |
| Ford-scraping MCP + scheduler | Add Node lint, build, unit/integration tests, and container scan; publish the ARM64 image by immutable digest. | Wait for approval; back up before a release that changes catalog migrations; run `node dist/src/cli.js migrate` once from the selected image on the OCI host; update MCP and the single scheduler process; verify MCP health and report the last monthly sync. |
| Ford-web Pages | Run lint, tests, production build, API contract check, and Pages Functions build check. Deploy output from the selected commit. | Wait for approval; deploy with Wrangler to the existing Pages project; verify the public UI and same-origin API routes. |

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

## Migration and rollback policy

- Migration changes are serialized with deployment concurrency per production database.
- Before applying changed DB migrations, use the existing backup path and require a successful backup result.
- Apply catalog migrations once before updating MCP and the scheduler. App Liquibase remains within app startup and is followed by a health gate.
- Prefer backward-compatible expand/contract schema changes. Do not automatically run down migrations during rollback.
- On application failure, allow a redeploy of the previous immutable image. Database restoration is a separate, explicitly reviewed recovery operation using the existing backup/restore process.
- Application rollback is an explicit operator action after a failed health gate; report the previous and candidate artifact identifiers, failing health checks, and selected rollback target in the deployment summary. The rollback itself must pass the normal health gate before it is reported successful.
- A manual catalog sync is a separate operator action from deployment. It runs the existing sync command once, reports its outcome and changed-record counts, and does not change the scheduled day-1 cadence.
- Emit an observable scheduler start/result record with run time, outcome, and changed-record counts, without logging customer data or credentials. Alert on a missing or failed monthly run.

## Security and reliability controls

- Use least-privilege job permissions and declare them per workflow/job.
- Use protected production environments, branch restrictions to `main`, one deployment concurrency group per service/database, and approval before environment secrets become available.
- Never deploy pull-request code or mutable tags to production.
- Generate per-repo release summaries containing repo, commit, artifact digest (for images), migration status, approver/deployment URL, and smoke-check result. Release-all links to each summary.
- Run post-deploy smoke checks before marking a release successful.
- Retain the previous immutable artifact and deployment metadata so an operator can explicitly roll back after a failed health check. For in-place Compose releases, report deployment/health status accurately; do not claim zero downtime or that the previous container stayed active during replacement.
- Keep operator workflows narrow: release selected repos, explicitly roll back an app artifact, or run catalog sync now. Do not add standalone migration or database-restore buttons; migrations remain part of the gated release sequence, and restore stays a separately reviewed recovery procedure.

## Implementation gates and open checks

1. Verify all three GitHub repos can use the private shared workflows and that the account plan supports required reviewer gates for their visibility.
2. Create/install the narrowly scoped GitHub App for release-all workflow dispatch.
3. Finish production Vault retrieval for data, MCP, and Collector; remove GitHub/controller dependence on runtime secret values before enabling deploy jobs.
4. Confirm the MCP host has capacity and required DB/Ollama/network settings for one scheduler container.
5. Inspect Cloudflare Pages Git integration and select GitHub Actions as the sole production deploy owner.
6. Confirm the service and catalog migration backup commands work from the production workflow context.

## Acceptance criteria

- Pull requests in each repo run their verification lane and cannot deploy.
- A production release after a successful `main` build waits for approval and deploys only the selected repo’s immutable version.
- Each deployment has one production approval gate, post-deploy health checks, clear failure details, and an explicit rollback action to the previous immutable application artifact; rejection leaves the current production version unchanged.
- Release-all selects exact versions, triggers only selected repos, preserves separate approvals, and records per-repo outcomes.
- Catalog migrations run once before the MCP/scheduler update; ordinary container restarts do not run migrations.
- One scheduler runs monthly on day 1 at 12:00 `America/Mexico_City`; overlapping executions are prevented and failures/missed runs are observable.
- Spring Liquibase migration completes during the approved app rollout and app health checks pass before success is reported.
- A failed app health gate stops the rollout and makes the previous artifact available for an operator-triggered redeploy; no automatic down migration occurs.
- An operator can run the catalog sync manually without changing the monthly schedule.
- Runtime credentials stay out of GitHub Actions; production deployment remains disabled until all runtime consumers are migrated to Vault.
- Cloudflare production deploy has one owner and verifies the site/API routes after deployment.

## References

- [GitHub reusable workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations)
- [GitHub deployments and environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
- [GitHub workflow triggers](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)
- [Cloudflare Pages direct upload with CI](https://developers.cloudflare.com/pages/how-to/use-direct-upload-with-continuous-integration/)
- [OCI workload identity federation token exchange](https://docs.oracle.com/en-us/iaas/Content/Identity/api-getstarted/token_exchange_grant_type_workload-id-federation.htm)
