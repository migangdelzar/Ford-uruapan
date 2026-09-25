import assert from "node:assert/strict";
import { access, constants, mkdir, mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import test from "node:test";

const execFileAsync = promisify(execFile);

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

test("observability startup bootstraps a private environment file", async () => {
  const envTask = "ford-ai-service/.mise/tasks/observability/env";
  const upTask = "ford-ai-service/.mise/tasks/observability/up";
  await access(envTask, constants.X_OK);
  assert.match(await read(envTask), /openssl rand -hex/);
  assert.match(await read(upTask), /ford_observability_env/);

  const root = await mkdtemp(join(tmpdir(), "ford-observability-env-"));
  const projectRoot = join(root, "ford-ai-service");
  await mkdir(projectRoot, { recursive: true });
  const langfuse = join(root, "deployment", "observability", "langfuse");
  await mkdir(langfuse, { recursive: true });
  await writeFile(join(langfuse, ".env.example"), [
    "POSTGRES_PASSWORD=replace-with-a-local-secret",
    "CLICKHOUSE_PASSWORD=replace-with-a-local-secret",
    "REDIS_AUTH=replace-with-a-local-secret",
    "MINIO_ROOT_PASSWORD=replace-with-a-local-secret",
    "SALT=replace-with-32-byte-hex-secret",
    "ENCRYPTION_KEY=replace-with-64-character-hex-secret",
    "NEXTAUTH_SECRET=replace-with-a-long-random-secret",
    "LANGFUSE_AUTH_HEADER=Basic-replace-with-base64-public-key-colon-secret",
    "LANGFUSE_INIT_PROJECT_PUBLIC_KEY=",
    "LANGFUSE_INIT_PROJECT_SECRET_KEY=",
    "LANGFUSE_INIT_USER_PASSWORD=replace-with-a-local-password",
    ""
  ].join("\n"));

  await execFileAsync(join(process.cwd(), envTask), [], {
    env: { ...process.env, MISE_PROJECT_ROOT: projectRoot }
  });

  const target = join(langfuse, ".env");
  const metadata = await stat(target);
  const generated = await readFile(target, "utf8");
  assert.equal(metadata.mode & 0o777, 0o600);
  assert.doesNotMatch(generated, /replace-with|Basic-/);
  assert.match(generated, /^LANGFUSE_AUTH_HEADER=Basic /m);
  assert.match(generated, /^LANGFUSE_INIT_PROJECT_PUBLIC_KEY=pk-lf-/m);
  assert.match(generated, /^LANGFUSE_INIT_PROJECT_SECRET_KEY=sk-lf-/m);
});

test("collector contract includes OTLP, protection, redaction, and Langfuse export", async () => {
  const source = await read("deployment/observability/otel-collector/config.yaml");
  assert.match(source, /otlp:/);
  assert.match(source, /memory_limiter/);
  assert.match(source, /batch/);
  assert.match(source, /attributes\/redaction/);
  assert.match(source, /langfuse/);
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
  assert.doesNotMatch(source, /otlphttp\//);
  assert.doesNotMatch(source, /filelog\//);
});

test("Grafana Cloud telemetry tasks and Compose contract are present", async () => {
  const compose = await read("ford-ai-service/docker-compose.yml");
  assert.match(compose, /GRAFANA_CLOUD_OTLP_ENDPOINT/);
  assert.match(compose, /GRAFANA_CLOUD_INSTANCE_ID/);
  assert.match(compose, /GRAFANA_CLOUD_API_TOKEN/);
  assert.match(compose, /DOCKER_CONTAINER_LOGS_PATH/);

  for (const task of ["start", "stop", "restart", "status", "check"]) {
    await access(`ford-ai-service/.mise/tasks/telemetry/grafana-cloud/${task}`);
  }
  const start = await read("ford-ai-service/.mise/tasks/telemetry/grafana-cloud/start");
  assert.match(start, /OTEL_COLLECTOR_CONFIG=grafana-cloud-config\.yaml/);
});

test("local tracing export is opt-in when the collector is not running", async () => {
  const application = await read("ford-ai-service/src/main/resources/application.yml");
  const env = await read("ford-ai-service/.env.example");
  const compose = await read("ford-ai-service/docker-compose.yml");

  assert.match(application, /enabled: \$\{FORD_OTEL_TRACES_EXPORT_ENABLED:false\}/);
  assert.match(env, /^FORD_OTEL_TRACES_EXPORT_ENABLED=false$/m);
  assert.match(compose, /FORD_OTEL_TRACES_EXPORT_ENABLED: \$\{FORD_OTEL_TRACES_EXPORT_ENABLED:-false\}/);
});
