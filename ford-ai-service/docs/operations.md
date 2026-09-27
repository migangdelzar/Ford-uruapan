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
- `FORD_MEMORY_ENABLED`, `FORD_MEMORY_RECENT_MESSAGE_LIMIT`, and
  `FORD_MEMORY_PROMPT_RECENT_MESSAGE_LIMIT` (default `8`). The first setting
  preserves the durable recent-memory bound; the second controls how many
  messages are injected into each model prompt.
- `FORD_MEMORY_SUMMARY_MAX_CHARS`, `FORD_MEMORY_FACT_LIMIT`, `FORD_MEMORY_FACT_MAX_CHARS`
- `FORD_MEMORY_SEMANTIC_ENABLED`, `FORD_MEMORY_SEMANTIC_LIMIT`, `FORD_MEMORY_CACHE_ENABLED`
- `FORD_AI_CHAT_PROVIDER`, `FORD_AI_EMBEDDING_PROVIDER`
- `FORD_AI_CLOUD_ENABLED`, `FORD_AI_LOCAL_ENABLED`, provider base URLs and model names
- `FORD_AI_LOG_PROVIDER_ERRORS` (opt-in bounded provider error details)
- `TYPESAFE_API_KEY` (optional Jev classification key; never log or commit it)
- `FORD_AI_CLASSIFICATION_ENABLED`, `FORD_AI_CLASSIFICATION_MODE` (`active`,
  `shadow`, or `disabled`), and `FORD_AI_CLASSIFICATION_PROVIDER`
- `FORD_AI_CLASSIFICATION_MIN_CONFIDENCE`, classifier-specific `FORD_AI_CLASSIFIER_*`
  toggles, and `FORD_AI_CLASSIFICATION_FALLBACK=rules`
- `FORD_AI_GUARDRAILS_ENABLED` and `FORD_AI_SELF_REFINE_ENABLED` (both default
  to `false`); self-refinement is bounded by `FORD_AI_SELF_REFINE_MAX_ATTEMPTS`

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

## Conversation graph and sales escalation

`FORD_CONVERSATION_GRAPH_ENABLED` selects the bounded LangGraph4j inbound
workflow. It defaults to `false` for rollback compatibility. The graph runs one
accepted inbound turn at a time and invokes the existing Spring AI assistant
once; the model, authorized Ford MCP/RAG tools, conversation transcript, lead
journey, and outbox remain owned by their existing application services and
PostgreSQL tables. Graph checkpoints live in the separate `ford_graph` schema
and contain only tenant, conversation, and receipt identifiers plus route
metadata. They are execution checkpoints, not conversation memory or the
system of record. Disable the flag and redeploy to return to the legacy
workflow; do not drop checkpoint or business tables as part of rollback.

With `FORD_HUMAN_HANDOFF_ENABLED=true`, direct requests for a person and HOT
leads enter the high-priority `/api/v1/handoffs` queue. A queued ticket means
the request is waiting; only a successful operator claim means an advisor has
taken the conversation, at which point automated replies pause. Operators need
`operator:read` to list tickets and `handoff:manage` to claim, pause, resume, or
hand back a conversation. Claims are tenant-scoped and version-checked.

For WARM leads, the assistant may offer an optional advisor follow-up. A
follow-up is queued only after an unambiguous customer acceptance; declining
suppresses another offer for that conversation. WARM follow-ups are listed at
`GET /api/v1/follow-ups` and claimed at
`POST /api/v1/follow-ups/{followUpId}/claim?tenantId=...`. Claiming a WARM
follow-up does not pause the bot or claim that an advisor has joined the live
conversation.

For the OCI rollout, use `mise run deploy:flags -- FORD_CONVERSATION_GRAPH_ENABLED
true FORD_HUMAN_HANDOFF_ENABLED true` to set only these allowlisted booleans in
the private deployment config, then deploy through `mise run deploy:app`.
Before a release, inspect both hosts and logs, run `mise run backup:run` and
`mise run backup:verify`, and save the current app image with
`mise run deploy:image-backup -- <current-image>`. Publish and deploy an
immutable GHCR tag. If startup, migration, or health checks fail, deploy the
previous immutable tag through the same app task. Migrations 013 and 014 are
additive; never reset the production database or tenant data for rollback.

