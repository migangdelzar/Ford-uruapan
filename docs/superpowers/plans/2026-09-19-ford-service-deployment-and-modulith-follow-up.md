# ford-service Deployment and Modulith Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep deployment configuration inside `ford-ai-service/` and verify that the existing Spring Modulith foundation remains intact.

**Architecture:** Move only the service-owned observability deployment tree into `ford-ai-service/deployment/observability/`. Keep GitHub Actions at repository root because GitHub requires `.github/workflows/` there. Do not duplicate Modulith dependencies or redesign its existing application-module graph; run its architecture, documentation, security, and application-context checks after the move.

**Tech Stack:** Spring Boot 4.1.0, Spring Modulith 2.1.1, Gradle, Docker Compose, GitHub Actions, Java 25.

## Global Constraints

- Work only on `feat/ford-service-mvp`.
- Do not merge into `main` in this change.
- Preserve deployment images, ports, environment variables, healthchecks, volumes, and runtime behavior.
- Keep `.github/workflows/` at repository root.
- Do not add a second Spring Modulith dependency set; the existing BOM and starters are authoritative.
- Do not delete or overwrite generated/untracked files outside the scoped deployment move.

---

### Task 1: Relocate service deployment configuration

**Files:**

- Move: `deployment/observability/` to `ford-ai-service/deployment/observability/`
- Create: `ford-ai-service/deployment/README.md`
- Modify: references in tracked documentation and workflow files, if present

**Interfaces:**

- Consumes the existing Langfuse Compose stack and OpenTelemetry collector configuration.
- Produces the same local observability stack at its new service-owned path.

- [x] **Step 1: Confirm the source set and stale references**

Run:

```bash
find deployment/observability -type f -print | sort
git grep -n -E 'deployment/observability|deployment/|observability/langfuse|otel-collector'
```

Expected: only the tracked observability tree is moved; GitHub workflow files remain at the repository root.

- [x] **Step 2: Move the tracked deployment tree**

Run:

```bash
mkdir -p ford-ai-service/deployment
mv deployment/observability ford-ai-service/deployment/observability
```

Remove the now-empty root `deployment` directory if it contains no files.

- [x] **Step 3: Add the service deployment index**

Create `ford-ai-service/deployment/README.md` describing:

```text
cd ford-ai-service/deployment/observability/langfuse
cp .env.example .env
docker compose up -d
```

State that secrets remain in `.env`, are not committed, and the stack is local/self-hosted observability support for `ford-service`.

- [x] **Step 4: Update path references**

Change only references that point to the moved tree. Preserve root-relative GitHub workflow locations and service Docker build context:

```yaml
context: ford-ai-service
file: ford-ai-service/Dockerfile
```

- [x] **Step 5: Verify the relocation**

Run:

```bash
test ! -e deployment
test -f ford-ai-service/deployment/observability/langfuse/docker-compose.yml
git grep -n -E 'deployment/observability|deployment/|observability/langfuse|otel-collector' -- ':!ford-ai-service/deployment/**' || true
git diff --check
```

Expected: no root deployment directory and no stale path references outside the new service-owned tree.

- [x] **Step 6: Commit**

```bash
git add -A deployment ford-ai-service/deployment .github docs
git commit -m "chore: co-locate deployment configuration with service"
```

### Task 2: Regression-verify Spring Modulith integration

**Files:**

- Verify: `ford-ai-service/build.gradle.kts`
- Verify: `ford-ai-service/gradle/libs.versions.toml`
- Verify: `ford-ai-service/src/main/java/com/ford/service/FordServiceApplication.java`
- Verify: `ford-ai-service/src/main/resources/application.yml`
- Verify: `ford-ai-service/src/test/java/com/ford/service/architecture/ApplicationModulesTest.java`
- Verify: `ford-ai-service/src/test/java/com/ford/service/architecture/ModuleDocumentationTest.java`
- Verify: `ford-ai-service/src/test/java/com/ford/service/security/ToolAndWebhookSecurityTest.java`

**Interfaces:**

- Existing Modulith foundation remains the single source of module verification, JDBC publication support, module documentation, runtime topology, and module tests.

- [x] **Step 1: Resolve dependency and run focused architecture tests**

Run:

```bash
cd ford-ai-service
./gradlew test --tests '*ApplicationModulesTest' --tests '*ModuleDocumentationTest' --tests '*ToolAndWebhookSecurityTest' --no-daemon
```

Expected: PASS with zero failures.

- [x] **Step 2: Run the complete unit suite**

Run:

```bash
./gradlew test --no-daemon
```

Expected: PASS with zero failures and the existing Modulith tests included.

- [x] **Step 3: Inspect the final graph and working tree**

Run:

```bash
find build/spring-modulith-docs -type f -print | sort | head -40
git status --short --branch
git diff --stat HEAD~1..HEAD
```

Expected: generated module documentation exists under the ignored build directory, and the latest commit contains only the deployment relocation, deployment index, and required path updates.

- [x] **Step 4: Update progress**

Record that the deployment-layout follow-up is complete and that Phase A Tasks 3–7 remain pending in `.superpowers/sdd/progress.md`; do not mark the entire Modulith Phase A plan complete.

- [x] **Step 5: Commit progress**

```bash
git add .superpowers/sdd/progress.md docs/superpowers/plans/2026-09-19-ford-service-deployment-and-modulith-follow-up.md
git commit -m "docs: record deployment and Modulith follow-up status"
```

## Existing Modulith roadmap

The broader plan remains `docs/superpowers/plans/2026-09-18-ford-service-phase-a-modulith.md`. Its pending work is:

- Task 3: durable conversation workflow and CAS-protected handoff.
- Task 4: Spring AI providers and read-only tools.
- Task 5: external provider outbox recovery.
- Task 6: claim-based operator actions and handoff events.
- Task 7: complete signed Meta-to-outbox vertical verification.

Those tasks are intentionally not marked complete by this deployment-layout change.
