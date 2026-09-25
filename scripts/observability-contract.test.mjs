import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function read(path) {
  return readFile(path, "utf8");
}

test("Langfuse Compose contains durable services and volumes", async () => {
  const source = await read("deployment/observability/langfuse/docker-compose.yml");
  for (const service of ["langfuse-web", "langfuse-worker", "postgres", "clickhouse", "redis", "minio", "otel-collector"]) {
    assert.match(source, new RegExp(`^  ${service}:`, "m"), `${service} must exist`);
  }
  for (const volume of ["langfuse_postgres_data", "langfuse_clickhouse_data", "langfuse_redis_data", "langfuse_minio_data"]) {
    assert.match(source, new RegExp(`- ${volume}:/`), `${volume} must be mounted`);
  }
  assert.doesNotMatch(source, /image: .*:latest\b/, "images must not use latest tags");
});

test("collector contract includes OTLP, protection, redaction, and Langfuse export", async () => {
  const source = await read("deployment/observability/otel-collector/config.yaml");
  assert.match(source, /otlp:/);
  assert.match(source, /memory_limiter/);
  assert.match(source, /batch/);
  assert.match(source, /attributes\/redaction/);
  assert.match(source, /langfuse/);
});

test("cloud collector profile dual-exports redacted traces to Tempo and Langfuse", async () => {
  const source = await read("deployment/observability/otel-collector/cloud-tempo-config.yaml");
  assert.match(source, /basicauth\/langfuse/);
  assert.match(source, /LANGFUSE_PUBLIC_KEY/);
  assert.match(source, /LANGFUSE_SECRET_KEY/);
  assert.match(source, /LANGFUSE_OTLP_ENDPOINT/);
  assert.match(source, /otlphttp\/tempo/);
  assert.match(source, /otlphttp\/langfuse/);
  assert.match(source, /exporters: \[otlphttp\/tempo, otlphttp\/langfuse\]/);
  assert.match(source, /attributes\/redaction/);
  assert.match(source, /gen_ai\.prompt/);
  assert.match(source, /gen_ai\.completion/);
});

test("Grafana Cloud collector profile exports traces, metrics, and Docker logs", async () => {
  const source = await read("deployment/observability/otel-collector/grafana-cloud-config.yaml");
  assert.match(source, /basicauth\/grafana/);
  assert.match(source, /GRAFANA_CLOUD_INSTANCE_ID/);
  assert.match(source, /GRAFANA_CLOUD_API_TOKEN/);
  assert.match(source, /GRAFANA_CLOUD_OTLP_ENDPOINT/);
  assert.match(source, /file_log\/docker/);
  assert.match(source, /prometheus:/);
  assert.match(source, /otlp_http\/grafana/);
  assert.match(source, /pipelines:/);
  assert.match(source, /logs:/);
  assert.match(source, /metrics:/);
  assert.match(source, /traces:/);
  assert.match(source, /attributes\/redaction/);
  assert.doesNotMatch(source, /otlphttp\//, "Grafana Cloud profile must use the current otlp_http exporter name");
  assert.doesNotMatch(source, /filelog\//, "Grafana Cloud profile must use the current file_log receiver name");
});

test("application Compose exposes the cloud collector profile without embedding credentials", async () => {
  const source = await read("ford-ai-service/docker-compose.yml");
  assert.match(source, /OTEL_COLLECTOR_CONFIG:-tempo-config\.yaml/);
  assert.match(source, /LANGFUSE_OTLP_ENDPOINT/);
  assert.match(source, /LANGFUSE_PUBLIC_KEY/);
  assert.match(source, /LANGFUSE_SECRET_KEY/);
  assert.doesNotMatch(source, /sk-lf-|pk-lf-/);
});

test("application Compose exposes Grafana Cloud credentials only to the collector", async () => {
  const source = await read("ford-ai-service/docker-compose.yml");
  assert.match(source, /GRAFANA_CLOUD_OTLP_ENDPOINT/);
  assert.match(source, /GRAFANA_CLOUD_INSTANCE_ID/);
  assert.match(source, /GRAFANA_CLOUD_API_TOKEN/);
  assert.match(source, /DOCKER_CONTAINER_LOGS_PATH/);
});

test("Mise exposes a complete cloud telemetry lifecycle", async () => {
  const taskPaths = [
    "start",
    "stop",
    "restart",
    "status",
    "check",
  ];

  for (const task of taskPaths) {
    await read(`ford-ai-service/.mise/tasks/telemetry/cloud/${task}`);
  }

  const start = await read("ford-ai-service/.mise/tasks/telemetry/cloud/start");
  const composeLibrary = await read("ford-ai-service/.mise/lib/compose.sh");
  assert.match(start, /OTEL_COLLECTOR_CONFIG=cloud-tempo-config\.yaml/);
  assert.match(composeLibrary, /LANGFUSE_PUBLIC_KEY/);
  assert.match(composeLibrary, /LANGFUSE_SECRET_KEY/);
});

test("Mise exposes a complete Grafana Cloud telemetry lifecycle", async () => {
  const taskPaths = [
    "start",
    "stop",
    "restart",
    "status",
    "check",
  ];

  for (const task of taskPaths) {
    await read(`ford-ai-service/.mise/tasks/telemetry/grafana-cloud/${task}`);
  }

  const start = await read("ford-ai-service/.mise/tasks/telemetry/grafana-cloud/start");
  const composeLibrary = await read("ford-ai-service/.mise/lib/compose.sh");
  assert.match(start, /OTEL_COLLECTOR_CONFIG=grafana-cloud-config\.yaml/);
  assert.match(composeLibrary, /GRAFANA_CLOUD_OTLP_ENDPOINT/);
  assert.match(composeLibrary, /GRAFANA_CLOUD_INSTANCE_ID/);
  assert.match(composeLibrary, /GRAFANA_CLOUD_API_TOKEN/);
});
