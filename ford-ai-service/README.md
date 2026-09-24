# ford-service

MVP modular monolith for a Spanish/MX$ Ford dealership assistant in Uruapan.
It normalizes WhatsApp, Messenger and Instagram webhooks; answers catalog,
offers and financing questions; scores leads; and pauses the bot for an
operator handoff.

## Architecture decisions

- Spring Boot MVC with Java virtual threads; WebFlux is not used in the MVP.
- Spring AI only, with independent Ollama/OpenAI chat and embedding policies.
- PostgreSQL + pgvector are canonical; Redis is ephemeral.
- JDBC/`JdbcClient` is used for explicit SQL. JPA is intentionally not used.
- RAG is reserved for approved documents. Live catalog, inventory, offers,
  finance math and lead scoring are deterministic services/tools.
- Ford official feeds and authorized scraping run through scheduled ingestion,
  never from the chat request path.
- All application configuration is YAML (`application.yml` and profiles); no
  `.properties` configuration is required.

## Run locally

```bash
mise install
mise run setup
```

Mise creates `.env` only when it does not exist, generates the local Grafana
admin password without printing it, validates Compose, builds the Java 25
application image, starts PostgreSQL, Redis, and the minimum metrics, logging,
and tracing pipeline, and waits for their health endpoints. Docker Desktop (or
Docker Engine with Compose) must be installed and running.

The normal setup uses Docker for the complete JVM application and its runtime
dependencies. For a faster edit/debug loop, run the dependencies in Docker and
the Java 25 JVM on the host:

```bash
mise run deps:up
mise run app:run
```

Grafana is optional because it is a visualization UI, not part of collection or
storage. Start it when needed with `mise run up-ui`; then open
`http://localhost:3000`.

Useful tasks, run from this directory:

```bash
mise run health                 # Check service readiness
mise run health-ui              # Check readiness including Grafana
mise run verify                 # Run unit and integration tests
mise run logs                   # Follow application logs
mise run down                   # Remove containers, preserve volumes
mise run db:reset:volume -- --confirm
```

The database reset task is deliberately guarded because it deletes the local
PostgreSQL volume. `mise run down` does not delete named volumes.

For tests, use Java 25 and run:

```bash
./gradlew clean check --no-configuration-cache
./gradlew integrationTest
```

The integration suite uses Testcontainers. Set `TESTCONTAINERS_RYUK_DISABLED=true`
when the local Docker socket cannot mount the Ryuk helper.

Important environment variables are listed in [operations.md](docs/operations.md).
API examples and webhook contracts are in [api.md](docs/api.md).
CI controls and evidence are documented in [ci-cd.md](../docs/ci-cd.md).
Telemetry, Langfuse and dashboard operations are documented in
[observability.md](../docs/observability.md).
