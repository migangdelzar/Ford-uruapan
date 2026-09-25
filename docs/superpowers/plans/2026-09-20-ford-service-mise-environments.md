# Ford Service mise Environment and Task Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a service-local mise workflow with grouped tasks, default development environment selection, explicit production overlays, and safe environment-variable handling.

**Architecture:** `.miserc.toml` selects `dev` early by default. Shared `mise.toml` manages Java 25, while `mise.dev.toml` and `mise.prod.toml` overlay environment values. Executable tasks under `.mise/tasks/<capability>/` wrap the existing Gradle, Docker Compose, observability, and load-gate commands without moving application configuration out of Spring’s existing `application.yml` contract.

**Tech Stack:** mise 2026.x, Java 25 / Gradle Wrapper, Bash file tasks, Docker Compose, Node.js test contracts, Spring Boot environment variables.

## Global Constraints

- Keep all work on branch `feat/ford-service-mvp`.
- Scope mise configuration and tasks to `ford-ai-service/`.
- Use Java 25, matching `build.gradle.kts` and the CI setup action.
- Keep secrets out of tracked mise files and example files.
- Default to `dev`; require explicit `mise -E prod` for production configuration.
- Keep the Docker Compose environment allowlist explicit.
- Do not add a destructive Docker volume-deletion task.
- Do not change Java application behavior or Spring configuration binding.
- Preserve unrelated worktree changes.

---

### Task 1: Add a failing mise contract test

**Files:**
- Create: `scripts/mise-contract.test.mjs`

**Interfaces:**
- Consumes: Tracked mise config files, task files, `.gitignore`, README, and operations documentation.
- Produces: An executable repository contract that fails until the complete mise layout exists.

- [ ] **Step 1: Write the failing contract test**

Create `scripts/mise-contract.test.mjs`:

```js
import assert from "node:assert/strict";
import { access, constants, readFile, stat } from "node:fs/promises";
import test from "node:test";

const serviceRoot = "ford-ai-service";

async function read(path) {
  return readFile(path, "utf8");
}

async function assertExecutable(path) {
  await access(path, constants.X_OK);
  const file = await stat(path);
  assert.ok((file.mode & 0o111) !== 0, `${path} must be executable`);
}

test("mise selects development by default and manages Java 25", async () => {
  const miserc = await read(`${serviceRoot}/.miserc.toml`);
  const config = await read(`${serviceRoot}/mise.toml`);
  const dev = await read(`${serviceRoot}/mise.dev.toml`);
  const prod = await read(`${serviceRoot}/mise.prod.toml`);

  assert.match(miserc, /env\s*=\s*\[\s*["']dev["']\s*\]/);
  assert.match(config, /\[tools\][\s\S]*\bjava\s*=\s*["']25["']/);
  assert.match(dev, /FORD_RUNTIME_ENV\s*=\s*["']development["']/);
  assert.match(dev, /_\.file\s*=\s*\{[^}]*path\s*=\s*["']\.env["']/);
  assert.match(prod, /FORD_RUNTIME_ENV\s*=\s*["']production["']/);
  assert.match(prod, /FORD_DATABASE_URL\s*=\s*\{\s*required\s*=/);
  assert.match(prod, /FORD_DATABASE_PASSWORD\s*=\s*\{[^}]*required\s*=.*redact\s*=\s*true/);
});

test("grouped mise task files exist and are executable", async () => {
  const tasks = [
    "app/run",
    "app/build",
    "test/unit",
    "test/integration",
    "test/check",
    "test/ai-evaluation",
    "env/check",
    "env/list",
    "docker/up",
    "docker/down",
    "docker/build",
    "docker/config",
    "docker/ps",
    "docker/logs",
    "observability/up",
    "observability/down",
    "observability/config",
    "observability/ps",
    "observability/logs",
    "load/gate",
    "doctor"
  ];

  for (const task of tasks) {
    const path = `${serviceRoot}/.mise/tasks/${task}`;
    await assertExecutable(path);
    assert.match(await read(path), /MISE_PROJECT_ROOT/);
  }
});

test("tracked mise configuration contains no credential-shaped values", async () => {
  const paths = [
    `${serviceRoot}/.miserc.toml`,
    `${serviceRoot}/mise.toml`,
    `${serviceRoot}/mise.dev.toml`,
    `${serviceRoot}/mise.prod.toml`,
    `${serviceRoot}/.env.prod.example`
  ];

  for (const path of paths) {
    const source = await read(path);
    assert.doesNotMatch(source, /sk-[A-Za-z0-9]{20,}/, `${path} must not contain an OpenAI-style key`);
    assert.doesNotMatch(source, /EAA[A-Za-z0-9]{20,}/, `${path} must not contain a Meta-style token`);
  }
});

test("documentation exposes grouped tasks and environment selection", async () => {
  const readme = await read(`${serviceRoot}/README.md`);
  const operations = await read(`${serviceRoot}/docs/operations.md`);
  const gitignore = await read(".gitignore");

  assert.match(readme, /mise run test:unit/);
  assert.match(readme, /mise -E prod/);
  assert.match(operations, /env:list/);
  assert.match(operations, /mise\.prod\.local\.toml/);
  assert.match(gitignore, /^mise\.local\.toml$/m);
  assert.match(gitignore, /^mise\.\*\.local\.toml$/m);
});
```

