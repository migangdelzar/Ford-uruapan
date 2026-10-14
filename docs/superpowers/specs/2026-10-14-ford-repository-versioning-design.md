# Independent Versioning Across Ford Repositories

**Date:** 2026-10-14  
**Status:** Approved by user for implementation  
**Scope:** `Ford-scraping`, `Ford-uruapan` (Ford Service), `Ford-web`, and release-all coordination

## Goal

Give each repository an independent SemVer release history while making every release reproducible. Release-all must report the selected version and immutable source identity for each repository. Ford Web remains on Cloudflare Pages; the two backend repositories publish OCI images.

## Options considered

1. **Independent SemVer per repository (recommended):** Each repo owns its version cadence. Release-all takes a version for each selected repo and records the mapping. This supports independently changing services and matches the selected direction.
2. **One shared version across all repos:** Simple release labels, but forces unrelated projects to release together and obscures which artifact changed.
3. **Commit-SHA-only releases:** Strong immutable identity, but no readable product release labels or convenient changelog anchors.

## Version and artifact identity

- Release tags use annotated `vMAJOR.MINOR.PATCH` tags independently in each repository.
- A tag is eligible only when it points to a commit reachable from that repo's protected `main` and the exact commit has a successful required CI run.
- The full Git commit SHA remains the source identity for every release. OCI releases also record and deploy the image digest (`sha256:…`). Mutable tags such as `latest` are never deployment inputs.
- Version tags are immutable: workflows reject an existing tag/version and never move or overwrite it. Correcting a release requires a new patch version.
- Version bump selection is explicit at release time; this design does not infer breaking changes from commit text or automatically edit versions.

## Per-repository publication

- **Ford-scraping:** Tag a CI-verified `main` commit, build and test the MCP container, publish OCI image aliases for the exact SemVer (`vX.Y.Z`, `X.Y.Z`), and record the pushed digest. Deployment remains a separately approved operation and consumes the digest.
- **Ford-uruapan / Ford Service:** Tag a CI-verified `main` commit, build and test the service container, publish OCI image aliases for the exact SemVer, and record the pushed digest. Existing Liquibase change sets remain the schema-version history; releases report the service version/digest and migration outcome rather than introducing a separate migration image version.
- **Ford-web:** Tag a CI-verified `main` commit and deploy that exact commit to Cloudflare Pages through its current release workflow. Include the SemVer and full SHA in build metadata and the GitHub release summary. Do not create a UI container image.
- **Ford-oci-deployment-action:** Maintain its own annotated SemVer tags for reusable-action discoverability. Repositories continue pinning the reviewed full action commit SHA, so a moving action tag is never a runtime dependency.

## Release-all

- Keep `validate` as the default, credential-free, read-only operation.
- For explicit `release`, accept an independent version for each selected repository (or a selection map when some repos are omitted). Resolve each `vX.Y.Z` tag to its exact SHA, verify tag immutability/ancestry and successful CI for that SHA, then dispatch the existing repo release workflow with that exact version and SHA.
- Produce a release manifest in the workflow summary and downloadable artifact. Each entry includes repository, version, tag, full source SHA, artifact digest when applicable, workflow run URL, and deployment URL/ID when a production deployment is approved and completed.
- A partial failure must retain successful entries and clearly report each failed repository. Rerunning release-all must not retag or overwrite an existing release.
- Tags do not bypass current production environment approvals or deployment gates. Production gates remain unset until separately authorized and prerequisites are ready.

## Safe execution and rollback

- Release publication and production deployment remain distinct operations. CI/tag verification may publish immutable artifacts; production deployment still requires the repository's existing explicit release operation and environment protection.
- OCI rollback selects a previously successful digest and source SHA. Pages rollback selects a prior successful production commit SHA. Neither rollback moves a version tag.
- The release manifest is the cross-repo record; no shared version is implied by a release-all run.

## Validation requirements

- Reject malformed, duplicate, or already-used SemVer tags and tags not on protected `main`.
- Reject a tag whose exact SHA lacks successful required CI.
- Test that OCI deployment receives a digest, not a SemVer alias; test Pages release receives the tagged commit SHA.
- Test release-all validate mode uses no credentials and dispatches nothing; test explicit release dispatches each repo's version and SHA and emits a per-repo result even on partial failure.
- Confirm generated build metadata and release manifest do not contain secrets.
- Perform a non-production/preview verification before enabling production release gates.

## Open operational prerequisite

The current main-branch Ford Service CI run after the release-all preflight merge is still executing its PostgreSQL integration and deterministic AI evaluation jobs. The versioning implementation should branch from current main after that run concludes and after this design is approved.
