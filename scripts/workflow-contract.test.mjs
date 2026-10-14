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
  assert.match(source, /skip-dirs:\s*\.worktrees/);
  assert.match(source, /github\/codeql-action\/upload-sarif@v4/);
  assert.match(source, /vars\.CODE_SCANNING_ENABLED == 'true'/);
  assert.match(source, /actions\/upload-artifact@v7/);
});

test("Gitleaks exceptions are limited to reviewed historical fixture findings", async () => {
  const exceptions = (await read(".gitleaksignore"))
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !line.startsWith("#"));

  assert.deepEqual(exceptions.sort(), [
    "3b14f16ccdf841f087b2e62033c8fbd537ab665b:deployment/observability/langfuse/.env.example:generic-api-key:8",
    "5f26bfaa856bb7d6d796e8f65d224eddde22f638:scripts/observability-contract.test.mjs:generic-api-key:42",
    "6fcf2fd7f6dc221ada19a1c3bdb41882203d9ec9:ford-ai-service/src/integrationTest/java/com/ford/service/identity/JdbcOperatorIdentityRepositoryIntegrationTest.java:generic-api-key:44",
    "6fcf2fd7f6dc221ada19a1c3bdb41882203d9ec9:ford-ai-service/src/integrationTest/java/com/ford/service/identity/JdbcOperatorIdentityRepositoryIntegrationTest.java:generic-api-key:71",
    "94b42716a98b008bdf4ba82261d98d18bde4d7bb:ford-ai-service/scripts/tests/test_load_vault_mappings.py:generic-api-key:35",
    "94c9b0e6dd4946c32f19f7fe0a194950dbe6d78b:ford-ai-service/src/test/java/com/ford/service/ai/InputSecurityPolicyTest.java:generic-api-key:56"
  ].sort());
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

test("container attestation permission is granted by both caller and reusable workflow", async () => {
  const caller = await read(".github/workflows/ci.yml");
  const reusable = await read(".github/workflows/reusable-container.yml");

  assert.match(caller, /attestations: write/);
  assert.match(reusable, /attestations: write/);
});

test("container provenance attests the digest returned by the registry push", async () => {
  const source = await read(".github/workflows/reusable-container.yml");

  assert.match(source, /subject-digest: \$\{\{ steps\.publish\.outputs\.digest \}\}/);
});

test("container tags and provenance subjects use a lowercase GHCR repository", async () => {
  const source = await read(".github/workflows/reusable-container.yml");
  assert.match(source, /id: image-repository/);
  assert.match(source, /echo "name=ghcr\.io\/\$\{REPOSITORY,,\}"/);
  assert.match(source, /tags: \$\{\{ steps\.image-repository\.outputs\.name \}\}:sha-\$\{\{ github\.sha \}\}/);
  assert.match(source, /subject-name: \$\{\{ steps\.image-repository\.outputs\.name \}\}/);
  assert.doesNotMatch(source, /ghcr\.io\/\$\{\{ github\.repository \}\}/);
});

test("branch container verification uses native AMD64 while published images target OCI ARM64", async () => {
  const source = await read(".github/workflows/reusable-container.yml");

  assert.match(
    source,
    /platforms:\s*\$\{\{ inputs\.publish && 'linux\/arm64' \|\| 'linux\/amd64' \}\}/,
  );
  assert.match(source, /cache-from:\s*type=gha/);
  assert.match(source, /cache-to:\s*type=gha[^\n]*mode=max/);
  assert.match(source, /actuator\/health/);
  assert.match(source, /ford-service-postgres/);
  assert.match(source, /--name ford-service-redis --network ford-service-smoke redis:7-alpine/);
  assert.match(source, /FORD_REDIS_HOST=ford-service-redis/);
  assert.match(source, /curlimages\/curl/);
});

test("container build, smoke tests, and vulnerability scan pass before GHCR publication", async () => {
  const source = await read(".github/workflows/reusable-container.yml");
  const build = source.indexOf("- name: Build immutable image");
  const healthSmoke = source.indexOf("- name: Smoke test Spring service health endpoint");
  const runtimeSmoke = source.indexOf("- name: Smoke test Java runtime");
  const scan = source.indexOf("- name: Scan image");
  const login = source.indexOf("- name: Log in to GHCR");
  const publish = source.indexOf("- name: Publish scanned image");

  assert.ok(build >= 0 && healthSmoke >= 0 && runtimeSmoke >= 0 && scan >= 0);
  assert.ok(login >= 0 && publish >= 0);
  assert.ok(build < healthSmoke, "local image must build before its health smoke");
  assert.ok(build < runtimeSmoke, "local image must build before its runtime smoke");
  assert.ok(runtimeSmoke < scan, "runtime smoke must pass before vulnerability scanning");
  assert.ok(scan < login, "the image must pass its scan before registry login");
  assert.ok(login < publish, "registry login must precede image publication");
  assert.match(source, /load: true/);
  assert.match(source, /push: false/);
  assert.match(source, /docker push "\$\{IMAGE_NAME\}"/);
  assert.match(
    source,
    /if: >-\n\s+always\(\) && github\.event_name != 'pull_request' &&\s+\(github\.event\.repository\.visibility == 'public' \|\| vars\.CODE_SCANNING_ENABLED == 'true'\) &&\s+hashFiles\('trivy-image\.sarif'\) != ''/s,
  );
});

test("service image runs as a non-root user", async () => {
  const dockerfile = await read("ford-ai-service/Dockerfile");
  assert.match(dockerfile, /^USER 10001:10001$/m);
  assert.match(
    dockerfile,
    /^LABEL org\.opencontainers\.image\.source=https:\/\/github\.com\/migangdelzar\/Ford-uruapan$/m,
  );
});

test("container compiles Java on the native builder and retains target runtime", async () => {
  const dockerfile = await read("ford-ai-service/Dockerfile");
  assert.match(
    dockerfile,
    /^FROM --platform=\$BUILDPLATFORM eclipse-temurin:25-jdk AS build$/m,
  );
  assert.match(dockerfile, /^FROM eclipse-temurin:25-jre$/m);
});

test("Docker context excludes credential files without excluding Java secrets packages", async () => {
  const dockerignore = await read("ford-ai-service/.dockerignore");

  assert.doesNotMatch(dockerignore, /^\*\*\/secrets\/?$/m);
  assert.match(dockerignore, /^\.env$/m);
  assert.match(dockerignore, /^\*\*\/\*\.pem$/m);
  assert.match(dockerignore, /^\*\*\/\*\.key$/m);
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

test("MCP containers run the protected HTTP command instead of the scheduler default", async () => {
  const composeFiles = [
    "deployment/ansible/roles/mcp/templates/compose.yml.j2",
    "deployment/ansible/roles/mcp/templates/blue-green-compose.yml.j2",
  ];

  for (const path of composeFiles) {
    const source = await read(path);
    assert.match(
      source,
      /command:\s*\["node", "dist\/src\/cli\.js", "mcp-http"\]/,
      `${path} must explicitly start mcp-http`,
    );
  }
});

test("scraper migrations use the candidate image and the service Compose environment", async () => {
  const compose = await read(
    "deployment/ansible/roles/mcp/templates/compose.yml.j2",
  );
  const override = await read(
    "deployment/ansible/roles/mcp/templates/migration-compose.yml.j2",
  );
  const tasks = await read("deployment/ansible/roles/mcp/tasks/blue-green.yml");
  const migrationStart = tasks.indexOf(
    "Run catalog migrations once from the immutable candidate image",
  );
  const migrationEnd = tasks.indexOf("\n- name:", migrationStart + 1);
  const migration = tasks.slice(migrationStart, migrationEnd);

  assert.match(compose, /DATABASE_URL: postgres:\/\/ford:\$\{POSTGRES_PASSWORD/);
  assert.match(compose, /FORD_BASE_URL: https:\/\/www\.forduruapan\.mx/);
  assert.match(override, /image: \{\{ mcp_candidate_image \}\}/);
  assert.match(migration, /-f "\{\{ remote_root \}\}\/mcp\/migration-compose\.yml"/);
  assert.match(migration, /run --rm --no-deps mcp node dist\/src\/cli\.js migrate/);
  assert.match(migration, /no_log: true/);
});

test("production scheduler runs privately on the data network", async () => {
  const source = await read(
    "deployment/ansible/roles/mcp/templates/blue-green-compose.yml.j2",
  );

  assert.match(source, /scheduler:/);
  assert.match(
    source,
    /command:\s*\["node", "dist\/src\/cli\.js", "scheduler"\]/,
  );
  assert.match(source, /networks:\s*\[data_default\]/);
  const scheduler = source.split("\n  scheduler:")[1]?.split("\n  mcp-proxy:")[0];
  assert.ok(scheduler, "scheduler service definition must be present");
  assert.doesNotMatch(scheduler, /ports:/);
  assert.match(source, /SYNC_SCHEDULE: "0 12 1 \* \*"/);
  assert.match(source, /SYNC_TIMEZONE: America\/Mexico_City/);
});