- [ ] **Step 2: Run the contract test and verify it fails**

Run:

```bash
node --test scripts/mise-contract.test.mjs
```

Expected: FAIL because the mise configuration and grouped task files do not yet exist.

- [ ] **Step 3: Commit the red contract**

```bash
git add scripts/mise-contract.test.mjs
git commit -m "test: define mise workflow contract"
```

### Task 2: Add shared and environment-specific mise configuration

**Files:**
- Create: `ford-ai-service/.miserc.toml`
- Create: `ford-ai-service/mise.toml`
- Create: `ford-ai-service/mise.dev.toml`
- Create: `ford-ai-service/mise.prod.toml`
- Create: `ford-ai-service/.env.prod.example`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: Existing `.env.example`, Spring `FORD_*` variables, and current Docker Compose interpolation.
- Produces: Default dev selection, explicit prod overlay, Java 25 management, and production variable validation.

- [ ] **Step 1: Add the early default environment selector**

Create `ford-ai-service/.miserc.toml`:

```toml
env = ["dev"]
```

- [ ] **Step 2: Add shared mise tool configuration**

Create `ford-ai-service/mise.toml`:

```toml
[tools]
java = "25"
```

- [ ] **Step 3: Add the development overlay**

Create `ford-ai-service/mise.dev.toml`:

```toml
[env]
FORD_RUNTIME_ENV = "development"
COMPOSE_PROJECT_NAME = "ford-service-dev"
_.file = { path = ".env", redact = true }
```

- [ ] **Step 4: Add the production overlay without secrets**

Create `ford-ai-service/mise.prod.toml`:

```toml
[env]
FORD_RUNTIME_ENV = "production"
COMPOSE_PROJECT_NAME = "ford-service-prod"
FORD_DATABASE_URL = { required = "Set the production JDBC URL before running prod tasks" }
FORD_DATABASE_USERNAME = { required = "Set the production database username before running prod tasks" }
FORD_DATABASE_PASSWORD = { required = "Set the production database password before running prod tasks", redact = true }
```

The file validates only the core database contract. Optional Meta, AI, ingestion, outbox, and telemetry variables remain conditional on the enabled feature and are supplied externally.

- [ ] **Step 5: Add a non-secret production environment template**

Create `ford-ai-service/.env.prod.example`:

```dotenv
# Copy to .env.prod only for local production-like testing.
# Production deployments should inject these values from a secret manager or CI.
FORD_DATABASE_URL=jdbc:postgresql://db.example.invalid:5432/ford_service
FORD_DATABASE_USERNAME=ford_service
FORD_DATABASE_PASSWORD=replace-with-secret

FORD_AI_CLOUD_ENABLED=false
FORD_AI_LOCAL_ENABLED=true
FORD_AI_LOCAL_BASE_URL=http://ollama.example.invalid:11434
FORD_AI_LOCAL_MODEL_VERSION=llama3.2
FORD_AI_EMBEDDING_MODEL_VERSION=nomic-embed-text
FORD_INGESTION_ENABLED=false
FORD_OUTBOX_ENABLED=false
FORD_OTEL_TRACES_ENDPOINT=http://otel-collector.example.invalid:4318/v1/traces
```

