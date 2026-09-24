# Release Automation and Mise Design

## Goal

Create a repeatable release path for `ford-service` using semantic version tags,
Gradle release verification, GitHub Actions, and GHCR, with Mise as the short
operator-facing command interface.

## Scope

This design covers versioning, Gradle release checks, container publication, and
the handoff from a published image to the OCI deployment. Runtime observability
is specified separately so it can be implemented and verified independently.

## Decisions

- Release versions use annotated `vMAJOR.MINOR.PATCH` tags, starting at `v0.1.0`.
- Pull requests run verification and image security gates but never publish.
- Pushes to `main` may publish a commit-addressed image for integration testing.
- Version tags publish the same image under the version tag and immutable digest.
- Production deployment consumes a digest, never `latest` or a mutable version tag.
- GitHub Actions builds and publishes; Mise plus Ansible performs the OCI deploy
  over the existing direct SSH access. OCI credentials and runtime secrets stay
  outside GitHub Actions.
- A release never changes PostgreSQL/Redis data services automatically.
- A failed application deployment must not restart or reset `emme-2`.

## Release flow

```text
mise run release:check
        |
mise run release:tag -- v0.1.0
        |
git push origin v0.1.0
        |
GitHub Actions: Gradle -> security -> observability -> ARM64 image -> GHCR
        |
GitHub Release + SBOM + provenance + image digest
        |
mise run deploy:release -- v0.1.0
        |
Ansible pulls the exact digest on emme-1 and runs smoke tests
```

The tag command is intentionally separate from the push command so a local
operator can inspect the tag and CI configuration before creating remote state.
The optional convenience task `mise run release:publish -- v0.1.0` may combine
the check, annotated tag creation, and tag push after an explicit confirmation
variable is supplied.

## Gradle contract

`ford-ai-service/build.gradle.kts` will derive the project version from the
`releaseVersion` Gradle property and retain `0.1.0-SNAPSHOT` as the local
default. The release pipeline passes the normalized tag version without the
leading `v`.

The `releaseCheck` task will:

1. Require `-PreleaseVersion=X.Y.Z`.
2. Reject missing, malformed, or `SNAPSHOT` versions.
3. Run the normal `check` lifecycle, including integration tests.
4. Produce the Spring Boot executable JAR and build metadata.

The Docker build receives the same version and OCI labels for version, source
revision, and build timestamp. The image remains reproducible from the tag and
commit used by CI.

## GitHub Actions contract

The current reusable container workflow will accept an image tag prefix and
publish the following references for a release:

- `ghcr.io/migangdelzar/Ford-uruapan:v0.1.0`
- `ghcr.io/migangdelzar/Ford-uruapan:v0.1`
- `ghcr.io/migangdelzar/Ford-uruapan:v0`
- `ghcr.io/migangdelzar/Ford-uruapan:sha-<commit>`

The workflow will also expose the content digest. Trivy, SBOM generation, and
GitHub build-provenance attestation remain required gates. A release job creates
the GitHub Release only after Gradle and container gates pass.

The production deployment workflow remains manual and protected. It receives
the image digest and environment name, but the first OCI implementation keeps
the actual SSH deployment in the local Mise/Ansible path to avoid storing VM
private keys in GitHub.

## Mise interface

From `ford-ai-service/`:

```text
mise run release:check
mise run release:tag -- v0.1.0
mise run release:publish -- v0.1.0
mise run deploy:release -- v0.1.0
mise run deploy:status
mise run deploy:rollback -- v0.1.0
```

The tasks validate semver input, refuse dirty working trees for a release,
avoid printing secrets, and report only the selected version, commit, image
reference, and remote service health. Rollback selects a previously published
digest and updates only the application service on `emme-1`.

## Failure and rollback behavior

- A failed Gradle or security gate prevents image publication.
- A failed image publication prevents release creation.
- A failed remote app pull or health check leaves the previous app container
  available until the replacement passes its health check.
- Database and Redis volumes are never removed by release or rollback tasks.
- Deployment logs redact registry tokens, application credentials, customer
  data, prompt content, and provider payloads.

## Verification

The implementation is complete when all of the following are demonstrated:

- `mise run release:check` passes on the release worktree.
- A `v0.1.0` tag run publishes an ARM64 GHCR image, SBOM, provenance, and digest.
- GHCR pull by digest succeeds from `emme-1`.
- `mise run deploy:release -- v0.1.0` passes the app health and private database
  and Redis connectivity checks.
- A rollback to the prior digest passes without touching `emme-2` data volumes.
