# Release-all Coordinator

The coordinator lives in `.github/workflows/release-all.yml`. It resolves the
latest successful `main` push verification for each selected repository, then
dispatches that repository's release workflow with the exact 40-character
source SHA. A request ID and the SHA appear in each target run title so the
coordinator can locate and report the matching run. Target workflows retain
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
when the coordinator job ends.

## Credential-free preflight

Run **Release selected Ford repositories** from `main` with operation
`validate` to check readiness without dispatching releases. Select at least one
repository. The preflight reads public GitHub Actions metadata to confirm each
selected repository has an active release workflow and a successful push CI
run on `main`; its summary records the exact verified source SHA. It does not
create the GitHub App token, dispatch a workflow, or enter a production
environment. `validate` is the default operation.

Choose operation `release` only when you intend to dispatch the selected target
workflows. That path requires the GitHub App secrets above and each target
workflow retains its own production approval.

## Safe dispatch check

Before a cross-repository dispatch, confirm each selected target's production
release variables remain unset or false. Then run `Release selected Ford
repositories` from `main` with **Ford Service only** selected. With production
gates off, the target run should report `skipped`; it must link the run and show
the exact verified SHA. This exercises dispatch without publishing a new MCP
image or entering production. Do not test the other targets until their
publication and deployment effects are understood.

The coordinator reports each target as success, failure, cancelled,
approval-pending, skipped, or timed-out. An approval-pending target continues
independently after the coordinator's observation window ends.
