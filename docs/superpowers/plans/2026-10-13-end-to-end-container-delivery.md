# End-to-end Container Delivery Implementation Plan

> **For agentic workers:** Execute this plan inline, task by task, with test-first changes and verification checkpoints.

**Goal:** Verify and release each OCI runtime through GitHub Actions, while keeping the Ford-web UI on Cloudflare Pages.

**Architecture:** One Ford-scraping image runs in separate scheduler and MCP containers. Ford Service is the only backend image. Ford-web remains a Cloudflare Pages UI and does not publish a BFF image. A pinned public OCI deployment action owns remote Ansible rollout logic; each application repo owns its verification and release workflow.

**Tech Stack:** GitHub Actions, GHCR, Docker Buildx, Trivy, SBOM/provenance attestations, Node.js, Gradle/Spring Boot, Bun/Cloudflare Pages, Ansible, Docker Compose.

## Global Constraints

- Keep releases and deploys on immutable SHA tags and digests.
- Pull-request and feature-branch CI must not publish images or deploy.
- Production deploys require the protected `production` environment and existing readiness flags.
- Do not create VMs or alter production runtime settings as part of CI verification.
- Preserve user-owned uncommitted files and local caches in every repository.
- Ford scraping migration must finish before MCP or scheduler candidate starts.
- Ford-web's static UI remains a Cloudflare Pages deployment.

---

## Task 1: Make Ford-scraping runtime commands explicit

**Files:**
- Modify `Ford-uruapan/deployment/ansible/roles/mcp/templates/compose.yml.j2`
- Modify `Ford-uruapan/deployment/ansible/roles/mcp/templates/blue-green-compose.yml.j2`
- Add scheduler Compose service and task in the shared OCI deployment action repo.
- Add source-level workflow/container contract tests in Ford-scraping and the action repo.

**Acceptance:** MCP containers explicitly run `node dist/src/cli.js mcp-http`; scheduler containers run `node dist/src/cli.js scheduler`; scheduler has no published host port; both use the existing data network and candidate image digest.

## Task 2: Add immutable build and scan gates

**Files:**
- Modify `Ford-scraping/.github/workflows/ci.yml` and `release.yml`
- Modify `Ford-uruapan/.github/workflows/reusable-container.yml` only if fresh CI confirms architecture-specific build needs adjustment.
- Keep `Ford-web/.github/workflows/ci.yml` and `release.yml` focused on UI/Pages verification and release; assert no standalone BFF image is built or published.

**Acceptance:** pull requests build and scan without registry writes; successful main CI publishes the source-SHA image, scans it, emits an SBOM and provenance, and exposes its digest to a gated release.

## Task 3: Extend the shared OCI deployment action

**Files:**
- Modify `Ford-oci-deployment-action/.github/actions/oci-blue-green/action.yml`
- Modify `Ford-oci-deployment-action/.github/actions/oci-blue-green/deploy.sh`
- Add or update Ansible templates, tasks, and playbooks for the scraper scheduler; Ford Service remains the sole backend runtime.
- Extend `Ford-oci-deployment-action/tests` for accepted targets, command selection, migration order, health checks, and rollback state.

**Acceptance:** deployment pulls the immutable digest with short-lived GHCR credentials, runs migrations once before candidates, checks MCP protocol and scheduler process health before route switch, and retains prior digest for rollback. Ford Service owns backend APIs/authentication; there is no separate BFF deployment path.

## Task 4: Connect application releases to the shared action

**Files:**
- Modify `Ford-scraping/.github/workflows/release.yml`
- Modify `Ford-uruapan/.github/workflows/release.yml`
- Modify `Ford-web/.github/workflows/release.yml`

**Acceptance:** Ford Service, MCP/scheduler, and Pages release jobs consume only successful source verification and immutable source identities where applicable. Existing backup, approval, readiness, health, and rollback gates remain enforced.

## Task 5: Verify and report

- Run focused Node contract tests, action unit tests, YAML parsing, shell syntax checks, Compose config checks, application CI commands, and Docker builds.
- Push the feature branches and inspect every resulting GitHub Actions job and artifact.
- Do not dispatch a production deploy, publish from a feature branch, enable a production variable, or merge to main.
- Update `docs/operations/production-rollout-readiness.md` only with verified evidence, preserving its current user edits.

## Plan review

- All four OCI artifacts and the Pages UI are represented.
- Migration precedes scraper candidates; service Liquibase compatibility remains documented as a release gate.
- Deployment only consumes immutable images and remains production-gated.
- No credentials or production changes are part of branch CI.