- [ ] **Step 6: Ignore machine-local mise overlays and production dotenv files**

Append to the repository `.gitignore`:

```gitignore
# Local mise environment overrides
mise.local.toml
mise.*.local.toml
```

The existing `.env.*` rule already ignores `.env.prod`; retain the exception for `.env.example` and add an exception for `.env.prod.example`.

- [ ] **Step 7: Run the contract test**

Run:

```bash
node --test scripts/mise-contract.test.mjs
```

Expected: The configuration-related assertions pass; grouped task file assertions remain red until Task 3.

- [ ] **Step 8: Commit the configuration layer**

```bash
git add .gitignore ford-ai-service/.miserc.toml ford-ai-service/mise.toml ford-ai-service/mise.dev.toml ford-ai-service/mise.prod.toml ford-ai-service/.env.prod.example
git commit -m "build: add mise environment overlays"
```

### Task 3: Add grouped executable mise tasks

**Files:**
- Create: `ford-ai-service/.mise/tasks/app/run`
- Create: `ford-ai-service/.mise/tasks/app/build`
- Create: `ford-ai-service/.mise/tasks/test/unit`
- Create: `ford-ai-service/.mise/tasks/test/integration`
- Create: `ford-ai-service/.mise/tasks/test/check`
- Create: `ford-ai-service/.mise/tasks/test/ai-evaluation`
- Create: `ford-ai-service/.mise/tasks/env/check`
- Create: `ford-ai-service/.mise/tasks/env/list`
- Create: `ford-ai-service/.mise/tasks/docker/up`
- Create: `ford-ai-service/.mise/tasks/docker/down`
- Create: `ford-ai-service/.mise/tasks/docker/build`
- Create: `ford-ai-service/.mise/tasks/docker/config`
- Create: `ford-ai-service/.mise/tasks/docker/ps`
- Create: `ford-ai-service/.mise/tasks/docker/logs`
- Create: `ford-ai-service/.mise/tasks/observability/up`
- Create: `ford-ai-service/.mise/tasks/observability/down`
- Create: `ford-ai-service/.mise/tasks/observability/config`
- Create: `ford-ai-service/.mise/tasks/observability/ps`
- Create: `ford-ai-service/.mise/tasks/observability/logs`
- Create: `ford-ai-service/.mise/tasks/load/gate`
- Create: `ford-ai-service/.mise/tasks/doctor`

**Interfaces:**
- Consumes: `MISE_PROJECT_ROOT`, selected overlay variables, Gradle Wrapper, Docker Compose, and the existing root load-gate script.
- Produces: Namespaced tasks such as `test:unit`, `docker:up`, and `observability:logs`.

- [ ] **Step 1: Add application and test tasks**

Create the following executable Bash tasks:

`ford-ai-service/.mise/tasks/app/run`

```bash
#!/usr/bin/env bash
#MISE description="Run ford-service with Spring Boot"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec ./gradlew bootRun --no-daemon
```

`ford-ai-service/.mise/tasks/app/build`

```bash
#!/usr/bin/env bash
#MISE description="Build the Spring Boot jar"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec ./gradlew bootJar --no-daemon
```

`ford-ai-service/.mise/tasks/test/unit`

```bash
#!/usr/bin/env bash
#MISE description="Run unit tests"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec ./gradlew test --no-daemon --no-configuration-cache
```

`ford-ai-service/.mise/tasks/test/integration`

```bash
#!/usr/bin/env bash
#MISE description="Run Testcontainers integration tests"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec ./gradlew integrationTest --no-daemon --no-configuration-cache
```

`ford-ai-service/.mise/tasks/test/check`

```bash
#!/usr/bin/env bash
#MISE description="Run the complete Gradle verification gate"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec ./gradlew clean check --no-daemon --no-configuration-cache
```

`ford-ai-service/.mise/tasks/test/ai-evaluation`

