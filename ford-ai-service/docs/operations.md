# Operations

## Local services

`docker-compose.yml` starts PostgreSQL with pgvector and Redis. Liquibase owns
the schema. Do not edit production tables manually; create a new YAML changeSet.

## Configuration

Use environment variables or a deployment profile; keep secrets outside Git.

- `FORD_DATABASE_URL`, `FORD_DATABASE_USERNAME`, `FORD_DATABASE_PASSWORD`
- `FORD_SECURITY_JWT_ENABLED`, `FORD_SECURITY_ISSUER_URI`, `FORD_SECURITY_AUDIENCE`
- `FORD_META_APP_SECRET`, `FORD_META_VERIFY_TOKEN`
- `FORD_META_DELIVERY_ENABLED`, channel delivery endpoints and access tokens
- `FORD_HUMAN_HANDOFF_ENABLED` (`false` enables bot-only mode)
- `FORD_MEMORY_ENABLED`, `FORD_MEMORY_RECENT_MESSAGE_LIMIT`
- `FORD_MEMORY_SUMMARY_MAX_CHARS`, `FORD_MEMORY_FACT_LIMIT`, `FORD_MEMORY_FACT_MAX_CHARS`
- `FORD_MEMORY_SEMANTIC_ENABLED`, `FORD_MEMORY_SEMANTIC_LIMIT`, `FORD_MEMORY_CACHE_ENABLED`
- `FORD_AI_CHAT_PROVIDER`, `FORD_AI_EMBEDDING_PROVIDER`
- `FORD_AI_CLOUD_ENABLED`, `FORD_AI_LOCAL_ENABLED`, provider base URLs and model names
- `FORD_AI_LOG_PROVIDER_ERRORS` (opt-in bounded provider error details)

For OpenAI-compatible providers, `FORD_AI_CLOUD_BASE_URL` must include the
provider API version because the Spring AI SDK appends `/chat/completions`
directly to it. For example, OpenRouter uses:

```dotenv
FORD_AI_CLOUD_BASE_URL=https://openrouter.ai/api/v1
```

The same shape works for the official OpenAI endpoint:
`https://api.openai.com/v1`.

- `FORD_INGESTION_ENABLED`, `FORD_TENANT_ID`, `FORD_INGESTION_CRON`
- `FORD_CATALOG_ENDPOINT`, `FORD_INVENTORY_ENDPOINT`, `FORD_SCRAPER_ENDPOINT`
- `FORD_MANUAL_CSV_PATH`, `FORD_INGESTION_MAX_ATTEMPTS`

See [integration-setup.md](integration-setup.md) and [.env.example](.env.example)
for the complete local contract. Never commit `.env` or paste provider keys into
source control, tickets, or chat.

The Spring profile is selected with `SPRING_PROFILES_ACTIVE=dev` or
`SPRING_PROFILES_ACTIVE=prod`; environment variables override both profile and
common YAML values. The default profile is `dev`.

API authentication uses Spring Security's OAuth2 Resource Server JWT support.
Set `FORD_SECURITY_JWT_ENABLED=true` only when the configured issuer is
available and publishes OIDC discovery/JWKS metadata. The issuer validates
`iss`, `exp`, and `nbf`; the configured audience validates `aud`. Tenant scope
is carried by `tenant_id` or `tenant_ids` claims and is checked as a request
attribute at the existing tenant authorization boundary. A `roles` claim with
`OPERATOR`, `MANAGER`, or `ADMIN` values becomes `ROLE_*`; operation scopes
remain explicit `SCOPE_*` authorities. Webhooks remain unauthenticated at the
HTTP bearer layer because Meta requests are authenticated with HMAC,
verify-token, and channel-binding checks.

The current operator scopes are:

- `operator:read` for read-only operator endpoints
- `handoff:manage` for handoff commands

Spring Security 7 method security is enabled with `@EnableMethodSecurity`, and
endpoint-specific rules use `@PreAuthorize`. This is the current
AuthorizationManager-based replacement for the deprecated
`@EnableGlobalMethodSecurity`; `@PreAuthorize` itself remains supported.

Bot-only mode is controlled by:

```dotenv
FORD_HUMAN_HANDOFF_ENABLED=false
```

This prevents assistant-created `handoff_ticket` rows and `HandoffRequested`
events. The workflow persists the deterministic bot fallback and publishes the
normal outbound delivery request instead. Set it back to `true` to restore
human escalation behavior.

Conversation memory is enabled by default and is persisted in PostgreSQL. The
session scope is tenant, channel, provider account, and participant, so the
same person on two provider numbers cannot share memory accidentally. The
bounded recent transcript is injected into each assistant request; Redis is
not the source of truth. Semantic fact retrieval is disabled by default and
should only be enabled after the configured embedding model is compatible with
the pgvector memory table. Memory read, embedding, and provider failures are
fail-open for webhook processing.

The default ingestion flag is disabled. Enable it only after official Ford
endpoints or authorized scraper endpoints and credentials are configured.

## mise environments and task groups

`ford-ai-service/.miserc.toml` selects `dev` by default, so local commands do
not need an environment flag. Use `mise -E prod ...` only when validating the
production overlay.

```bash
mise run env:check
mise run env:list
mise run docker:deps:up
mise run docker:deps:ps
mise run docker:deps:down
mise run db:reset -- --confirm
mise run db:reset-volume -- --confirm
mise run app:run
mise run docker:up
mise run docker:ps
mise run observability:up
mise run observability:logs
```

`mise run test:unit` is the non-integration verification command for the
application configuration. The separate `mise run test:integration` task uses
Testcontainers and is not part of this verification.

Use the host-JVM mode when only PostgreSQL and Redis should run in containers:

```bash
mise run docker:deps:up
mise run app:run
```

Use the full container mode when PostgreSQL, Redis, and Spring Boot should all
run in Compose:

```bash
mise run docker:up
```

Host-JVM observability uses host loopback addresses. Keep these values in the
private `.env` used by `mise run app:run`:

```dotenv
FORD_OTEL_TRACES_EXPORT_ENABLED=false
FORD_AI_LOCAL_BASE_URL=http://127.0.0.1:11434
FORD_OTEL_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces
```

Compose uses the separate `*_DOCKER` values from `.env.example`, so
`host.docker.internal` is not used by a JVM running directly on Linux. Trace
export is disabled by default. If you want observability, set
`FORD_OTEL_TRACES_EXPORT_ENABLED=true`, start the local collector, and then
start the application:

```bash
mise run observability:up
mise run app:run
```

The HTTP exchange filter logs request arrival and completion with method, path,
status, duration, content types, and `X-Request-Id`. It intentionally does not
log query values, request/response bodies, authorization headers, cookies, or
Meta signatures.

`docker:deps:down` stops only PostgreSQL and Redis and preserves the named
PostgreSQL volume.

For a clean local database, stop the application first. The schema reset keeps
the named volume but deletes all application tables and data; Liquibase
recreates them when the application starts:

```bash
mise run db:reset -- --confirm
mise run app:run
```

The full reset deletes this Compose project's PostgreSQL volumes, recreates
PostgreSQL and Redis, and also requires explicit confirmation:

```bash
mise run db:reset-volume -- --confirm
mise run app:run
```

Both resets delete channel bindings. The default `dev` profile recreates the
single configured WhatsApp binding automatically when `FORD_META_PHONE_ID` is a
numeric phone-number ID. Set `FORD_META_AUTO_BINDING_ENABLED=false` when
managing bindings manually or when using multiple numbers; use
`mise exec -- ./bin/create-meta-binding.sh <PHONE_NUMBER_ID>` for those rows.

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
