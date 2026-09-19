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