```bash
#!/usr/bin/env bash
#MISE description="Run deterministic AI evaluation tests"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec ./gradlew test --tests '*AiRegressionEvaluationTest' --no-daemon --no-configuration-cache
```

- [ ] **Step 2: Add environment diagnostics**

Create `ford-ai-service/.mise/tasks/env/check`:

```bash
#!/usr/bin/env bash
#MISE description="Validate the selected environment contract without printing values"
set -euo pipefail

profile="${FORD_RUNTIME_ENV:-development}"
if [[ "$profile" == "development" ]]; then
  [[ -f "$MISE_PROJECT_ROOT/.env" ]] || {
    echo "Missing $MISE_PROJECT_ROOT/.env; copy .env.example first." >&2
    exit 1
  }
elif [[ "$profile" == "production" ]]; then
  missing=()
  for name in FORD_DATABASE_URL FORD_DATABASE_USERNAME FORD_DATABASE_PASSWORD; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  if ((${#missing[@]} > 0)); then
    printf 'Missing required production variables: %s\n' "${missing[*]}" >&2
    exit 1
  fi
else
  printf 'Unsupported FORD_RUNTIME_ENV: %s\n' "$profile" >&2
  exit 1
fi

printf 'Environment contract present: %s\n' "$profile"
```

Create `ford-ai-service/.mise/tasks/env/list`:

```bash
#!/usr/bin/env bash
#MISE description="List environment variable names and presence only"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"

names=(
  FORD_DATABASE_URL
  FORD_DATABASE_USERNAME
  FORD_DATABASE_PASSWORD
  FORD_AI_CHAT_PROVIDER
  FORD_AI_CLOUD_ENABLED
  FORD_AI_LOCAL_ENABLED
  OPENAI_API_KEY
  FORD_META_APP_SECRET
  FORD_META_VERIFY_TOKEN
  FORD_INGESTION_ENABLED
  FORD_OUTBOX_ENABLED
  FORD_OTEL_TRACES_ENDPOINT
)

for name in "${names[@]}"; do
  status=missing
  [[ -n "${!name:-}" ]] && status=set
  printf '%-40s %s\n' "$name" "$status"
done
```

- [ ] **Step 3: Add application Docker tasks**

Create these files:

`ford-ai-service/.mise/tasks/docker/up`

```bash
#!/usr/bin/env bash
#MISE description="Build and start the local Ford Service Compose stack"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec docker compose up --build -d
```

`ford-ai-service/.mise/tasks/docker/down`

```bash
#!/usr/bin/env bash
#MISE description="Stop the local Ford Service Compose stack"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec docker compose down
```

`ford-ai-service/.mise/tasks/docker/build`

```bash
#!/usr/bin/env bash
#MISE description="Build the local Ford Service Compose images"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec docker compose build
```

`ford-ai-service/.mise/tasks/docker/config`

```bash
#!/usr/bin/env bash
#MISE description="Validate the local Ford Service Compose configuration"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec docker compose config --quiet
```

`ford-ai-service/.mise/tasks/docker/ps`

```bash
#!/usr/bin/env bash
#MISE description="Show local Ford Service Compose status"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
exec docker compose ps
```

`ford-ai-service/.mise/tasks/docker/logs`

```bash
#!/usr/bin/env bash
#MISE description="Follow local Ford Service Compose logs"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"
args=(logs --tail="${FORD_DOCKER_LOG_TAIL:-100}" -f)
if (($# > 0)); then
  args+=("$@")
fi
exec docker compose "${args[@]}"
```

- [ ] **Step 4: Add observability Compose tasks**

Each observability task must change into
`$MISE_PROJECT_ROOT/deployment/observability/langfuse` before invoking Compose
so the stack’s ignored local `.env` is discovered correctly.

Create `up`:

```bash
#!/usr/bin/env bash
#MISE description="Start the local Langfuse and OTel stack"
set -euo pipefail
cd "$MISE_PROJECT_ROOT/deployment/observability/langfuse"
exec docker compose up -d
```

Create `down`:

