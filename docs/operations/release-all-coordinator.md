# Release-all Coordinator

The coordinator lives in `.github/workflows/release-all.yml`. Each repository
uses its own annotated `vMAJOR.MINOR.PATCH` tag. Before approval, the workflow
resolves every selected tag to its exact source SHA and verifies that the tag
is reachable from `main` and has successful CI for that SHA. The preflight
summary shows the selected versions and SHAs for review.

For `release`, one human approval in Ford Service's
`release-all-approval` environment authorizes the selected rollout. The
coordinator dispatches only checked repositories in this fixed order:

1. Ford-scraping (catalog writer and monthly scheduler)
2. Ford Service
3. Ford Web

It waits for each target workflow's deploy and health/smoke checks before
starting the next target. Any failure or timeout stops the sequence; later
selected targets are recorded as skipped. Successful earlier targets are not
rolled back automatically. Selecting one repository runs only that repository.

Target workflows accept the coordinator path only when all of these hold:

- The dispatch actor is `ford-release-coordinator[bot]`.
- The release request ID matches the current coordinator run ID and attempt.
- The referenced Ford Service run is an active `release-all.yml` dispatch on
  protected `main`, and its approved release job is in progress.
- The workflow ref is protected `main`.

Direct per-repository releases keep their own approval. Each repository uses
`manual-release-approval` for that reviewer gate, then accesses its existing
`production` environment secrets and variables. The `production` environment
must retain its branch policy but have no required reviewer, so release-all
child runs do not create additional approvals. Keep the Pages token in the
existing `pages-preview` environment, which has no reviewer gate.

## GitHub App setup

The coordinator needs an installation token because a repository's
`GITHUB_TOKEN` cannot dispatch Actions workflows in the other repositories.

1. Use the GitHub App owned by `migangdelzar` with no webhook.
2. Grant **Actions: Read and write** and **Contents: Read-only**. Keep the
   default **Metadata: Read-only** permission.
3. Install the App on only `Ford-uruapan`, `Ford-scraping`, and `Ford-web`.
4. Keep `RELEASE_COORDINATOR_APP_ID` and
   `RELEASE_COORDINATOR_APP_PRIVATE_KEY` as repository Actions secrets in
   `Ford-uruapan`.
5. Never add the private key to source control, issues, or workflow logs.

The preflight requests a temporary Actions read / Contents read token to inspect
private and public target metadata. After approval, the deployment stage creates
a second token scoped to Actions write / Contents read. Tokens expire when
their jobs end.

## Environment configuration

In Ford Service, create `release-all-approval`, require the production reviewer,
and limit deployments to protected `main`. Do not place deployment secrets in
this approval-only environment.

In each target repository, create `manual-release-approval` with the current
reviewer and protected `main` policy. Keep all current deployment secrets and
variables in `production`, remove its required reviewer, and limit it to
protected `main`. This preserves direct release approval while allowing
coordinator-authorized child runs through the same protected deployment jobs.

In Ford Service, also create `terraform-runtime-vault-approval` with the same
reviewer and protected `main` policy. Terraform keeps using the `production`
environment for OCI credentials; the new approval runs after a reviewed plan
and before applying it.

## Preflight and release operation

Run **Release selected Ford repositories** from `main` with operation
`validate` to check a selection without dispatching releases. Select at least
one repository, and provide a version for each selected repository. This is the
default operation and does not enter a production environment.

Choose `release` to deploy. The coordinator verifies all selected identities,
waits for the single approval, and then deploys the selection in the fixed
order. Every child repeats tag, CI, image provenance, and production readiness
checks. The child result includes immutable artifact identity, migration status,
deployment link, and post-deploy smoke result. The coordinator records these in
one release manifest.

No database rollback is automatic. OCI traffic rollback retains its prior
container; database restoration remains a separate reviewed recovery operation.
