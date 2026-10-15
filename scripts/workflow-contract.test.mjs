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

test("CI verifies every branch and publishes images only from immutable SemVer tags", async () => {
  const source = await read(".github/workflows/ci.yml");
  const release = await read(".github/workflows/release.yml");

  assert.match(source, /^  push:\s*$/m);
  assert.match(source, /name: Container gates \(no publication\)/);
  assert.match(source, /publish: false/);
  assert.match(source, /name: Container gates and version publication/);
  assert.match(source, /github\.ref_type == 'tag'/);
  assert.match(source, /startsWith\(github\.ref, 'refs\/tags\/v'\)/);
  assert.match(release, /version:/);
  assert.match(release, /vars\.PRODUCTION_DEPLOY_ENABLED/);
});

test("CI runs the operational Python contract suite", async () => {
  const source = await read(".github/workflows/ci.yml");

  assert.match(source, /name: Release coordinator tests/);
  assert.match(
    source,
    /python3 -m unittest discover -s ford-ai-service\/scripts\/tests -p 'test_\*\.py' -v/,
  );
});

test("deployment connectivity preflight is manually runnable from main and stays read-only", async () => {
  const source = await read(".github/workflows/deployment-preflight.yml");

  assert.match(source, /^  workflow_dispatch:\s*$/m);
  assert.match(source, /\[\[ "\$\{GITHUB_REF\}" == "refs\/heads\/main" \]\]/);
  assert.match(source, /environment: deployment-preflight/);
  assert.match(source, /Deployment, migration, image pull, and route switch: not run/);
  assert.doesNotMatch(source, /^  push:/m);
  assert.doesNotMatch(source, /PRODUCTION_DEPLOY_ENABLED|oci-blue-green|reusable-deploy/);
});

test("Ford Service pins the backup-verified OCI rollout action", async () => {
  const source = await read(".github/workflows/release.yml");

  assert.match(
    source,
    /migangdelzar\/Ford-oci-deployment-action\/.github\/actions\/oci-blue-green@9cb6eae7e3803df4684c664a7106873d78550f4f/,
  );
  assert.match(source, /host: \$\{\{ vars\.OCI_DEPLOY_HOST \}\}/);
  assert.match(source, /user: \$\{\{ vars\.OCI_DEPLOY_USER \}\}/);
  assert.match(source, /backup-host: \$\{\{ vars\.OCI_DATA_DEPLOY_HOST \}\}/);
  assert.match(source, /backup-user: \$\{\{ vars\.OCI_DATA_DEPLOY_USER \}\}/);
  assert.match(source, /backup-ssh-private-key: \$\{\{ secrets\.OCI_DATA_SSH_PRIVATE_KEY \}\}/);
  assert.match(source, /remote-root: \/opt\/ford-uruapan/);
  assert.doesNotMatch(source, /secrets\.OCI_(?:DATA_)?DEPLOY_(?:HOST|USER)/);
});

test("Ford Service supplies the data private IP required by app deployment", async () => {
  const source = await read(".github/workflows/release.yml");

  assert.match(source, /data-private-ip: \$\{\{ vars\.OCI_DATA_PRIVATE_IP \}\}/);
  assert.match(source, /grafana-cloud-otlp-endpoint: \$\{\{ vars\.GRAFANA_CLOUD_OTLP_ENDPOINT \}\}/);
  assert.match(source, /grafana-cloud-instance-id: \$\{\{ vars\.GRAFANA_CLOUD_INSTANCE_ID \}\}/);
  assert.match(source, /grafana-cloud-api-token-secret-ocid: \$\{\{ vars\.FORD_VAULT_SECRET_OCID_GRAFANA_CLOUD_API_TOKEN \}\}/);
  assert.match(source, /langfuse-otlp-endpoint: \$\{\{ vars\.LANGFUSE_OTLP_ENDPOINT \}\}/);
  assert.match(source, /langfuse-public-key: \$\{\{ vars\.LANGFUSE_PUBLIC_KEY \}\}/);
  assert.match(source, /langfuse-secret-key-secret-ocid: \$\{\{ vars\.FORD_VAULT_SECRET_OCID_LANGFUSE_SECRET_KEY \}\}/);
  assert.match(
    source,
    /name: Check out Service observability configuration\n\s+uses: actions\/checkout@v7\n\s+with:\n\s+ref: main/,
  );
  for (const input of [
    "grafana-cloud-otlp-endpoint:",
    "grafana-cloud-instance-id:",
    "grafana-cloud-api-token-secret-ocid:",
    "langfuse-otlp-endpoint:",
    "langfuse-public-key:",
    "langfuse-secret-key-secret-ocid:",
  ]) {
    assert.equal(source.split(input).length - 1, 1, `${input} must be unique`);
  }
});

