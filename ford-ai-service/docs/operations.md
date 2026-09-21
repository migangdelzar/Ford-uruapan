# Operations

## Local services

`docker-compose.yml` starts PostgreSQL with pgvector and Redis. Liquibase owns
the schema. Do not edit production tables manually; create a new YAML changeSet.

## Configuration

Use environment variables or a deployment profile; keep secrets outside Git.

- `FORD_DATABASE_URL`, `FORD_DATABASE_USERNAME`, `FORD_DATABASE_PASSWORD`
- `FORD_META_APP_SECRET`, `FORD_META_VERIFY_TOKEN`
- `FORD_META_DELIVERY_ENABLED`, channel delivery endpoints and access tokens
- `FORD_AI_CHAT_PROVIDER`, `FORD_AI_EMBEDDING_PROVIDER`
- `FORD_AI_CLOUD_ENABLED`, `FORD_AI_LOCAL_ENABLED`, provider base URLs and model names
- `FORD_INGESTION_ENABLED`, `FORD_TENANT_ID`, `FORD_INGESTION_CRON`
- `FORD_CATALOG_ENDPOINT`, `FORD_INVENTORY_ENDPOINT`, `FORD_SCRAPER_ENDPOINT`
- `FORD_MANUAL_CSV_PATH`, `FORD_INGESTION_MAX_ATTEMPTS`

See [integration-setup.md](integration-setup.md) and [.env.example](.env.example)
for the complete local contract. Never commit `.env` or paste provider keys into
source control, tickets, or chat.

The default ingestion flag is disabled. Enable it only after official Ford
endpoints or authorized scraper endpoints and credentials are configured.

## mise environments and task groups

`ford-ai-service/.miserc.toml` selects `dev` by default, so local commands do
not need an environment flag. Use `mise -E prod ...` only when validating the
production overlay.

```bash
mise run env:check
mise run env:list
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

## Fresh-host setup

Install mise once with the official installer, then run setup from this
directory:

```bash
curl -fsSL https://mise.run | sh
mise trust
mise run setup:check
mise run setup:host
mise run setup:host -- --apply
mise run setup:docker -- --apply
mise run setup:env
```

`setup:host` is a dry-run unless `--apply` is supplied. Fedora and Oracle
Linux use Docker’s RPM repository through `dnf`, install rootless prerequisites,
and configure a per-user Docker systemd service. They do not use the Docker
group. On Fedora and Oracle Linux, `newuidmap` and `newgidmap` come from
`shadow-utils`. Rootless Docker requires those commands and at least 65,536
subordinate UIDs and GIDs in `/etc/subuid` and `/etc/subgid`.

macOS uses Homebrew and the `emme` Colima profile. Colima provides the Docker
Linux VM; its Docker daemon runs as root inside that VM, while the macOS host
does not run a Docker daemon. The setup preserves existing profile data and
does not delete or recreate profiles automatically.

`setup:check` reports the active Docker context and socket. The runtime helper
passes the user-scoped rootless socket to Gradle/Testcontainers on Linux and
the Colima socket plus in-VM override on macOS. If a rootful Docker service is
already active on Linux, setup stops with migration instructions instead of
disabling or deleting it.

`setup:env` copies `.env.example` to a private `.env` only when `.env` is
absent. It never overwrites an existing file or creates production env files.

`.env` is ignored and is used only for local development. An optional ignored
`mise.prod.local.toml` may load `.env.prod` for manual production-like testing;
it must not be used as the production secret source. The local override has
this shape:

```toml
[env]
_.file = { path = ".env.prod", redact = true }
```

## Health and metrics

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`
- `GET /actuator/modulith` (operator role required)

Metrics use bounded provider, tool and lead-band labels. Prompts, completions,
raw webhook bodies, phone numbers, email addresses and financial details must
not be placed in logs or metric labels. Use `DefaultPiiRedactor` before any
diagnostic payload is exported.

## Provider and safety controls

Cloud/local model choice is policy-driven and never silently falls back across
privacy boundaries. Sensitive requests stay local when a local provider is
enabled. Models receive read-only tools only; finance calculations and lead
scores remain deterministic.

To disable AI while keeping webhook acknowledgement and operator access,
disable both AI provider flags and route the conversation to the human queue.
To disable ingestion, set `FORD_INGESTION_ENABLED=false` and leave canonical
data available with its freshness status.

Ingestion runs only from the scheduled coordinator. Accepted catalog records
are idempotently upserted into `vehicle_model`, `vehicle_variant`, and
`vehicle_price`; inventory records upsert `vehicle_unit`. Every write carries
the tenant, source identity, freshness status, raw source JSON, and sync-run
identity. Apply schema changes through Liquibase only.

For local Testcontainers failures on Colima, use the persisted `emme` profile
and run `ford-colima-recover`; it restores the Docker context and starts the
profile before tests. The Gradle test tasks also set
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, preventing Ryuk
from attempting to mount the macOS socket path inside the Colima VM.

## Rollback

Deploy the previous application image, preserve the database volume, and do not
roll back Liquibase changes independently of the application version. Replay
pending outbox events only after verifying provider idempotency keys.
