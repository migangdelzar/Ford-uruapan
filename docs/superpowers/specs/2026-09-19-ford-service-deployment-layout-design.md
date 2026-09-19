# ford-service Deployment Layout Design

| Field | Value |
|---|---|
| Date | 2026-09-19 |
| Status | Approved in conversation |
| Branch | `feat/ford-service-mvp` |
| Scope | Co-locate service deployment configuration under `ford-ai-service/` |

## Decision

Keep the service and its deployment configuration in the same repository and
service directory. Move the existing observability deployment tree from
`deployment/observability/` to `ford-ai-service/deployment/observability/`.

The service directory remains the ownership boundary for the runtime artifact,
Docker build context, local Compose configuration, operational documentation,
and observability support configuration.

GitHub workflow files remain under the repository-root `.github/workflows/`
directory because GitHub discovers Actions workflows from that location. They
will reference deployment files through their new `ford-ai-service/` paths.

## Target layout

```text
ford-ai-service/
  Dockerfile
  docker-compose.yml
  docs/
  deployment/
    observability/
      dashboards/
      langfuse/
      otel-collector/
```

The root `deployment/` directory will no longer exist after the move.

## Changes

1. Move all tracked files below `deployment/observability/` into
   `ford-ai-service/deployment/observability/`.
2. Add a service deployment README that identifies the local observability
   stack, required environment files, and the Compose entry point.
3. Update repository documentation and workflow references to the new paths.
4. Preserve Docker images, ports, environment variable names, volumes,
   healthchecks, and service behavior.
5. Leave `.github/workflows/` at the repository root and leave GitHub Actions
   checkout/build semantics unchanged except for path references.

## Verification

- Confirm no tracked files remain under root `deployment/`.
- Search tracked files for stale `deployment/observability` references.
- Run workflow and observability contract tests.
- Run the service Gradle verification suite appropriate to the local Docker
  environment.
- Run `git diff --check` and inspect the final rename diff.

## Non-goals

- No merge into `main` in this change.
- No change to deployment providers, environments, secrets, ports, images, or
  runtime topology.
- No relocation of `.github/workflows/`, which must remain at repository root.
