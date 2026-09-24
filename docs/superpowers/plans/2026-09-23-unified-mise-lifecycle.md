# Unified Mise Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide one consistent `target:action` lifecycle for the database, infrastructure dependencies, application, development JVM, and full stack while preserving Gradle's reusable JVM and build caches.

**Architecture:** Atomic Mise tasks own the smallest useful service groups: database, cache, telemetry, and application runtime. Composite tasks call those atomic tasks in dependency order and wait for readiness before starting the application. Mise remains the developer-facing entry point; Gradle owns Java compilation, tests, daemon reuse, dependency caching, and build caching; Docker Compose owns long-lived services and named volumes.

**Tech Stack:** Mise file tasks, Bash, Docker Compose, Spring Boot, Gradle Wrapper, Java 25, PostgreSQL, Redis, Prometheus, Loki, Tempo, Grafana Alloy, and OpenTelemetry Collector.

## Global Constraints

- Public lifecycle names use `<target>:<action>`: `start`, `stop`, `restart`, `status`, `check`, `reset`, or `rebuild`.
- `dev:start` is managed as a background JVM process with a repository-local ignored PID/log directory; `dev:stop` must not stop its dependencies.
- `db:reset` is the only destructive reset and requires `--confirm`.
- `deps:start` includes PostgreSQL, Redis, metrics, logs, and traces, but not the application or Grafana.
- `full:start` includes dependencies, the application container, and Grafana.
- Existing unrelated worktree changes must not be staged.
- Gradle daemon, dependency cache, and local build cache remain shared through Gradle User Home; no project-local duplicate cache is introduced.

### Task 1: Replace the Mise task surface with atomic lifecycle tasks

**Files:**
- Create: `ford-ai-service/.mise/tasks/db/start`, `stop`, `restart`, `status`, `reset`
- Create: `ford-ai-service/.mise/tasks/cache/start`, `stop`, `restart`, `status`
- Create: `ford-ai-service/.mise/tasks/telemetry/start`, `stop`, `restart`, `status`
- Create: `ford-ai-service/.mise/tasks/app/start`, `stop`, `restart`, `status`, `rebuild`
- Create: `ford-ai-service/.mise/tasks/deps/start`, `stop`, `restart`, `status`, `check`
- Create: `ford-ai-service/.mise/tasks/full/start`, `stop`, `restart`, `status`, `check`
- Create: `ford-ai-service/.mise/tasks/setup`, `check`, `logs`, `test`
- Delete: legacy `up`, `up-ui`, `deps/up`, `app/run`, `health`, `health-ui`, `down`, `verify`, `env`, and `compose/config` task files after their behavior is covered.

**Interfaces:**
- Every task sources `.mise/lib/compose.sh` and uses `MISE_PROJECT_ROOT`.
- Composite tasks invoke atomic task names such as `mise run db:start` and `mise run telemetry:start`.
- `full:check` extends core readiness checks with Grafana readiness.

- [ ] Add atomic service tasks with explicit Compose service lists.
- [ ] Add composite dependency tasks that start atomic services before readiness checks.
- [ ] Add unified application and full-stack lifecycle tasks.
- [ ] Remove legacy task names that expose mixed verbs such as `up-ui` or `run`.

### Task 2: Add managed local JVM lifecycle and cache-safe state

**Files:**
- Modify: `ford-ai-service/.mise/lib/compose.sh`
- Create: `ford-ai-service/.mise/tasks/dev/start`, `stop`, `restart`, `status`
- Modify: `.gitignore`
- Modify: `ford-ai-service/README.md`

**Interfaces:**
- State files live only under `ford-ai-service/.mise/state/` and contain `dev.pid` and `dev.log`.
- `dev:start` starts dependencies, builds the boot jar through the Gradle wrapper, then backgrounds the Java process.
- `dev:stop` terminates only the recorded JVM PID and leaves Docker dependencies running.

- [ ] Add PID validation, stale-PID cleanup, and log-path helpers.
- [ ] Implement start/stop/restart/status with safe failure messages.
- [ ] Ignore runtime state and document the unified commands.

### Task 3: Verify task contracts, Compose, Gradle reuse, and tests

**Files:**
- Verify: all files under `ford-ai-service/.mise/`, `ford-ai-service/mise.toml`, `ford-ai-service/docker-compose.yml`, and `ford-ai-service/gradle.properties`

- [ ] Confirm all task scripts pass `bash -n`.
- [ ] Confirm Mise discovers only the new public task names and runs `mise run check` without containers by reporting the expected readiness failure.
- [ ] Validate core and UI Compose profiles with `docker-compose config --quiet`.
- [ ] Run `mise run test` and `mise run verify` or the equivalent unified test task.
- [ ] Inspect the staged file list, commit only the Mise/Gradle/docs changes, and push the commit.