test("database backup actions inspect the deployed archive directory", async () => {
  const source = await read(".github/workflows/database-backup.yml");

  assert.match(source, /\/opt\/ford-uruapan\/backup\/archive/);
  assert.doesNotMatch(source, /\/opt\/ford\/backup\/archive/);
});

test("requested releases fail instead of reporting a skipped production deployment", async () => {
  const source = await read(".github/workflows/release.yml");

  assert.match(source, /Run Ford Service releases from main/);
  assert.match(source, /^  production-readiness:\s*$/m);
  assert.match(source, /PRODUCTION_DEPLOY_ENABLED: \$\{\{ vars\.PRODUCTION_DEPLOY_ENABLED \}\}/);
  assert.match(source, /RUNTIME_VAULT_READY: \$\{\{ vars\.RUNTIME_VAULT_READY \}\}/);
  assert.match(source, /needs\.production-readiness\.result == 'success'/);
  assert.doesNotMatch(source, /needs\.deploy\.result == 'skipped'/);
});

test("production service deployment passes Vault secret references to Ansible", async () => {
  const source = await read(".github/workflows/release.yml");
  const requiredReferences = [
    "FORD_VAULT_SECRET_OCID_FORD_DATABASE_PASSWORD",
    "FORD_VAULT_SECRET_OCID_SPRING_DATA_REDIS_PASSWORD",
    "FORD_VAULT_SECRET_OCID_FORD_OPERATOR_PASSWORD",
    "FORD_VAULT_SECRET_OCID_FORD_SECURITY_JWK_PRIVATE_KEY",
    "FORD_VAULT_SECRET_OCID_FORD_RESEND_API_KEY",
    "FORD_VAULT_SECRET_OCID_FORD_META_APP_SECRET",
    "FORD_VAULT_SECRET_OCID_FORD_META_VERIFY_TOKEN",
    "FORD_VAULT_SECRET_OCID_FORD_META_WHATSAPP_ACCESS_TOKEN",
    "FORD_VAULT_SECRET_OCID_FORD_META_MESSENGER_ACCESS_TOKEN",
    "FORD_VAULT_SECRET_OCID_FORD_META_INSTAGRAM_ACCESS_TOKEN",
    "FORD_VAULT_SECRET_OCID_FORD_MCP_TOKEN",
    "FORD_VAULT_SECRET_OCID_FORD_MCP_ACCESS_CLIENT_SECRET",
    "FORD_VAULT_SECRET_OCID_FORD_AI_OLLAMA_ACCESS_CLIENT_SECRET",
    "FORD_VAULT_SECRET_OCID_GROQ_API_KEY",
    "FORD_VAULT_SECRET_OCID_GEMINI_API_KEY",
    "FORD_VAULT_SECRET_OCID_CLOUDFLARE_API_TOKEN",
    "FORD_VAULT_SECRET_OCID_TYPESAFE_API_KEY",
    "FORD_VAULT_SECRET_OCID_OPENROUTER_API_KEY",
    "FORD_VAULT_SECRET_OCID_OPENAI_API_KEY",
    "FORD_VAULT_SECRET_OCID_GRAFANA_CLOUD_API_TOKEN",
    "FORD_VAULT_SECRET_OCID_LANGFUSE_SECRET_KEY",
  ];

  assert.match(source, /secrets-provider: \$\{\{ vars\.FORD_SECRETS_PROVIDER \}\}/);
  assert.match(source, /FORD_SECRETS_PROVIDER: \$\{\{ vars\.FORD_SECRETS_PROVIDER \}\}/);
  for (const name of requiredReferences) {
    assert.ok(
      source.includes(name + ": ${{ vars." + name + " }}"),
      `${name} is passed to Ansible`,
    );
  }
});

