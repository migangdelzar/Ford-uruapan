# OpenTelemetry Collector

The collector receives OTLP traces from Ford Service, removes sensitive
attributes, batches data, and forwards traces to one or more configured
backends. Prompt/completion and raw provider payload attributes are deleted
before export.

`tempo-config.yaml` is the default local profile and exports to Tempo.
`cloud-tempo-config.yaml` is the optional Cloud profile and exports the same
redacted traces to both Tempo and Langfuse Cloud using the collector's
`basicauth` extension. The Langfuse project is selected by the project key
pair; no project identifier is embedded in the application image.

The collector uses a bounded queue and retry window. If Langfuse is down, the
queue is allowed to drain or expire; the collector must not become an
unbounded store for customer data.

Validate the configuration with:

```bash
docker run --rm \
  -v "$PWD/deployment/observability/otel-collector/config.yaml:/etc/otelcol-contrib/config.yaml:ro" \
  otel/opentelemetry-collector-contrib:0.161.0 \
  validate --config=/etc/otelcol-contrib/config.yaml
```

For the Cloud profile, supply `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`,
and `LANGFUSE_OTLP_ENDPOINT` to the collector container and mount
`cloud-tempo-config.yaml` instead.

## Grafana Cloud profile

The Grafana Cloud profile receives application traces over OTLP, scrapes the
Ford Actuator Prometheus endpoint, and reads Docker JSON logs from the Linux
Docker host. It exports all three signals through Grafana Cloud's OTLP gateway
and sends redacted traces to Langfuse Cloud:

```text
Ford JVM ─┐
Actuator ─┼─> OpenTelemetry Collector ─> Grafana Cloud OTLP
Docker ───┘                         └─> Langfuse Cloud (traces only)
```

Supply these values through the shell environment or a secret manager:

```bash
export GRAFANA_CLOUD_OTLP_ENDPOINT='https://otlp-gateway-.../otlp'
export GRAFANA_CLOUD_INSTANCE_ID='your-otlp-instance-id'
export GRAFANA_CLOUD_API_TOKEN='your-cloud-access-policy-token'
export LANGFUSE_PUBLIC_KEY='your-langfuse-public-key'
export LANGFUSE_SECRET_KEY='your-langfuse-secret-key'
```

The Cloud Access Policy needs `metrics:write`, `logs:write`, and
`traces:write`. The Docker log mount is intended for Linux/OCI hosts and must
map the host's `/var/lib/docker/containers` directory into the Collector.
Run `mise run telemetry:grafana-cloud:start` from `ford-ai-service` and use
`mise run telemetry:grafana-cloud:check` to verify readiness. Grafana Cloud
stores the three signals in its managed Metrics, Loki, and Tempo-compatible
backends through the OTLP gateway.
