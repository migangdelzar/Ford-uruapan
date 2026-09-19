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
