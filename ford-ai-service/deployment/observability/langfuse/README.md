# Self-hosted Langfuse

This Compose project runs a local or pilot Langfuse v4 stack for Ford Service.
It is deliberately separate from the Ford application Compose file so
Langfuse can be upgraded, backed up, and restarted without stopping the
customer-facing service.

## Start

```bash
cd ford-ai-service
mise run observability:env
mise run observability:up
mise run observability:ps
mise run observability:logs
```

`observability:up` also runs the environment bootstrap automatically. It creates
`deployment/observability/langfuse/.env` once, with private random local
credentials, and never overwrites an existing file. The generated Langfuse
project credentials are used to build the collector's `LANGFUSE_AUTH_HEADER`.
Keep this file private; it is ignored by Git.

Open `http://localhost:3300` after the Web container is ready. The application
can send traces to `http://localhost:4318/v1/traces`.

The `LANGFUSE_AUTH_HEADER` value is the HTTP Basic credential containing the
generated Langfuse public key and secret key. For production, inject all
credentials through a secret manager instead of using this local bootstrap.

For direct debugging, run `docker compose` from this directory. The mise tasks
use the same Compose file and local `.env` automatically.

## Operations

- PostgreSQL, ClickHouse, Redis, and MinIO use named persistent volumes.
- Keep all services except Langfuse Web and the local MinIO API on a private
  network in a shared environment.
- Back up PostgreSQL, ClickHouse data, and MinIO before upgrades.
- Test restores on a separate host before changing production data.
- Set trace retention according to the approved privacy policy.
- Pin image versions and review release notes before upgrades.
- Export failure is fail-open for the Ford application; inspect collector
  queue and exporter metrics when traces are missing.

This Compose profile is for local development and a single-VM pilot. Use a
high-availability deployment for production traffic.
