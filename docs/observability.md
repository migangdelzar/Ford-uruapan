# Observability

Ford Service exposes Actuator health/metrics endpoints and exports traces
through Spring Boot's supported OpenTelemetry starter. The application Compose
stack includes Prometheus, Loki, Grafana Alloy, Tempo, and an OpenTelemetry
Collector. Grafana is an optional UI profile. The application-to-collector
protocol is OTLP over HTTP; container stdout is the log source for Alloy.

## Local or OCI observability stack

Use Mise to create the local environment and start the minimum collection
pipeline:

```bash
cd ford-ai-service
mise install
mise run setup
```

Prometheus, Loki, Tempo, the collector, and Alloy are bound to loopback by
default. The service sends traces to the collector, Prometheus scrapes
`/actuator/prometheus`, and Alloy discovers all Docker containers and forwards
their stdout to Loki. Start the optional dashboard with `mise run up-ui`; it is
available on `http://localhost:3000`. Change the published ports or place
Grafana behind the OCI reverse proxy for deployment.

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

## Langfuse Cloud profile

For the OCI Always Free deployment, use Langfuse Cloud instead of running the
heavy self-hosted Langfuse services on `emme-1` or `emme-2`. The collector is a
separate process from the Ford JVM and can dual-export redacted traces to local
Tempo and Langfuse Cloud:

```bash
export LANGFUSE_BASE_URL=https://us.cloud.langfuse.com
export LANGFUSE_PUBLIC_KEY='<project-public-key>'
export LANGFUSE_SECRET_KEY='<project-secret-key>'

mise run telemetry:cloud:start
mise run telemetry:cloud:check
```

The task derives the OTLP endpoint as
`https://us.cloud.langfuse.com/api/public/otel` unless
`LANGFUSE_OTLP_ENDPOINT` is explicitly supplied. Credentials are injected only
into the collector container through the environment and are never written to
the repository or sent to the frontend. The cloud profile uses a dedicated
collector configuration with bounded queueing, PII redaction, and fail-open
export behavior. Stop or restart it with the matching `telemetry:cloud:*`
tasks.

The deployment boundary is:

```text
Ford JVM → OpenTelemetry Collector → Tempo
                                  ↘ Langfuse Cloud
Ford logs → Alloy → Loki
Ford metrics → Prometheus
```

The Ford JVM does not contain Langfuse client code, and Langfuse Cloud is not a
database, Redis, or application dependency. On the small OCI footprint, keep
this profile off the production VMs until the resource budget for Tempo, Loki,
Prometheus, and Alloy is intentionally allocated; use it locally or on a
separate observability host.

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
