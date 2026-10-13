import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const requiredWorkflows = [
  ".github/workflows/ci.yml",
  ".github/workflows/reusable-java-ci.yml",
  ".github/workflows/reusable-security.yml",
  ".github/workflows/reusable-container.yml",
  ".github/workflows/reusable-observability.yml",
  ".github/workflows/reusable-deploy.yml"
];

async function read(path) {
  return readFile(path, "utf8");
}

test("required workflow contracts exist", async () => {
  for (const path of requiredWorkflows) {
    await assert.doesNotReject(() => read(path), path);
  }
});

test("CI verifies every branch while publishing containers only from main", async () => {
  const source = await read(".github/workflows/ci.yml");
  const release = await read(".github/workflows/release.yml");

  assert.match(source, /^  push:\s*$/m);
  assert.match(source, /publish:.*github\.ref == 'refs\/heads\/main'/s);
  assert.match(release, /PRODUCTION_DEPLOY_ENABLED/);
  assert.match(release, /branches: \[main\]/);
});

test("reusable workflows expose the shared contract", async () => {
  for (const path of requiredWorkflows.slice(1)) {
    const source = await read(path);
    assert.match(source, /^name:/m, `${path} must have a name`);
    assert.match(source, /^on:/m, `${path} must have an on trigger`);
    assert.match(source, /^permissions:/m, `${path} must declare permissions`);
    assert.match(source, /^concurrency:/m, `${path} must declare concurrency`);
    assert.match(source, /^  workflow_call:/m, `${path} must be reusable`);
  }
});

test("java workflow publishes reports with the pinned checkout action", async () => {
  const source = await read(".github/workflows/reusable-java-ci.yml");
  assert.match(source, /actions\/checkout@v7/);
  assert.match(source, /actions\/upload-artifact@v7/);
});

test("security workflow contains secret, filesystem, and SARIF gates", async () => {
  const source = await read(".github/workflows/reusable-security.yml");
  assert.match(source, /gitleaks\/gitleaks-action@v3/);
  assert.match(source, /aquasecurity\/trivy-action@v0\.36\.0/);
  assert.match(source, /github\/codeql-action\/upload-sarif@v4/);
  assert.match(source, /vars\.CODE_SCANNING_ENABLED == 'true'/);
  assert.match(source, /actions\/upload-artifact@v7/);
});

test("container workflow contains image, SBOM, and provenance gates", async () => {
  const source = await read(".github/workflows/reusable-container.yml");
  assert.match(source, /docker\/build-push-action@v6/);
  assert.match(source, /aquasecurity\/trivy-action@v0\.36\.0/);
  assert.match(source, /anchore\/sbom-action@v0\.20\.0/);
  assert.match(source, /actions\/attest-build-provenance@v2/);
  assert.match(source, /packages: write/);
  assert.match(source, /id-token: write/);
});

test("service image runs as a non-root user", async () => {
  const dockerfile = await read("ford-ai-service/Dockerfile");
  assert.match(dockerfile, /^USER 10001:10001$/m);
});

test("observability workflow validates Compose and collector configuration", async () => {
  const source = await read(".github/workflows/reusable-observability.yml");
  assert.match(source, /docker compose/);
  assert.match(source, /otel\/opentelemetry-collector-contrib:0\.161\.0/);
  assert.match(source, /validate --config/);
  assert.match(source, /cloud-tempo-config\.yaml/);
  assert.match(source, /LANGFUSE_PUBLIC_KEY/);
});

test("observability workflow points to files in the checked-in deployment tree", async () => {
  const source = await read(".github/workflows/reusable-observability.yml");
  assert.match(source, /docker compose -f deployment\/observability\/langfuse\/docker-compose\.yml/);
  assert.match(source, /\$GITHUB_WORKSPACE\/deployment\/observability\/otel-collector\/config\.yaml/);
  assert.doesNotMatch(source, /ford-ai-service\/deployment\/observability/);
});
