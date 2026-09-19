# Observability

Ford Service exposes Actuator health/metrics endpoints and exports traces
through Spring Boot's supported OpenTelemetry starter. The self-hosted
Langfuse and OpenTelemetry Collector deployment support lives under
`ford-ai-service/deployment/observability/`, alongside the service-owned
runtime configuration. The application-to-collector protocol is OTLP over HTTP.

## Local Langfuse

```bash
cp ford-ai-service/deployment/observability/langfuse/.env.example ford-ai-service/deployment/observability/langfuse/.env
docker compose -f ford-ai-service/deployment/observability/langfuse/docker-compose.yml up -d
```

Langfuse is available at `http://localhost:3300`. Configure the application
with `FORD_OTEL_TRACES_ENDPOINT=http://localhost:4318/v1/traces` and keep
`LANGFUSE_AUTH_HEADER` and all database/storage credentials in the local env
file or a secret manager. The Compose stack pins every image and persists
PostgreSQL, ClickHouse, Redis, and MinIO data in named volumes.

## Telemetry policy

Spring AI native observations cover ChatClient, model, embedding, vector-store,
and tool operations. Ford-specific observations cover conversations, Ford
catalog synchronization, and outbox publication. Prompt and completion
logging are disabled by default. Sampling defaults to 10% and can be changed
with `FORD_OTEL_SAMPLING_PROBABILITY`.

The Micrometer filter removes PII such as prompt/completion text, names, email,
phone, government identifiers, bank data, exact income, MXN or amount values,
raw request/response payloads, tool arguments/results, and provider/message
identifiers. Conversation, tenant, lead, and provider IDs must not be added as
metric labels. Trace export is fail-open: a Langfuse or
collector outage must not fail the business request.

Spring Modulith persists transactional internal-listener publications in
PostgreSQL. The protected `GET /actuator/modulith` resource exposes the module
topology for operators; event-publication staleness and recovery signals are
kept separate from the external `outbox_event` delivery queue. Monitor both
publication lag and outbox pending/dead-letter counts during incidents.

The PII redaction and observation-filter tests are part of the Java verification
suite. They assert that prompt, completion, raw payload, provider identifiers,
contact, financial, and identity attributes cannot become observation tags.

## Dashboard and incident signals

Import [ford-service-overview.json](../ford-ai-service/deployment/observability/dashboards/ford-service-overview.json)
into Grafana backed by Prometheus. It provides request rate, AI conversation
rate and errors, catalog sync rate, and outbox publication rate. Use Langfuse
for sampled trace inspection and prompt/tool latency analysis; use Prometheus
and the dashboard for service health and alerting.

When traces disappear, check the Collector queue/exporter first, then the
Langfuse Web/Worker logs. When AI errors rise, compare the AI error panel with
provider health and the deterministic evaluation artifact from the CI run.
