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
printf 'GRAFANA_ADMIN_PASSWORD=replace-with-a-secret\n' > .env
docker compose up --build
```

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