test("release-all reports missing cross-repository dispatch credentials clearly", async () => {
  const source = await read(".github/workflows/release-all.yml");

  assert.match(source, /name: Validate release coordinator credentials/);
  assert.match(source, /RELEASE_COORDINATOR_APP_ID: \$\{\{ secrets\.RELEASE_COORDINATOR_APP_ID \}\}/);
  assert.match(source, /RELEASE_COORDINATOR_APP_PRIVATE_KEY: \$\{\{ secrets\.RELEASE_COORDINATOR_APP_PRIVATE_KEY \}\}/);
  assert.match(source, /missing the RELEASE_COORDINATOR_APP_PRIVATE_KEY repository secret/);
  assert.match(source, /name: Require coordinator dispatch from main/);
  assert.doesNotMatch(source, /release:\n\s+name: Dispatch and track selected releases\n\s+if:/);
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

test("published container provenance is verified against the reusable workflow signer", async () => {
  const source = await read(".github/workflows/reusable-container.yml");
  const attest = source.indexOf("- name: Attest published image");
  const verify = source.indexOf("- name: Verify published image provenance");

  assert.ok(attest >= 0, "published images must have an attestation");
  assert.ok(verify > attest, "verification must follow attestation creation");
  assert.match(source, /- name: Verify published image provenance\n\s+if: inputs\.publish/);
  assert.match(source, /gh attestation verify "oci:\/\/\$\{IMAGE\}"/);
  assert.match(source, /--repo "\$\{GITHUB_REPOSITORY\}"/);
  assert.match(
    source,
    /--signer-workflow "\$\{GITHUB_REPOSITORY\}\/\.github\/workflows\/reusable-container\.yml"/,
  );
  assert.match(source, /IMAGE: \$\{\{ steps\.image\.outputs\.image \}\}/);
  assert.match(source, /GH_TOKEN: \$\{\{ github\.token \}\}/);
});

test("container tags and provenance subjects use a lowercase GHCR repository", async () => {
  const source = await read(".github/workflows/reusable-container.yml");
  assert.match(source, /id: image-repository/);
  assert.match(source, /echo "name=ghcr\.io\/\$\{REPOSITORY,,\}"/);
  assert.match(source, /tags: \$\{\{ steps\.tags\.outputs\.tags \}\}/);
  assert.match(source, /subject-name: \$\{\{ steps\.image-repository\.outputs\.name \}\}/);
  assert.doesNotMatch(source, /ghcr\.io\/\$\{\{ github\.repository \}\}/);
});

test("service health smoke runs natively before target ARM64 image publication", async () => {
  const source = await read(".github/workflows/reusable-container.yml");
  const smokeBuild = source.indexOf("- name: Build native smoke image");
  const healthSmoke = source.indexOf("- name: Smoke test Spring service health endpoint");
  const runtimeSmoke = source.indexOf("- name: Smoke test Java runtime");
  const publishBuild = source.indexOf("- name: Build target image for publication");
  const scan = source.indexOf("- name: Scan image");
  const publish = source.indexOf("- name: Publish scanned image");

  assert.ok(smokeBuild >= 0 && healthSmoke > smokeBuild);
  assert.ok(runtimeSmoke > healthSmoke && publishBuild > runtimeSmoke);
  assert.ok(scan > publishBuild && publish > scan);
  assert.match(source, /id: smoke-build/);
  assert.match(source, /platforms: linux\/amd64/);
  assert.match(source, /platforms: linux\/arm64/);
  assert.match(source, /cache-from:\s*type=gha/);
  assert.match(source, /cache-to:\s*type=gha[^\n]*mode=max/);
  assert.match(source, /actuator\/health/);
  assert.match(source, /ford-service-postgres/);
  assert.match(source, /--name ford-service-redis --network ford-service-smoke redis:7-alpine/);
  assert.match(source, /FORD_REDIS_HOST=ford-service-redis/);
  assert.match(source, /curlimages\/curl/);
  assert.match(source, /push: false/);
  assert.match(source, /input: \/tmp\/ford-service-arm64\.tar/);
  assert.match(source, /image: \$\{\{ steps\.tags\.outputs\.sha_tag \}\}/);
});

test("container build, smoke tests, and vulnerability scan pass before GHCR publication", async () => {
  const source = await read(".github/workflows/reusable-container.yml");
  const smokeBuild = source.indexOf("- name: Build native smoke image");
  const healthSmoke = source.indexOf("- name: Smoke test Spring service health endpoint");
  const runtimeSmoke = source.indexOf("- name: Smoke test Java runtime");
  const targetBuild = source.indexOf("- name: Build target image for publication");
  const scan = source.indexOf("- name: Scan image");
  const sbom = source.indexOf("- name: Generate SBOM");
  const login = source.indexOf("- name: Log in to GHCR after build, smoke tests, and scan pass");
  const publish = source.indexOf("- name: Publish scanned image");

  assert.ok(smokeBuild >= 0 && healthSmoke >= 0 && runtimeSmoke >= 0 && targetBuild >= 0 && scan >= 0);
  assert.ok(sbom >= 0 && login >= 0 && publish >= 0);
  assert.ok(smokeBuild < healthSmoke, "native image must build before its health smoke");
  assert.ok(healthSmoke < runtimeSmoke, "health smoke must pass before runtime smoke");
  assert.ok(runtimeSmoke < targetBuild, "native smokes must pass before target image build");
  assert.ok(targetBuild < scan, "target image must build before vulnerability scanning");
  assert.ok(scan < sbom, "the target image must pass its scan before SBOM generation");
  assert.ok(sbom < login, "scan and SBOM must pass before registry login");
  assert.ok(login < publish, "registry login must precede image publication");
  assert.match(source, /load: true/);
  assert.match(source, /push: false/);
  assert.match(source, /docker push "\$\{SHA_TAG\}"/);
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

  assert.match(compose, /DATABASE_URL: \$\{DATABASE_URL:-postgres:\/\//);
  assert.match(compose, /\$\{POSTGRES_PASSWORD:-\}/);
  assert.match(compose, /DATABASE_HOST: postgres/);
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
