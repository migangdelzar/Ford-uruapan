# OCI Observability Capability Design

## Goal

Run a bounded, production-useful observability stack on `emme-1` while keeping
`emme-2` dedicated to PostgreSQL/pgvector and Redis.

## Placement decision

Observability belongs on `emme-1` because the application logs and metrics are
there, and because `emme-2` is the stateful data plane. The app VM has about
5.5 GiB RAM, so the stack must be bounded and the existing development
PostgreSQL/Redis containers must not be treated as production dependencies.

The initial OCI capability includes Prometheus, Loki, Tempo, Grafana Alloy, and
the OpenTelemetry Collector. Langfuse is excluded from the first OCI rollout:
its dependency footprint is too large for a 1 OCPU/6 GiB Always Free VM and it
is not required for application health, logs, metrics, or traces.

## Runtime topology

```text
emme-1
  ford-service ---- private Docker network ---- otel-collector -> Tempo
       |                         |              Prometheus <- /actuator/prometheus
       |                         |              Alloy -> Loki
       |                         +-------------- Grafana (optional)
       +---- stdout ---------------------------- Alloy

emme-2
  PostgreSQL/pgvector + Redis only
```

The app and observability Compose projects share a dedicated external Docker
network. The app sends traces to the collector by service name; Prometheus
scrapes the app service on that network; Alloy reads Docker stdout through the
read-only Docker socket. No observability port is published publicly.

## Resource policy

The Compose services receive explicit memory limits and bounded retention:

| Service | Memory limit | Retention / role |
| --- | ---: | --- |
| Prometheus | 384 MiB | 7 days, application metrics |
| Loki | 512 MiB | 7 days, structured container logs |
| Tempo | 384 MiB | 7 days, traces |
| OpenTelemetry Collector | 256 MiB | redaction, batching, export |
| Alloy | 192 MiB | Docker log collection |
| Grafana | 256 MiB | optional UI |

The limits are ceilings, not reservations. Health checks, restart policies, and
bounded queue sizes are required. The stack must be measured with `docker stats`
after startup; if the VM becomes memory pressured, Grafana is the first service
to stop, followed by trace retention reduction.

## Security and data handling

- Prometheus, Loki, Tempo, and the collector bind to loopback or the private
  Docker network only.
- Grafana is disabled by default and is accessed through an SSH tunnel until a
  dedicated Cloudflare route is explicitly configured.
- The collector removes prompt, completion, phone, email, name, financial, and
  raw-provider-payload attributes before export.
- No customer message body, system prompt content, access token, or database
  password is written to logs.
- Retention is finite and persistent volumes are included in the backup plan.

## Mise and Ansible interface

The capability is exposed consistently with the existing deployment tasks:

```text
mise run deploy:observability:check
mise run deploy:observability:start
mise run deploy:observability:start-ui
mise run deploy:observability:status
mise run deploy:observability:logs
mise run deploy:observability:restart
mise run deploy:observability:stop
```

Ansible owns installation, the shared Docker network, Compose files, resource
limits, and app telemetry environment. Mise renders the target inventory and
calls Ansible; it does not duplicate remote shell logic.

## Application integration

The application keeps Prometheus scraping enabled. Tracing is enabled with a
bounded sampling probability and the OTLP HTTP endpoint points to the collector
on the shared network. Telemetry export is fail-open: an observability outage
cannot fail a WhatsApp, Messenger, Instagram, or operator request.

The app Compose definition joins the observability network without exposing the
collector or databases to the public interface. Existing Cloudflare routing
continues to target only the application port.

## Verification

The capability is complete when:

- all services report healthy on `emme-1`;
- Prometheus reports the Ford service target `up`;
- Loki receives an application log line with trace correlation fields;
- Tempo accepts a trace and the trace is searchable in Grafana;
- the application health and SSE endpoints remain responsive while telemetry
  services are restarted;
- `docker stats` stays within the stated memory limits; and
- `emme-2` still runs only the database and Redis capability.
