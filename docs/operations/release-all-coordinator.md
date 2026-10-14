# Release-all Coordinator

The coordinator lives in `.github/workflows/release-all.yml`. Each selected
repository has an independent annotated `vMAJOR.MINOR.PATCH` tag. The
coordinator resolves that tag to its exact source SHA, verifies that it is
reachable from `main` and has a successful CI run for that tagged SHA, then
dispatches the target release workflow with the version and SHA. A request ID,
version, and SHA appear in each target run title. Target workflows retain
their own production approval. The coordinator never approves a deployment or
rolls back a successful target when another target fails.

## GitHub App setup

The coordinator needs an installation token because the caller's `GITHUB_TOKEN`
cannot dispatch Actions workflows in other repositories.

1. Create a GitHub App owned by `migangdelzar` with no webhook required.
2. Grant repository permissions: **Actions: Read and write** and **Contents:
   Read-only**. Keep the default **Metadata: Read-only** permission.
3. Install the App on only `Ford-uruapan`, `Ford-scraping`, and `Ford-web`.
4. Add these repository Actions secrets to `Ford-uruapan`:
   - `RELEASE_COORDINATOR_APP_ID`
   - `RELEASE_COORDINATOR_APP_PRIVATE_KEY`
5. Do not add the private key to source control, an issue, or a workflow log.

The workflow requests an installation token limited to those three repositories
and to Actions write plus Contents read. The token is temporary and is revoked
when the coordinator job ends. The same Actions read permission allows it to
download each target's short-lived result artifact after the run completes.

## Credential-free preflight

Run **Release selected Ford repositories** from `main` with operation
`validate` to check readiness without dispatching releases. Select at least one
repository and enter its version tag. The preflight reads public GitHub
metadata to confirm each selected tag is annotated, points to a commit on
`main`, and has successful CI for that exact SHA. It does not create the GitHub
App token, dispatch a workflow, or enter a production environment. `validate`
is the default operation.

Choose operation `release` only when you intend to dispatch the selected target
workflows. That path requires the GitHub App secrets above. OCI repositories
publish the scanned, attested SemVer image and record its digest; production
deployment remains a separate gated job. Ford Web deploys the selected SHA to
Pages only after its production environment approval.

## Safe dispatch check

Before a cross-repository dispatch, confirm each selected target's production
release variables remain unset or false. Run **Release selected Ford
repositories** from `main` with the selected repository versions. With
production gates off, target workflows may publish verified immutable OCI
artifacts, but deployment jobs remain skipped. Ford Web's deployment remains
skipped. The coordinator downloads each target's `ford-release-result`
artifact and records source SHA, image digest where applicable, deployment ID,
and deployment URL when deployment completes.

The coordinator reports each target as success, failure, cancelled,
approval-pending, skipped, or timed-out. It also uploads a release manifest
with partial outcomes and artifact identities. An approval-pending target
continues independently after the coordinator's observation window ends.
