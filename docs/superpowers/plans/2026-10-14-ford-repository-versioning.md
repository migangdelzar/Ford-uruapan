# Independent Repository Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add immutable independent `vMAJOR.MINOR.PATCH` releases to Ford-scraping, Ford Service, and Ford-web, and make release-all resolve and report each selected tag, source SHA, artifact identity, and result.

**Architecture:** Each repository owns its tags and release workflow. Tag-triggered workflows verify tag format, annotated-tag target, ancestry on `main`, and successful CI for that exact SHA; OCI projects build, scan, publish, attest, and deploy by digest, while Ford-web deploys the tagged SHA to Pages. Release-all remains a credential-free validate path by default and only dispatches selected versions after resolving and verifying each repository's exact tag and source.

**Tech Stack:** GitHub Actions, GitHub REST API, Python 3 standard library, GHCR, Docker Buildx, Cloudflare Pages/Wrangler, existing OCI blue-green Ansible action.

## Global Constraints

- Keep Ford-scraping, Ford Service, and Ford-web versions independent.
- Use annotated immutable `vMAJOR.MINOR.PATCH` tags; never move a released tag.
- Preserve the full source commit SHA in every release record.
- OCI deployment inputs must be `ghcr.io/...@sha256:...`, never mutable aliases.
- Ford-web remains Cloudflare Pages; do not create a UI container image.
- Release-all defaults to credential-free read-only validation; release dispatch requires explicit selection and version per selected repository.
- Keep production gates and environment approvals unchanged; no production rollout is part of implementation verification.
- Preserve existing dirty user files and use isolated worktrees for all repository edits.

---

## File Map

- `Ford-scraping/.github/workflows/release.yml`: publish a verified SemVer tag to GHCR, then deploy only through the existing explicit gated operation.
- `Ford-uruapan/.github/workflows/release.yml` and `.github/workflows/reusable-container.yml`: publish the tagged service image by digest with scan, SBOM, provenance, and gated OCI rollout.
- `Ford-web/.github/workflows/release.yml`: deploy the exact verified tagged commit to Pages and report version/SHA/deployment identity.
- `Ford-uruapan/ford-ai-service/scripts/release_all.py`: resolve selected versions, verify remote tag and CI identity, dispatch exact version/SHA, preserve per-repository partial results, and render a downloadable manifest.
- `Ford-uruapan/.github/workflows/release-all.yml`: accept independent version inputs and upload the manifest artifact.
- Existing contract/unit tests in each repository: exercise malformed/duplicate tags, ancestry, CI matching, immutable digest selection, exact Pages SHA, read-only validation, and partial failures.
- `Ford-uruapan/docs/operations/repository-versioning.md`: explain tag creation, required GitHub tag rulesets, publication, rollback, and manual account setup.

## Task 1: Release-all version resolution and manifest

**Files:**
- Modify: `Ford-uruapan/ford-ai-service/scripts/release_all.py`
- Modify: `Ford-uruapan/ford-ai-service/scripts/tests/test_release_all_preflight.py`
- Modify: `Ford-uruapan/ford-ai-service/scripts/tests/test_release_all_selection.py`
- Modify: `Ford-uruapan/ford-ai-service/scripts/tests/test_release_all_summary.py`

- [x] Add tests that reject malformed versions and fail when a selected repository has no exact `vX.Y.Z` tag, when the tag target is not reachable from `main`, or when exact-SHA push CI did not succeed.
- [x] Add tests that release dispatch includes the selected version and resolved full SHA, retains results for successful repositories when a different selected repository fails, and emits one manifest entry per selection.
- [x] Run the focused unittest suite and confirm the new tests fail before implementation.
- [x] Implement strict SemVer parsing, annotated-tag peeling, `main` ancestry verification, exact-SHA successful-CI lookup, and versioned dispatch using the existing injected GitHub API boundary.
- [x] Add a deterministic JSON manifest writer and validated target result-artifact reader for repository, version, tag, source SHA, status, workflow URL, OCI digest, and deployment identity; never include token or secret fields.
- [x] Re-run the focused unittest suite and confirm all cases pass, including partial repository failure and malformed result artifacts.

## Task 2: Ford-scraping tagged MCP image release

**Files:**
- Modify: `Ford-scraping/.github/workflows/release.yml`
- Modify: `Ford-scraping/scripts/container-contract.test.mjs`
- Modify: applicable workflow/release tests under `Ford-scraping/scripts/`

- [x] Add failing workflow contract tests for tag-only publication, exact tag SHA CI verification, annotated SemVer enforcement, immutable SemVer aliases, digest output, and production deployment remaining separately gated.
- [x] Update unit and contract assertions to reject the prior automatic `main` release behavior.
- [x] Make the release workflow publish only after a valid `vX.Y.Z` tag resolves to a successful `main` CI commit; retain ARM64 image scan, SBOM, provenance verification, and digest-only deployment.
- [x] Preserve the one-shot MCP migration and pre-release backup in the pinned OCI action path.
- [x] Run lint, all 278 unit tests, container contract tests, YAML parsing, and shell syntax checks.

