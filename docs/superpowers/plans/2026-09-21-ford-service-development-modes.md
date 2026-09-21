# Ford Service Development Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add mise tasks for running PostgreSQL and Redis in Compose while Spring Boot runs on the host JVM, while preserving the existing all-container workflow.

**Architecture:** Reuse the existing Compose compatibility helper and single `docker-compose.yml`. Add `docker:deps:up`, `docker:deps:down`, and `docker:deps:ps` tasks that target only `postgres` and `redis`; retain `docker:up` for the complete container stack and `app:run` for host-JVM Spring Boot.

**Tech Stack:** mise executable tasks, Bash, Docker Compose, Node.js contract tests, Gradle/Spring Boot.

## Global Constraints

- Do not introduce a second Compose file.
- Do not change the existing `docker:up` full-stack behavior.
- Dependency tasks must use `.mise/lib/compose.sh` for rootless Linux and macOS Colima socket handling.
- `docker:deps:down` must not delete named volumes.
- `docker:deps:ps` is read-only.

---

### Task 1: Define failing task contracts

**Files:**
- Modify: `scripts/setup-contract.test.mjs` or `scripts/mise-contract.test.mjs`

**Interfaces:**
- Produces contract expectations for `docker:deps:up`, `docker:deps:down`, and `docker:deps:ps`.

- [ ] **Step 1: Add the failing assertions**

Assert that the three executable task files exist, use the Compose helper, target `postgres redis`, and that `docker:up` still contains `up --build -d` without service selection.

- [ ] **Step 2: Run the focused contract test**

Run:

```bash
node --test scripts/mise-contract.test.mjs
```

Expected: FAIL because the dependency task files do not exist.

### Task 2: Implement dependency-only Compose tasks

**Files:**
- Create: `ford-ai-service/.mise/tasks/docker/deps/up`
- Create: `ford-ai-service/.mise/tasks/docker/deps/down`
- Create: `ford-ai-service/.mise/tasks/docker/deps/ps`

**Interfaces:**
- Each task changes to `MISE_PROJECT_ROOT`, sources `.mise/lib/compose.sh`, and invokes `ford_compose`.
- `deps/up` runs `ford_compose up -d postgres redis`.
- `deps/down` runs `ford_compose stop postgres redis` so named volumes are preserved.
- `deps/ps` runs `ford_compose ps postgres redis`.

- [ ] **Step 1: Add the executable task files**

Use the existing task style:

```bash
#!/usr/bin/env bash
#MISE description="Start PostgreSQL and Redis for host-JVM development"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
source "$MISE_PROJECT_ROOT/.mise/lib/compose.sh"
ford_compose up -d postgres redis
```

Use matching descriptions and commands for `down` and `ps`.

- [ ] **Step 2: Run focused contracts**

Run:

```bash
node --test scripts/mise-contract.test.mjs
```

Expected: PASS.

### Task 3: Document both workflows and verify

**Files:**
- Modify: `ford-ai-service/README.md`
- Modify: `ford-ai-service/docs/operations.md`

- [ ] **Step 1: Document host-JVM mode**

Add:

```bash
mise run docker:deps:up
mise run app:run
```

Explain that only PostgreSQL and Redis are containerized in this mode.

- [ ] **Step 2: Document full-container mode**

Keep or clarify:

```bash
mise run docker:up
```

Explain that this builds and starts PostgreSQL, Redis, and Spring Boot in Compose.

- [ ] **Step 3: Run verification**

Run:

```bash
node --test scripts/setup-contract.test.mjs scripts/mise-contract.test.mjs scripts/workflow-contract.test.mjs scripts/operations-contract.test.mjs scripts/observability-contract.test.mjs
bash -n scripts/setup/*.sh ford-ai-service/.mise/tasks/setup/* ford-ai-service/.mise/tasks/docker/* ford-ai-service/.mise/tasks/docker/deps/* ford-ai-service/.mise/lib/*.sh
(cd ford-ai-service && mise tasks validate)
(cd ford-ai-service && mise run docker:config)
```

Expected: all tests pass, shell syntax succeeds, all mise tasks validate, and Compose config succeeds.

- [ ] **Step 4: Commit implementation**

```bash
git add ford-ai-service/.mise/tasks/docker/deps ford-ai-service/README.md ford-ai-service/docs/operations.md scripts/mise-contract.test.mjs
git commit -m "feat: add host JVM development mode"
```