```bash
#!/usr/bin/env bash
#MISE description="Stop the local Langfuse and OTel stack"
set -euo pipefail
cd "$MISE_PROJECT_ROOT/deployment/observability/langfuse"
exec docker compose down
```

Create `config`:

```bash
#!/usr/bin/env bash
#MISE description="Validate the local Langfuse and OTel Compose configuration"
set -euo pipefail
cd "$MISE_PROJECT_ROOT/deployment/observability/langfuse"
exec docker compose config --quiet
```

Create `ps`:

```bash
#!/usr/bin/env bash
#MISE description="Show local Langfuse and OTel Compose status"
set -euo pipefail
cd "$MISE_PROJECT_ROOT/deployment/observability/langfuse"
exec docker compose ps
```

Create `logs`:

```bash
#!/usr/bin/env bash
#MISE description="Follow local Langfuse and OTel Compose logs"
set -euo pipefail
cd "$MISE_PROJECT_ROOT/deployment/observability/langfuse"
args=(logs --tail="${FORD_DOCKER_LOG_TAIL:-100}" -f)
if (($# > 0)); then
  args+=("$@")
fi
exec docker compose "${args[@]}"
```

- [ ] **Step 5: Add load gate and doctor tasks**

Create `ford-ai-service/.mise/tasks/load/gate`:

```bash
#!/usr/bin/env bash
#MISE description="Run the MVC HTTP load gate"
set -euo pipefail
exec node "$MISE_PROJECT_ROOT/../scripts/mvc-load-gate.mjs" "$@"
```

Create `ford-ai-service/.mise/tasks/doctor`:

```bash
#!/usr/bin/env bash
#MISE description="Check the local Ford Service toolchain"
set -euo pipefail
cd "$MISE_PROJECT_ROOT"

command -v java >/dev/null || { echo "java is missing from PATH" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker is missing from PATH" >&2; exit 1; }
docker compose version >/dev/null || { echo "docker compose is unavailable" >&2; exit 1; }
docker info >/dev/null || { echo "Docker daemon is unavailable" >&2; exit 1; }

java --version | head -1
docker compose version
printf 'Docker daemon: ready\n'
```

- [ ] **Step 6: Mark every task executable**

Run:

```bash
find ford-ai-service/.mise/tasks -type f -exec chmod +x {} +
```

- [ ] **Step 7: Validate task discovery**

Run:

```bash
mise -C ford-ai-service tasks validate
mise -C ford-ai-service tasks ls --local --name-only
```

Expected: validation succeeds and names include `app:run`, `test:unit`, `docker:up`, `observability:up`, `load:gate`, and `doctor`.

- [ ] **Step 8: Run the contract test and commit**

Run:

```bash
node --test scripts/mise-contract.test.mjs
```

Expected: PASS.

```bash
git add ford-ai-service/.mise/tasks
git commit -m "build: add grouped mise tasks"
```

### Task 4: Document the workflow and environment contract

**Files:**
- Modify: `ford-ai-service/README.md`
- Modify: `ford-ai-service/docs/operations.md`
- Modify: `docs/ci-cd.md`
- Modify: `ford-ai-service/deployment/README.md`
- Modify: `ford-ai-service/deployment/observability/langfuse/README.md`

**Interfaces:**
- Consumes: Final mise task names and environment-file locations from Tasks 2 and 3.
- Produces: User-facing setup and operational documentation that does not expose credentials.

- [ ] **Step 1: Replace direct local commands in the service README**

Add a mise setup section after the architecture decisions:

````markdown
## Local development with mise

From this directory, trust the project once and copy the development template:

```bash
mise trust
cp .env.example .env
mise run doctor
```

The service defaults to the `dev` mise environment through `.miserc.toml`.
Useful grouped tasks:

```bash
mise run app:run
mise run test:unit
mise run test:integration
mise run test:check
mise run docker:up
mise run docker:logs
mise run observability:up
mise run load:gate
```

Inspect available tasks with `mise tasks ls`.

For production-like validation, select the production overlay explicitly and
provide its required variables through the shell, CI, or a secret manager:

```bash
mise -E prod run env:check
mise -E prod run docker:config
```

