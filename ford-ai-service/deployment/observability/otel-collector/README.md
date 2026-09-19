# OpenTelemetry Collector

The collector receives OTLP traces from Ford Service, removes sensitive
attributes, batches data, and forwards traces to the Langfuse Web ingestion
endpoint. Prompt/completion and raw provider payload attributes are deleted
before export.

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
