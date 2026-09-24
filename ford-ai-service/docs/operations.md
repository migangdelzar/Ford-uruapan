# Operations

## Local services

`docker-compose.yml` starts PostgreSQL with pgvector and Redis. Liquibase owns
the schema. Do not edit production tables manually; create a new YAML changeSet.

## Configuration

Use environment variables or a deployment profile; keep secrets outside Git.

- `FORD_DATABASE_URL`, `FORD_DATABASE_USERNAME`, `FORD_DATABASE_PASSWORD`
- `FORD_META_APP_SECRET`, `FORD_META_VERIFY_TOKEN`
- `FORD_META_TENANT_ID` (optional single-tenant UUID; defaults to the local development tenant)
- `FORD_AI_CHAT_PROVIDER`, `FORD_AI_EMBEDDING_PROVIDER`
- `FORD_AI_CLOUD_ENABLED`, `FORD_AI_LOCAL_ENABLED`
- `FORD_AI_SYSTEM_PROMPT_LOCATION` (optional classpath or external resource override for the seed prompt)
- `FORD_INGESTION_ENABLED`, `FORD_TENANT_ID`, `FORD_INGESTION_CRON`
- `FORD_CATALOG_ENDPOINT`, `FORD_INVENTORY_ENDPOINT`, `FORD_SCRAPER_ENDPOINT`
- `FORD_MANUAL_CSV_PATH`, `FORD_INGESTION_MAX_ATTEMPTS`

The default ingestion flag is disabled. Enable it only after official Ford
endpoints or authorized scraper endpoints and credentials are configured.

## Health and metrics

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

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

## Runtime system prompt

The default Spanish dealership prompt is versioned in
`src/main/resources/prompts/ford-uruapan-system.md`. Operators can replace it
at runtime through the authenticated `PUT /api/v1/operator/ai/system-prompt`
endpoint. The request must include the latest `expectedVersion`; this prevents
two CRM users from silently overwriting each other. Prompt content is not
written to logs or telemetry.

## Application logging

Production application logs use `INFO` for operational outcomes: startup,
webhook rejection, inbound-message acceptance or deduplication, AI completion,
outbox delivery, catalog synchronization, prompt changes, SSE lifecycle, and
failures. These messages include bounded technical fields such as channel,
outcome, error type, record counts, and `durationMs`.

Request bodies, customer messages, prompts, tokens, phone numbers, provider
payloads, and response bodies are never logged. Framework and database noise
is reduced to `WARN`; `DEBUG` remains available for temporary diagnostics.

## Rollback

Deploy the previous application image, preserve the database volume, and do not
roll back Liquibase changes independently of the application version. Replay
pending outbox events only after verifying provider idempotency keys.
