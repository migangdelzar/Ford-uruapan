# Ford Service mise Environment and Task Architecture

| Field | Value |
|---|---|
| Date | 2026-09-20 |
| Status | Proposed for written review |
| Branch | `feat/ford-service-mvp` |
| Scope | Local tooling, environment selection, task organization, and Docker workflows under `ford-ai-service/` |

## Goal

Make `ford-ai-service` runnable through a discoverable mise task interface while
keeping Java, Gradle, Docker, observability, and environment configuration
consistent across developer machines and CI.

The default developer command must not require an environment flag:

```bash
mise run test:unit
mise run docker:up
```

Production-sensitive commands must remain explicit:

```bash
mise -E prod run env:check
mise -E prod run docker:config
```

## Architecture

```text
mise command
   |
   v
.miserc.toml                  early selection: dev by default
   |
   +--> mise.toml              shared Java 25 and task settings
   |
   +--> mise.dev.toml          local dotenv + safe dev defaults
   |
   +--> mise.prod.toml         safe prod defaults + required variables
   |       |
   |       +--> mise.prod.local.toml (ignored, optional local dotenv)
   |
   v
.mise/tasks/<capability>/<task>
   |
   +--> Gradle / Java
   +--> Docker Compose
   +--> observability Compose
   +--> Node load-gate scripts
```

mise configuration selects the developer or production overlay. The task
scripts then consume the resolved environment and invoke the existing project
tools. Spring remains the application configuration boundary: `application.yml`
continues to bind `FORD_*` and provider variables, while mise supplies those
variables to local commands and Docker Compose. mise environments are not
treated as a replacement for Spring profiles.

## Configuration files

### `.miserc.toml`

Commit a service-local early configuration file:

```toml
env = ["dev"]
```

This makes `dev` the default when commands run from `ford-ai-service`. A
production invocation uses `mise -E prod ...` and overrides the default. The
setting belongs in `.miserc.toml` because environment selection happens before
`mise.toml` is discovered.

### `mise.toml`

The shared file will contain:

- Java 25 as the project tool version.
- Task output and task-discovery settings shared by all environments.
- No secrets and no environment-specific endpoints.

### `mise.dev.toml`

The development overlay will:

- Load the ignored `ford-ai-service/.env` through `env._.file`.
- Mark loaded values as redacted in mise task output.
- Set a development Compose project name to avoid collisions.
- Set only safe local defaults needed by task scripts.

The existing `.env.example` remains the starting template for a developer's
`.env` file. The Compose file will continue to use an explicit environment
allowlist rather than passing every shell variable into the application
container.

### `mise.prod.toml`

The production overlay will:

- Contain no secret values.
- Set a production Compose project name and non-secret task defaults.
- Validate core runtime variables such as database URL, database username, and
  database password before production-oriented tasks run.
- Accept secrets from the caller's environment, CI secret store, or another
  external secret manager.

An optional ignored `mise.prod.local.toml` may load a local `.env.prod` for
manual production-like testing. CI and actual deployment do not depend on that
file. A committed `.env.prod.example` will document the production variable
contract without containing credentials.

## Task organization

Tasks will be executable file tasks under `.mise/tasks/`, grouped by directory.
mise will expose the directory hierarchy as colon-separated task names.

```text
.mise/tasks/
  app/
    run
    build
  test/
    unit
    integration
    check
    ai-evaluation
  env/
    check
    show
  docker/
    up
    down
    build
    config
    ps
    logs
  observability/
    up
    down
    config
    ps
    logs
  load/
    gate
  doctor
```

Examples:

```bash
mise run app:run
mise run app:build
mise run test:unit
mise run test:integration
mise run test:check
mise run docker:up
mise run observability:up
mise run load:gate
mise run doctor
```

The scripts will use `MISE_PROJECT_ROOT` so they work consistently regardless
of the caller's current subdirectory. Docker lifecycle tasks will not include a
volume-deleting cleanup command.

## Environment variable structure

Environment values have three sources, in descending order of authority:

1. External caller or CI environment, especially for production secrets.
2. The selected mise overlay (`mise.dev.toml` or `mise.prod.toml`).
3. The ignored local dotenv file loaded by the selected environment.

The repository will keep variable names aligned with the existing Spring and
Compose contracts:

- Database: `FORD_DATABASE_URL`, `FORD_DATABASE_USERNAME`,
  `FORD_DATABASE_PASSWORD`.
- AI providers: `FORD_AI_*`, `OPENAI_API_KEY`.
- Meta channels: `FORD_META_*`.
- Ingestion and outbox: `FORD_INGESTION_*`, `FORD_OUTBOX_*`.
- Telemetry: `FORD_OTEL_*` and observability stack variables.

No task will print raw environment values. `env:show` will use mise's redacted
view and will be documented as diagnostic output, not a secret export command.

## Docker and observability behavior

The current `docker-compose.yml` remains the local development stack. Tasks
will wrap its existing lifecycle and configuration commands. Observability
tasks will wrap the existing Langfuse/OTel Compose file under
`ford-ai-service/deployment/observability/`.

The production overlay will not falsely treat the local Compose stack as a
production deployment. Production configuration validation may run against
externally supplied dependencies, but a separate production Compose topology
requires a later decision about image publishing, networks, persistent storage,
health checks, and secret injection.

## Documentation and verification

Update service README and operations documentation with:

- mise installation/trust and shell activation requirements;
- default development behavior from `.miserc.toml`;
- production override syntax;
- task group reference;
- `.env` and `.env.prod` handling;
- secret-handling rules.

Verify with:

- `mise config` for dev and prod overlays;
- `mise tasks validate` and grouped task listing;
- redacted environment checks without exposing values;
- `docker compose config --quiet` for the local and observability stacks;
- Gradle unit/integration checks where Docker is available;
- existing Node contract tests;
- `git diff --check`.

## Non-goals

- No credentials committed to mise files or example files.
- No replacement of Spring's application configuration model.
- No deployment, registry, or production infrastructure implementation.
- No destructive Docker volume cleanup task.
- No unrelated Java or application behavior changes.

