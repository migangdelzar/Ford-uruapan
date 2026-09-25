# Release Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add semantic-tag releases with Gradle verification, ARM64 GHCR publication, and Mise commands that deploy an immutable image.

**Architecture:** Gradle owns release versioning and verification. GitHub Actions owns CI gates, ARM64 image publication, SBOM, and provenance. Mise remains the local operator interface for checks, tag creation, GHCR release lookup, and Ansible deployment over direct SSH.

**Tech Stack:** Gradle Kotlin DSL, Spring Boot, GitHub Actions, Docker Buildx, GHCR, GitHub CLI, Mise, Ansible, OCI VM runtime.

## Global Constraints

- Release tags are annotated `vMAJOR.MINOR.PATCH`, beginning with `v0.1.0`.
- Pull requests never publish images or deploy.
- Production deployment uses an image digest, never `latest`.
- Runtime secrets and SSH keys remain outside GitHub Actions.
- A release must not restart, reset, or delete `emme-2` data services.

### Task 1: Make Gradle versioning and release verification explicit

**Files:**
- Modify: `ford-ai-service/build.gradle.kts`
- Test: `scripts/release-contract.test.mjs`

- [ ] **Step 1: Write the failing contract test**

Assert the Gradle build file contains a `releaseVersion` property, a non-SNAPSHOT
default, and a `releaseCheck` task. Assert the task invokes `check` and
`bootJar`.

- [ ] **Step 2: Run the contract test and confirm failure**

Run `node --test scripts/release-contract.test.mjs`.
Expected: failure because the release property and task are absent.

- [ ] **Step 3: Implement version and task**

Use `providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT")` for
`project.version`. Register `releaseCheck` as a verification task that requires
`releaseVersion`, rejects blank or `SNAPSHOT` values, depends on `check` and
`bootJar`, and fails when the value is not `X.Y.Z`.

- [ ] **Step 4: Run focused verification**

Run:

```bash
cd ford-ai-service
./gradlew releaseCheck -PreleaseVersion=0.1.0 --no-daemon --max-workers=2
./gradlew releaseCheck -PreleaseVersion=0.1.0-SNAPSHOT --no-daemon --max-workers=2
```

Expected: the first command passes and the second fails before producing a
release artifact.

- [ ] **Step 5: Commit**

```bash
git add ford-ai-service/build.gradle.kts scripts/release-contract.test.mjs
git commit -m "build: add Gradle release verification"
```

### Task 2: Publish versioned OCI images from tag workflows

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/reusable-container.yml`
- Create: `.github/workflows/release.yml`
- Test: `scripts/workflow-contract.test.mjs`

- [ ] **Step 1: Add failing workflow assertions**

Require a tag-triggered release workflow, `v*.*.*` matching, `releaseCheck`,
ARM64 build, GHCR login, digest output, SBOM, and provenance. Require PRs not to
publish and require no production deploy from a tag without an environment gate.

- [ ] **Step 2: Run the workflow contract test and confirm failure**

Run `node --test scripts/workflow-contract.test.mjs`.
Expected: failure because the tag release workflow is absent.

- [ ] **Step 3: Implement release workflow**

The tag workflow extracts `X.Y.Z` from `GITHUB_REF_NAME`, runs `./gradlew
releaseCheck -PreleaseVersion=X.Y.Z`, calls the reusable container workflow with
the release tags, waits for the immutable image digest, creates the GitHub
Release, and publishes SBOM/provenance artifacts. It must never print registry
credentials.

- [ ] **Step 4: Extend the reusable container contract**

Add optional `version` and `revision` inputs. On a release, publish `vX.Y.Z`,
`vX.Y`, `vX`, and `sha-<commit>` while retaining the digest output. Add OCI
labels for version and revision. Preserve current `main` behavior.

- [ ] **Step 5: Run static workflow verification**

Run:

```bash
node --test scripts/workflow-contract.test.mjs scripts/release-contract.test.mjs
git diff --check
```

Expected: all tests pass and no whitespace errors are reported.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows scripts/workflow-contract.test.mjs
git commit -m "ci: publish versioned release images to GHCR"
```

### Task 3: Add Mise release and immutable deployment commands

**Files:**
- Create: `ford-ai-service/.mise/tasks/release/check`
- Create: `ford-ai-service/.mise/tasks/release/tag`
- Create: `ford-ai-service/.mise/tasks/release/publish`
- Create: `ford-ai-service/.mise/tasks/deploy/release`
- Create: `ford-ai-service/.mise/tasks/deploy/rollback`
- Modify: `ford-ai-service/.mise/lib/remote.sh`
- Test: `scripts/operations-contract.test.mjs`

- [ ] **Step 1: Add failing task contract assertions**

Require all five Mise task paths, semver validation, dirty-tree rejection for
release creation, digest resolution through `gh`, and an Ansible application
limit for deployment/rollback.

- [ ] **Step 2: Implement the tasks**

`release:check` runs `git diff --check`, `./gradlew releaseCheck`, and the
container/observability contract tests. `release:tag` validates `vX.Y.Z`,
requires a clean tree, runs `release:check`, and creates an annotated tag without
pushing it. `release:publish` requires `--confirm PUBLISH-RELEASE`, pushes the
selected tag, and prints the Actions URL. `deploy:release` resolves the GHCR
digest with `gh`, writes it only into ignored deployment state, and invokes the
existing Ansible app role with `--limit app`; it never runs the data role.
`deploy:rollback` accepts a digest or prior release tag and uses the same app
path after verifying the selected image exists.

- [ ] **Step 3: Run the task contract tests**

Run `node --test scripts/operations-contract.test.mjs`.
Expected: pass without requiring real OCI credentials.

- [ ] **Step 4: Commit**

```bash
git add ford-ai-service/.mise scripts/operations-contract.test.mjs
git commit -m "ops: add Mise release and immutable deploy commands"
```

### Task 4: Verify a real release candidate

- [ ] **Step 1: Run local release checks**

Run `mise run release:check` from `ford-ai-service/` and save the command output
without secrets.

- [ ] **Step 2: Trigger the first stable tag release**

Create the approved stable tag `v0.1.0`. Confirm the workflow completes, GHCR
exposes the ARM64 image, and the digest is recorded.

- [ ] **Step 3: Deploy only the recorded digest**

Run `mise run deploy:release -- v0.1.0` after the deployment secrets and SSH
inventory are present. Verify app health and private ports, then run
`mise run deploy:status`.

- [ ] **Step 4: Commit verification notes**

Update `docs/ci-cd.md` and `ford-ai-service/README.md` with the exact release
and rollback commands and commit the documentation.