## Task 3: Ford Service tagged container release

**Files:**
- Modify: `Ford-uruapan/.github/workflows/ci.yml`
- Modify: `Ford-uruapan/.github/workflows/reusable-container.yml`
- Modify: `Ford-uruapan/.github/workflows/release.yml`
- Modify: `Ford-uruapan/ford-ai-service/scripts/tests/test_security_workflow_contract.py`
- Modify: `Ford-uruapan/ford-ai-service/scripts/tests/test_oci_action_contract.py`

- [x] Add failing contract tests proving pull-request/main CI can verify without publishing, a SemVer tag is required for GHCR publication, the tag SHA must match successful main CI, and OCI deployment consumes a verified digest.
- [x] Run focused Python and JavaScript workflow contract tests and observe the expected pre-implementation failures.
- [x] Separate image verification from publication so ordinary CI has read-only package permission and only an explicitly tagged, verified release publishes; preserve native smoke, ARM64 target build, vulnerability scan, SBOM, attestation, and attestation verification.
- [x] Keep deployment and rollback under existing `production` and Vault gates; retain database backup before service deploy and recorded digest rollback.
- [ ] Run `./gradlew check --no-daemon` with Docker available; this local run's unit checks pass, but Testcontainers integration tests fail because no Docker daemon/socket is available.

## Task 4: Ford-web tagged Pages release

**Files:**
- Modify: `Ford-web/.github/workflows/release.yml`
- Modify: `Ford-web/tests/unit/releaseWorkflow.test.ts`
- Modify: `Ford-web/docs/architecture/ford-pages-bff-routing.md` only if the release/version behavior changes its documented deployment ownership.

- [x] Add failing tests for SemVer tag validation, tag-to-successful-main-CI SHA matching, deployment of the exact tagged SHA, and version/SHA/deployment metadata in the release result.
- [x] Run the workflow contract test and confirm the new assertions fail before workflow changes.
- [x] Implement Pages release from the verified tag commit while preserving current production environment and approval gates; do not add a Docker image or change proxy/public-origin routing.
- [x] Run 128 unit tests, lint, build, API checks, Pages Functions checks, workflow contract tests, YAML parsing, and shell syntax checks.

## Task 5: Version-aware release-all coordinator

**Files:**
- Modify: `Ford-uruapan/.github/workflows/release-all.yml`
- Modify: `Ford-uruapan/ford-ai-service/scripts/release_all.py`
- Modify: `Ford-uruapan/ford-ai-service/scripts/tests/test_release_all_selection.py`
- Modify: `Ford-uruapan/ford-ai-service/scripts/tests/test_release_all_summary.py`

- [x] Add workflow and coordinator tests proving validate mode needs no App credential and dispatches nothing, selected repositories require their own version inputs, and partial manifests survive target failure.
- [x] Run the focused contract tests and confirm failure before implementation.
- [x] Add three independent version inputs; require one version per selected repository and dispatch the resolved tag/SHA pair.
- [x] Add per-repository result artifacts and a summary with workflow URLs, artifact digests, and deployment identities without changing production approvals.
- [x] Run the release-all unit suite, workflow contract tests, YAML parsing, and shell syntax checks. Live preflight on GitHub remains pending because these changes are not pushed.

## Task 6: Release operations and version safeguards

**Files:**
- Create: `Ford-uruapan/docs/operations/repository-versioning.md`
- Modify: per-repository release documentation and `.github` ruleset documentation as needed.

- [x] Document annotated tag creation from a CI-verified `main` SHA, version uniqueness, exact source identity, image digest use, Pages deployment identity, and OCI rollback by prior digest without retagging.
- [x] Document required GitHub tag rulesets for `v*` immutability and branch protections; distinguish account-owner settings from workflow-enforced checks.
- [x] Document that Ford-web publishes to Pages and Ford Service/Ford-scraping publish container images, with production deployment still gated.
- [ ] Search all three repositories and the action repository for stale release claims; do not rewrite historical readiness snapshots without their owner’s review.

## Final verification

- [ ] Run all repository CI checks on the implementation branches.
- [ ] Run release-all `validate` with all three repos selected; verify it reports the exact eligible versions/SHA and creates no App token, dispatch, or deployment.
- [ ] Use a non-production Pages preview and image publication validation only; do not dispatch OCI production deploy/rollback or change production gates.
- [ ] Verify GHCR artifacts are digest-addressed, scanned, attested, and linked to tag/SHA; verify Ford-web Pages metadata identifies tag/SHA.
- [ ] Record production gates and any account-side tag ruleset configuration as pending; do not claim production deployment validation.
