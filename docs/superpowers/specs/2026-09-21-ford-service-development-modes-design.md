# Ford Service Development Modes Design

## Goal

Provide two explicit local development modes under `ford-ai-service`:

1. Host-JVM mode: PostgreSQL and Redis run in Compose while Spring Boot runs
   directly through the host JVM.
2. Container mode: PostgreSQL, Redis, and Spring Boot all run in Compose.

## Decision

Keep the existing `ford-ai-service/docker-compose.yml` as the single source of
truth. Compose service selection will provide the dependency-only mode; no
second Compose file will be introduced.

The existing `docker:up` task remains the container mode and continues to
build/start the complete stack. New grouped tasks will manage dependencies:

```text
docker:deps:up       Start PostgreSQL and Redis only
docker:deps:down     Stop the dependency containers
docker:deps:ps       Show dependency container status
```

The existing `app:run` task remains the host-JVM mode's Spring Boot command.
The two workflows are therefore:

```bash
mise run docker:deps:up
mise run app:run
```

or:

```bash
mise run docker:up
```

## Boundaries and behavior

- Dependency tasks invoke the existing Compose compatibility helper so rootless
  Linux sockets and macOS Colima sockets continue to work.
- `docker:deps:up` starts only `postgres` and `redis`; it does not build or
  start `ford-service`.
- `docker:up` behavior remains unchanged: it builds and starts all services.
- `app:run` continues to use the mise-selected Java version and environment
  variables loaded by the active mise environment.
- `docker:deps:down` stops the Compose project without deleting named volumes.
- `docker:deps:ps` reports the Compose project status; it does not mutate state.

## Testing

- Contract tests verify the new task files, descriptions, service selection,
  Compose helper usage, and preservation of the full-stack task.
- `mise tasks validate` verifies task discovery and executable task structure.
- Existing Compose configuration and shell syntax checks remain required.
