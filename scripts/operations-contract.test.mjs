import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function read(path) {
  return readFile(path, "utf8");
}

test("operations dashboard is a valid Prometheus dashboard", async () => {
  const dashboard = JSON.parse(await read("ford-ai-service/deployment/observability/dashboards/ford-service-overview.json"));
  assert.equal(dashboard.schemaVersion, 39);
  assert.ok(Array.isArray(dashboard.panels));
  assert.ok(dashboard.panels.length >= 3);
  assert.match(JSON.stringify(dashboard), /ford_ai_conversation/);
  assert.match(JSON.stringify(dashboard), /http_server_requests_seconds_count/);
});

test("operations documentation describes evidence and safe telemetry", async () => {
  const ci = await read("docs/ci-cd.md");
  const observability = await read("docs/observability.md");
  assert.match(ci, /reusable/i);
  assert.match(ci, /SBOM/i);
  assert.match(ci, /provenance/i);
  assert.match(ci, /artifact/i);
  assert.match(observability, /Langfuse/);
  assert.match(observability, /PII|personal data/i);
  assert.match(observability, /OTLP/);
  assert.match(observability, /dashboard/i);
});

test("service README links the operational contracts", async () => {
  const readme = await read("ford-ai-service/README.md");
  assert.match(readme, /docs\/ci-cd\.md/);
  assert.match(readme, /docs\/observability\.md/);
});