Do not commit `.env`, `.env.prod`, or any provider credentials. Use
`.env.prod.example` only as a variable-name template.
````

- [ ] **Step 2: Update operations documentation**

Add this section after the existing configuration section in
`ford-ai-service/docs/operations.md`:

````markdown
## mise environments and task groups

`ford-ai-service/.miserc.toml` selects `dev` by default, so local commands do
not need an environment flag. Use `mise -E prod ...` only when validating the
production overlay.

```bash
mise run env:check
mise run docker:up
mise run docker:ps
mise run observability:up
mise run observability:logs
```

Production requires database variables from the caller, CI, or a secret
manager:

```bash
mise -E prod run env:check
mise -E prod run docker:config
```

`.env` is ignored and is used only for local development. An optional ignored
`mise.prod.local.toml` may load `.env.prod` for manual production-like testing;
it must not be used as the production secret source.
````

Keep the existing variable list and secret-handling guidance unchanged.

- [ ] **Step 3: Update CI/local-check documentation**

Add the equivalent local commands to the Local checks section:

```bash
cd ford-ai-service
mise run test:check
mise run test:ai-evaluation
mise run docker:config
mise run observability:config
```

Keep the existing direct Gradle commands in CI documentation where they match
GitHub Actions exactly; mise is the local developer interface, not a required
replacement for the CI runner contract.

- [ ] **Step 4: Update observability deployment documentation**

Replace the primary startup examples in
`ford-ai-service/deployment/README.md` and
`ford-ai-service/deployment/observability/langfuse/README.md` with:

```bash
cd ford-ai-service
mise run observability:up
mise run observability:ps
mise run observability:logs
```

Keep the direct Compose command as a debugging fallback and retain the existing
warning that this stack is for local development and single-VM pilot use, not
high-availability production traffic.

- [ ] **Step 5: Run documentation contract tests**

Run:

```bash
node --test scripts/mise-contract.test.mjs scripts/operations-contract.test.mjs scripts/observability-contract.test.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit the documented workflow**

```bash
git add ford-ai-service/README.md ford-ai-service/docs/operations.md docs/ci-cd.md ford-ai-service/deployment/README.md ford-ai-service/deployment/observability/langfuse/README.md
git commit -m "docs: document mise development and production workflows"
```

### Task 5: Run final verification and inspect the branch

**Files:**
- Modify: None unless verification exposes an implementation defect.

**Interfaces:**
- Consumes: The complete mise configuration, tasks, docs, and existing project checks.
- Produces: Evidence that default dev selection, explicit prod selection, task discovery, Compose syntax, and project tests work.

- [ ] **Step 1: Verify environment selection without printing secrets**

Run from `ford-ai-service`:

```bash
mise config
mise -E dev config
mise -E prod config
mise run env:list
```

Expected: `mise config` includes the default dev overlay, explicit prod config
loads `mise.prod.toml`, and `env:list` prints names with `set`/`missing` status
only.

- [ ] **Step 2: Validate task graph and Compose files**

Run:

```bash
mise tasks validate
mise tasks deps
mise run docker:config
mise run observability:config
```

Expected: task validation and both Compose config checks succeed. If Docker is
unavailable, record that limitation and continue with non-Docker checks.

- [ ] **Step 3: Run repository contract tests**

Run from the repository root:

```bash
node --test scripts/mise-contract.test.mjs scripts/workflow-contract.test.mjs scripts/operations-contract.test.mjs scripts/observability-contract.test.mjs
```

Expected: PASS.

- [ ] **Step 4: Run the Java verification gate**

Run from `ford-ai-service`:

```bash
mise run test:check
```

Expected: Gradle verification passes. If Docker/Testcontainers is unavailable,
use the documented Colima recovery path and record the exact remaining failure.

- [ ] **Step 5: Inspect final changes**

Run:

```bash
git diff --check HEAD~5..HEAD
git status --short
git log --oneline -6
```

Expected: no whitespace errors, only intended files changed, and no `.env` or
other credential file is tracked.

- [ ] **Step 6: Report evidence**

Report the branch, commits, task examples, verification commands, pass/fail
results, and any Docker or external-secret limitation without reproducing
secret values.
