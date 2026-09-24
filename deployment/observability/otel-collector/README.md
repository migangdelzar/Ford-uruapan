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
