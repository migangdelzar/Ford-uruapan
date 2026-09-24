# Observability

Ford Service exposes Actuator health/metrics endpoints and exports traces
through Spring Boot's supported OpenTelemetry starter. The application Compose
stack includes Prometheus, Grafana, Loki, Grafana Alloy, Tempo, and an
OpenTelemetry Collector. The application-to-collector protocol is OTLP over
HTTP; container stdout is the log source for Alloy.

## Local or OCI observability stack

Create a local env file with a strong Grafana password, then start the stack:

```bash
cd ford-ai-service
printf 'GRAFANA_ADMIN_PASSWORD=replace-with-a-secret\n' > .env
docker compose up -d
```

Grafana is available on `http://localhost:3000`. Prometheus, Loki, Tempo, and
Alloy are bound to loopback by default. The service sends traces to the
collector, Prometheus scrapes `/actuator/prometheus`, and Alloy discovers all
Docker containers and forwards their stdout to Loki. Change the published
ports or place Grafana behind the OCI reverse proxy for deployment.

The observability services use named volumes and bounded trace/log retention
defaults. Review memory limits and retention before enabling this stack on a
small VM.

## Optional Langfuse

```bash
cp deployment/observability/langfuse/.env.example deployment/observability/langfuse/.env
docker compose -f deployment/observability/langfuse/docker-compose.yml up -d
```

Langfuse is available at `http://localhost:3300`. Its separate collector can
be used when sampled AI traces must also be reviewed in Langfuse. Configure
the application with `FORD_OTEL_TRACES_ENDPOINT=http://localhost:4318/v1/traces` and keep
`LANGFUSE_AUTH_HEADER` and all database/storage credentials in the local env
file or a secret manager. The Compose stack pins every image and persists
PostgreSQL, ClickHouse, Redis, and MinIO data in named volumes.

## Telemetry policy

Spring AI native observations cover ChatClient, model, embedding, vector-store,
and tool operations. Ford-specific observations cover conversations, Ford
catalog synchronization, and outbox publication. Prompt and completion
logging are disabled by default. Sampling defaults to 10% and can be changed
with `FORD_OTEL_SAMPLING_PROBABILITY`.

Micrometer metrics are exposed in Prometheus format at `/actuator/prometheus`.
The Micrometer filter removes PII such as prompt/completion text, names, email,
phone, government identifiers, bank data, exact income, MXN or amount values,
raw request/response payloads, tool arguments/results, and provider/message
identifiers. Conversation, tenant, lead, and provider IDs must not be added as
metric labels. Trace export is fail-open: a Tempo, Langfuse, or collector outage
must not fail the business request. Loki receives only application/container
logs; payloads, prompts, and customer content are not logged.

## Dashboard and incident signals

Import [ford-service-overview.json](../deployment/observability/dashboards/ford-service-overview.json)
into Grafana backed by Prometheus. It provides request rate, AI conversation
rate and errors, catalog sync rate, and outbox publication rate. Use Loki for
operational logs, Tempo for trace inspection, and Prometheus for service health
and alerting.

When traces disappear, check the Collector queue/exporter first, then Tempo.
When logs disappear, check Alloy's Docker socket access and Loki readiness.
When AI errors rise, compare the AI error panel with provider health and the
deterministic evaluation artifact from the CI run.
