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

## Local development with mise

From this directory, trust the project once and copy the development template:

```bash
mise trust
cp .env.example .env
mise run doctor
```

The service defaults to the `dev` mise environment through `.miserc.toml`.
Useful grouped tasks:

```bash
mise run app:run
mise run test:unit
mise run test:integration
mise run test:check
mise run docker:up
mise run docker:logs
mise run observability:up
mise run load:gate
```

Inspect available tasks with `mise tasks ls`.

For production-like validation, select the production overlay explicitly and
provide its required variables through the shell, CI, or a secret manager:

```bash
mise -E prod run env:check
mise -E prod run docker:config
```

Do not commit `.env`, `.env.prod`, or any provider credentials. Use
`.env.prod.example` only as a variable-name template.

The integration suite uses Testcontainers. The Gradle test tasks persist the
Colima-compatible `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
setting; use the `emme` profile and run `ford-colima-recover` if Docker is not
available.

Important environment variables are listed in [operations.md](docs/operations.md),
and the complete external integration checklist is in
[integration-setup.md](docs/integration-setup.md). Start from
[.env.example](.env.example) for local configuration.
API examples and webhook contracts are in [api.md](docs/api.md).
CI controls and evidence are documented in [ci-cd.md](../docs/ci-cd.md).
Telemetry, Langfuse and dashboard operations are documented in
[observability.md](../docs/observability.md).