Conversation memory is enabled by default and is persisted in PostgreSQL. The
session scope is tenant, channel, provider account, and participant, so the
same person on two provider numbers cannot share memory accidentally. Every
accepted inbound message and successful bot reply is written to the canonical
`conversation_message` transcript. Redis is only a 24-hour cache and is not
the source of truth; the working-memory projection expires after 90 days of
inactivity.

Each model request receives a controlled context: the bounded rolling summary,
up to `FORD_MEMORY_PROMPT_RECENT_MESSAGE_LIMIT` recent messages (8 by
default), and durable facts. The database transcript is not deleted when this
window is reached, so it remains available for audit and future retrieval.
The summary is deterministic and bounded: it carries forward recent customer
requests and the current request without spending a second model call. This
is the safe first stage of long-conversation compaction.

Semantic retrieval is disabled by default. The current pgvector adapter
retrieves semantic facts, not archived message turns; enabling it does not yet
provide full event-stream recall. A future message-recall adapter should index
redacted transcript turns and retrieve only the top 2-3 relevant turns under a
token budget. Memory read, embedding, and provider failures are fail-open for
webhook processing.

Jev classification is a separate structured decision step, not the response
generation model. When enabled with a real `TYPESAFE_API_KEY`, it classifies
intent, lead stage/temperature, handoff signals, tool family, and sensitive
data. The application passes only a sanitized decision context to generation,
keeps authorization and handoff policy in Java, and persists normalized
evidence in `conversation_decision`. If Jev is unavailable, malformed, or
below the configured confidence floor, deterministic rules continue the turn.
Use `FORD_AI_CLASSIFICATION_MODE=shadow` to exercise the provider while
retaining rules decisions.

The optional Jev self-refine advisor evaluates groundedness and safety and may
retry a generated answer up to the configured bounded attempt count. The Jev
guardrail advisor is the final safety layer when enabled. Both are disabled by
default because they add latency and provider calls; enable them only after
classification and generation have been verified.

To inspect persisted classifier evidence:

```sql
select classifier_type, classification, confidence, fallback_used, created_at
from conversation_decision
where conversation_id = '<conversation-id>'
order by created_at desc;
```

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

### Chat failure triage and release gate

Run these gates from `ford-ai-service` before publishing an application image:

```bash
mise run test:unit
mise run test:integration
mise run test:ai-evaluation
mise run test:check
```

The integration gate requires Docker/Testcontainers. The AI evaluation task
runs deterministic Java and Node fixture tests without contacting OpenRouter;
`mise run test:ai-evaluation --live` is a separate, opt-in model call and must
not use customer conversation data. A `202` response from a webhook means the
inbound event was accepted, not that an assistant reply was delivered.

Use the durable stores to distinguish internal memory refresh from external
delivery. These aggregate queries deliberately omit event payloads, transcript
content, tenant identifiers, and serialized Modulith events:

```sql
select event_type, count(*) as outstanding,
       min(publication_date) as oldest_publication
from event_publication
where completion_date is null
group by event_type
order by oldest_publication;

select status, attempts, count(*) as events,
       min(available_at) as oldest_available
from outbox_event
where status in ('PENDING', 'PROCESSING', 'DEAD_LETTER')
group by status, attempts
order by status, attempts;
```

An outstanding `ConversationMemoryProjectionRefreshRequested` publication
means the derived memory projection needs retry; the accepted transcript and
outbound event are committed independently. A growing `outbox_event` retry
count means customer delivery is delayed; `DEAD_LETTER` needs operator review
before any replay. Never replay an outbound message solely because a projection
failed.

During an incident, check `/actuator/health`, then the bounded observations and
the two aggregate queries. Provider `rate_limited`/`provider_unavailable`
events retry at most once per eligible provider before policy-approved fallback;
repeated failures indicate upstream/provider capacity or configuration, not a
reason to increase retry loops. Projection `persistence` failures are recoverable
through Modulith publications. Outbox `retry` followed by `dead_letter` indicates
delivery exhaustion; inspect the redacted error category and provider health.
Review `mise run deploy:logs` through approved redacted filters only. Do not
paste raw logs, SQL payload columns, prompts, tool results, phone numbers, tokens,
or provider response bodies into incident notes.

For a production release, additionally validate `mise run deploy:check` and
`mise run deploy:status`, record the immutable current app image for rollback,
then publish/deploy only after explicit release approval. The app deploy targets
`emme-1`; database and Redis services remain on `emme-2`. Verify health and
redacted startup/provider/MCP summaries after rollout. Do not reset data volumes
or send a customer test message as part of release verification.

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
