import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import os from "node:os";
import path from "node:path";
import test from "node:test";

async function read(path) {
  return (await readFile(path, "utf8")).replaceAll("\r\n", "\n");
}

test("operations dashboard is a valid Prometheus dashboard", async () => {
  const dashboard = JSON.parse(await read("deployment/observability/dashboards/ford-service-overview.json"));
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

test("production telemetry uses internal OTLP push and never exposes a scrape credential", async () => {
  const appEnv = await read("deployment/ansible/roles/app/templates/app.env.j2");
  const cloudCollector = await read("deployment/observability/otel-collector/grafana-cloud-config.yaml");
  const appCompose = await read("deployment/ansible/roles/app/templates/compose.yml.j2");

  assert.match(appEnv, /^FORD_OTEL_METRICS_EXPORT_ENABLED=true$/m);
  assert.match(appEnv, /^FORD_OTEL_METRICS_ENDPOINT=http:\/\/otel-collector:4318\/v1\/metrics$/m);
  assert.match(appEnv, /^FORD_OTEL_TRACES_EXPORT_ENABLED=true$/m);
  assert.match(appCompose, /otel-collector/);
  assert.match(cloudCollector, /metrics:\n\s+receivers: \[otlp\]/);
  assert.doesNotMatch(cloudCollector, /targets: \[ford-service:8080\]/);
  assert.doesNotMatch(cloudCollector, /authorization|bearer_token/i);
});

test("local Prometheus reads the internal Collector exporter instead of the protected actuator", async () => {
  const prometheus = await read("deployment/observability/prometheus/prometheus.yml");
  const collector = await read("deployment/observability/otel-collector/tempo-config.yaml");
  const compose = await read("ford-ai-service/docker-compose.yml");

  assert.match(prometheus, /targets:\s*\["otel-collector:9464"\]/);
  assert.doesNotMatch(prometheus, /ford-service:8080/);
  assert.match(collector, /prometheus:\n\s+endpoint: 0\.0\.0\.0:9464/);
  assert.match(collector, /receivers: \[otlp\]/);
  assert.match(collector, /exporters: \[prometheus\]/);
  assert.match(compose, /FORD_OTEL_METRICS_EXPORT_ENABLED: "true"/);
  assert.match(compose, /FORD_OTEL_METRICS_ENDPOINT: http:\/\/otel-collector:4318\/v1\/metrics/);
});

test("PostgreSQL logs identify the service and SQLSTATE without recording statements", async () => {
  const appEnv = await read("deployment/ansible/roles/app/templates/app.env.j2");
  const dataCompose = await read("deployment/ansible/roles/data/templates/compose.yml.j2");
  const localCompose = await read("ford-ai-service/docker-compose.yml");

  assert.match(appEnv, /FORD_DATABASE_URL=.*\?ApplicationName=ford-service/);
  for (const compose of [dataCompose, localCompose]) {
    assert.match(compose, /log_line_prefix=.*app=%a.*sqlstate=%e/);
    assert.match(compose, /log_error_verbosity=verbose/);
    assert.match(compose, /log_min_error_statement=panic/);
  }
});

test("Prometheus alert rules cover service, MCP tool, and model provider failures", async () => {
  const alerts = await read("deployment/observability/prometheus/alerts.yml");
  const prometheus = await read("deployment/observability/prometheus/prometheus.yml");
  const compose = await read("ford-ai-service/docker-compose.yml");

  assert.match(prometheus, /rule_files:[\s\S]*ford-alerts\.yml/);
  assert.match(compose, /ford-alerts\.yml/);
  assert.match(alerts, /alert: FordCollectorUnavailable/);
  assert.match(alerts, /alert: FordMcpToolFailures/);
  assert.match(alerts, /alert: FordProviderFailures/);
});

test("database deployment does not implicitly reinstall the separately managed backup role", async () => {
  const site = await read("deployment/ansible/site.yml");
  const backupTask = await read("ford-ai-service/.mise/tasks/deploy/backup");

  assert.match(site, /Configure data services[\s\S]*?tags: \[data\][\s\S]*?roles:\n\s+- data/);
  assert.match(site, /Configure OCI database backups[\s\S]*?tags: \[backup\][\s\S]*?roles:\n\s+- backup/);
  assert.match(backupTask, /deployment\/ansible\/backup\.yml/);
});

test("service README links the operational contracts", async () => {
  const readme = await read("ford-ai-service/README.md");
  assert.match(readme, /docs\/ci-cd\.md/);
  assert.match(readme, /docs\/observability\.md/);
});

test("OCI backup identity is declarative and reproducible", async () => {
  const storage = await read("deployment/terraform/storage.tf");
  const variables = await read("deployment/terraform/variables.tf");
  const example = await read("deployment/config/oci.env.example");
  const identityTask = await read("ford-ai-service/.mise/tasks/backup/identity-check");
  const backupListTask = await read("ford-ai-service/.mise/tasks/backup/list");
  const backupVerifyTask = await read("ford-ai-service/.mise/tasks/backup/verify");
  const backupRunTask = await read("ford-ai-service/.mise/tasks/backup/run");
  const deployBackupTask = await read("ford-ai-service/.mise/tasks/deploy/backup");
  const backupPlaybook = await read("deployment/ansible/backup.yml");
  const backupTasks = await read("deployment/ansible/roles/backup/tasks/main.yml");
  const backupScript = await read("deployment/ansible/roles/backup/templates/backup-databases.sh.j2");
  const backupService = await read("deployment/ansible/roles/backup/templates/ford-backup.service.j2");

  assert.match(storage, /oci_identity_dynamic_group/);
  assert.match(storage, /oci_identity_policy/);
  assert.match(storage, /objectstorage-\$\{var\.region\}/);
  assert.match(storage, /emme-business|backup_bucket_name/);
  assert.match(storage, /backup_object_prefix.*\*/);
  assert.match(variables, /emme_2_instance_ocid/);
  assert.match(example, /^OCI_BACKUP_BUCKET=emme-business$/m);
  assert.match(example, /^OCI_BACKUP_PREFIX=ford\/backups$/m);
  assert.match(identityTask, /instance_principal/);
  assert.match(identityTask, /emme-2/);
  assert.match(backupTasks, /systemd/);
  assert.match(backupTasks, /oci-cli|oci/);
  assert.match(backupScript, /instance_principal/);
  assert.match(backupScript, /redis/);
  assert.match(backupScript, /ford\/backups/);
  assert.match(backupService, /Environment=.*PATH=.*\/usr\/local\/bin/);
  assert.match(backupListTask, /ssh/);
  assert.match(backupListTask, /find/);
  assert.match(backupVerifyTask, /ssh/);
  assert.match(backupVerifyTask, /find/);
  assert.match(backupVerifyTask, /instance_principal/);
  assert.match(backupVerifyTask, /remote_oci os object head/);
  assert.match(backupRunTask, /ford-backup\.service/);
  assert.match(deployBackupTask, /backup\.yml/);
  assert.match(backupPlaybook, /roles:\n\s+- backup/);
});

test("OCI runtime Vault identity is opt-in and scoped to each instance, vault, and named secret", async () => {
  const identity = await read("deployment/terraform/runtime-vault-identity.tf");
  const variables = await read("deployment/terraform/variables.tf");
  const example = await read("deployment/terraform/terraform.tfvars.example");
  const instructions = await read("deployment/terraform/README.md");

  assert.match(variables, /variable "manage_runtime_vault_identity"\s*\{[^}]*default\s*=\s*false/s);
  assert.match(example, /^manage_runtime_vault_identity\s*=\s*false$/m);
  assert.match(identity, /resource "oci_identity_dynamic_group" "runtime_vault"/);
  assert.match(identity, /resource "oci_identity_policy" "runtime_vault"/);
  assert.match(identity, /ford-runtime-app-emme1/);
  assert.match(identity, /ford-runtime-data-emme2/);
  assert.match(identity, /ford-runtime-secret-read/);
  assert.match(identity, /ALL \{instance\.id = '\$\{each\.value\.instance_ocid\}'\}/);
  assert.match(identity, /read secret-bundles/);
  assert.match(identity, /target\.vault\.id = '\$\{var\.runtime_vault_ocid\}'/);
  assert.match(identity, /target\.secret\.name = '\$\{secret_name\}'/);
  assert.match(identity, /ford-prod-cloudflared-tunnel-token/);
  assert.match(identity, /ford-prod-meta-instagram-access-token/);
  assert.doesNotMatch(identity, /ford-prod-bff-oidc-client-secret/);
  assert.match(instructions, /terraform import/);
  assert.match(instructions, /import.*before.*apply/is);
});

test("CI formats and validates Terraform before accepting infrastructure changes", async () => {
  const workflow = await read(".github/workflows/reusable-observability.yml");

  assert.match(workflow, /hashicorp\/setup-terraform@v3/);
  assert.match(workflow, /terraform fmt -check -recursive deployment\/terraform/);
  assert.match(workflow, /terraform init -backend=false/);
  assert.match(workflow, /terraform validate/);
});

test("Ansible propagates the complete Meta webhook configuration", async () => {
  const example = await read("deployment/config/oci.env.example");
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const template = await read("deployment/ansible/roles/app/templates/app.env.j2");

  for (const variable of [
    "FORD_META_APP_SECRET",
    "FORD_META_VERIFY_TOKEN",
    "FORD_META_AUTO_BINDING_ENABLED",
    "FORD_META_PROVIDER_ACCOUNT_ID",
    "FORD_META_PHONE_NUMBER_ID",
    "FORD_META_WHATSAPP_BUSINESS_ACCOUNT_ID",
    "FORD_META_DIAGNOSTIC_STACKTRACES_ENABLED",
    "FORD_TENANT_ID"
  ]) {
    assert.match(example, new RegExp(`^${variable}=`, "m"), `${variable} must be documented`);
  }

  assert.match(remote, /FORD_META_APP_SECRET/);
  assert.match(remote, /FORD_META_VERIFY_TOKEN/);
  assert.match(remote, /FORD_META_AUTO_BINDING_ENABLED/);
  assert.match(remote, /FORD_META_PROVIDER_ACCOUNT_ID/);
  assert.match(remote, /FORD_META_PHONE_NUMBER_ID/);
  assert.match(remote, /FORD_META_WHATSAPP_BUSINESS_ACCOUNT_ID/);
  assert.match(remote, /FORD_TENANT_ID/);
  assert.match(remote, /ford_meta_auto_binding_enabled/);
  assert.match(remote, /ford_meta_provider_account_id/);
  assert.match(remote, /ford_tenant_id/);

  for (const variable of [
    "FORD_META_APP_SECRET",
    "FORD_META_VERIFY_TOKEN",
    "FORD_META_AUTO_BINDING_ENABLED",
    "FORD_META_PROVIDER_ACCOUNT_ID",
    "FORD_META_PHONE_NUMBER_ID",
    "FORD_META_WHATSAPP_BUSINESS_ACCOUNT_ID",
    "FORD_META_DIAGNOSTIC_STACKTRACES_ENABLED",
    "FORD_TENANT_ID"
  ]) {
    assert.match(template, new RegExp(`^${variable}=`, "m"), `${variable} must reach app.env.j2`);
  }
});

test("Ansible propagates the configured cloud AI provider", async () => {
  const example = await read("deployment/config/oci.env.example");
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const template = await read("deployment/ansible/roles/app/templates/app.env.j2");

  for (const variable of [
    "FORD_AI_CHAT_PROVIDER",
    "FORD_AI_CLOUD_PROVIDER",
    "FORD_AI_CLOUD_ENABLED",
    "FORD_AI_LOG_PROVIDER_ERRORS",
    "FORD_AI_LOCAL_ENABLED",
    "FORD_AI_CLOUD_BASE_URL",
    "FORD_AI_CLOUD_MODEL_VERSION"
  ]) {
    assert.match(example, new RegExp(`^${variable}=`, "m"), `${variable} must be documented`);
    assert.match(remote, new RegExp(variable), `${variable} must reach the inventory`);
    assert.match(template, new RegExp(`^${variable}=`, "m"), `${variable} must reach app.env.j2`);
  }
});

test("Ansible propagates isolated AI fallback credentials and evaluation gates", async () => {
  const example = await read("deployment/config/oci.env.example");
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const vaultSecret = await read("ford-ai-service/.mise/tasks/deploy/vault-secret");
  const template = await read("deployment/ansible/roles/app/templates/app.env.j2");
  const spring = await read("ford-ai-service/src/main/resources/application.yml");
  const terraform = await read("deployment/terraform/variables.tf");

  for (const variable of [
    "FORD_AI_FALLBACK_ORDER", "FORD_AI_EVALUATION_MODE",
    "FORD_AI_GROQ_ENABLED", "FORD_AI_GEMINI_ENABLED", "FORD_AI_CLOUDFLARE_ENABLED", "FORD_AI_OPENROUTER_ENABLED",
    "GROQ_API_KEY", "GEMINI_API_KEY", "CLOUDFLARE_API_TOKEN", "CLOUDFLARE_ACCOUNT_ID", "OPENROUTER_API_KEY"
  ]) {
    assert.match(example, new RegExp(`^${variable}=`, "m"), `${variable} must be documented`);
    assert.match(remote, new RegExp(variable), `${variable} must reach Ansible inventory`);
    assert.match(template, new RegExp(`^${variable}=`, "m"), `${variable} must reach app.env.j2`);
  }
  for (const variable of ["GROQ_API_KEY", "GEMINI_API_KEY", "CLOUDFLARE_API_TOKEN", "OPENROUTER_API_KEY"]) {
    assert.match(vaultSecret, new RegExp(variable), `${variable} must be allowlisted for Vault import`);
    assert.match(template, new RegExp(`FORD_VAULT_SECRET_OCID_${variable}`), `${variable} must be sourced from Vault in production`);
  }
  assert.match(spring, /provider-max-attempts:.*:5/);
  assert.match(spring, /max-attempts:.*:1/);
  assert.match(template, /FORD_AI_PROVIDER_MAX_ATTEMPTS=/);
  assert.match(remote, /ford_ai_provider_max_attempts/);
  assert.match(remote, /FORD_AI_PROVIDER_MAX_ATTEMPTS:-5/);
  assert.doesNotMatch(terraform, /groq_api_key|gemini_api_key|cloudflare_api_token|openrouter_api_key/,
    "provider API keys must stay out of Terraform variables");
});

test("deploy secret adoption stores exported fallback keys privately without printing them", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "ford-deploy-secret-test-"));
  try {
    const configDir = path.join(temporary, "deployment", "config");
    const miseRoot = path.join(temporary, "ford-ai-service");
    await mkdir(configDir, { recursive: true });
    await mkdir(miseRoot, { recursive: true });
    const configFile = path.join(configDir, "oci.env");
    await writeFile(configFile, "GROQ_API_KEY=\n", { mode: 0o600 });
    const library = path.resolve("ford-ai-service/.mise/lib/remote.sh");
    const stdout = execFileSync("bash", ["-c", `source "${library}"; load_deploy_config; adopt_environment_secret GROQ_API_KEY`], {
      cwd: process.cwd(),
      encoding: "utf8",
      env: { ...process.env, MISE_PROJECT_ROOT: miseRoot, GROQ_API_KEY: "synthetic-test-key" }
    });

    assert.equal(stdout, "");
    assert.match(await readFile(configFile, "utf8"), /^GROQ_API_KEY=synthetic-test-key$/m);
    assert.equal((await stat(configFile)).mode & 0o777, 0o600);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("deployment adopts the prefixed Jev key into the private app credential without printing it", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "ford-typesafe-secret-test-"));
  try {
    const configDir = path.join(temporary, "deployment", "config");
    const miseRoot = path.join(temporary, "ford-ai-service");
    await mkdir(configDir, { recursive: true });
    await mkdir(miseRoot, { recursive: true });
    const configFile = path.join(configDir, "oci.env");
    await writeFile(configFile, "TYPESAFE_API_KEY=\n", { mode: 0o600 });
    const library = path.resolve("ford-ai-service/.mise/lib/remote.sh");
    const stdout = execFileSync("bash", ["-c", `source "${library}"; load_deploy_config; adopt_environment_secret TYPESAFE_API_KEY FORD_TYPESAFE_JEV_API_KEY`], {
      cwd: process.cwd(),
      encoding: "utf8",
      env: { ...process.env, MISE_PROJECT_ROOT: miseRoot, FORD_TYPESAFE_JEV_API_KEY: "synthetic-jev-key" }
    });

    assert.equal(stdout, "");
    assert.match(await readFile(configFile, "utf8"), /^TYPESAFE_API_KEY=synthetic-jev-key$/m);
    assert.equal((await stat(configFile)).mode & 0o777, 0o600);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("TypeSafe credential is available through supported secret providers without entering Terraform", async () => {
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const template = await read("deployment/ansible/roles/app/templates/app.env.j2");
  const vaultSecret = await read("ford-ai-service/.mise/tasks/deploy/vault-secret");
  const example = await read("deployment/config/oci.env.example");
  const vaultExample = await read("deployment/config/vault-secret-ocids.env.example");

  assert.match(remote, /TYPESAFE_API_KEY/);
  assert.match(template, /^TYPESAFE_API_KEY=\{\{ lookup\('ansible\.builtin\.env', 'TYPESAFE_API_KEY'\)/m);
  assert.match(vaultSecret, /TYPESAFE_API_KEY/);
  assert.match(template, /FORD_VAULT_SECRET_OCID_TYPESAFE_API_KEY/);
  assert.match(example, /^TYPESAFE_API_KEY=$/m);
  assert.match(vaultExample, /^FORD_VAULT_SECRET_OCID_TYPESAFE_API_KEY=$/m);
  assert.doesNotMatch(remote, /typesafe_api_key.*terraform|terraform.*typesafe_api_key/i);
});

test("deployment preflight reports fallback readiness without revealing provider keys", async () => {
  const library = path.resolve("ford-ai-service/.mise/lib/remote.sh");
  const output = execFileSync("bash", ["-c", `source "${library}"; report_ai_fallback_status`], {
    encoding: "utf8",
    env: {
      ...process.env,
      FORD_AI_GROQ_ENABLED: "true",
      GROQ_API_KEY: "synthetic-hidden-key",
      FORD_AI_GROQ_TOOL_CALLING_VERIFIED: "true",
      FORD_AI_GROQ_STRUCTURED_OUTPUT_VERIFIED: "true"
    }
  });

  assert.match(output, /AI fallback groq\s+enabled credential=present capabilities=verified/);
  assert.match(output, /AI fallback gemini\s+disabled/);
  assert.doesNotMatch(output, /synthetic-hidden-key/);
});

test("deployment preflight reports Jev readiness without revealing its key", async () => {
  const library = path.resolve("ford-ai-service/.mise/lib/remote.sh");
  const output = execFileSync("bash", ["-c", `source "${library}"; report_classification_status`], {
    encoding: "utf8",
    env: {
      ...process.env,
      FORD_AI_CLASSIFICATION_ENABLED: "true",
      FORD_AI_CLASSIFICATION_MODE: "active",
      FORD_AI_CLASSIFICATION_PROVIDER: "typesafe",
      TYPESAFE_API_KEY: "synthetic-hidden-jev-key",
      FORD_AI_CLASSIFIER_SENSITIVE_DATA_ENABLED: "false"
    }
  });

  assert.match(output, /classification typesafe enabled=true mode=active credential=present sensitive_data=false/);
  assert.doesNotMatch(output, /synthetic-hidden-jev-key/);
});

test("live provider preflight always executes and requires a synthetic tool call", async () => {
  const gradle = await read("ford-ai-service/build.gradle.kts");
  const preflight = await read("ford-ai-service/src/providerPreflight/java/com/ford/service/ai/configuration/LiveProviderPreflightTest.java");

  assert.match(gradle, /tasks\.register<Test>\("providerPreflight"\)[\s\S]*?outputs\.upToDateWhen\s*\{\s*false\s*}/);
  assert.doesNotMatch(preflight, /toolChoice\("required"\)/);
  assert.match(preflight, /lookup_demo exactamente una vez/);
  assert.match(preflight, /tool_call_failed/);
  assert.match(preflight, /structured_response_empty/);
});

test("Ansible propagates Meta delivery and outbox runtime settings", async () => {
  const example = await read("deployment/config/oci.env.example");
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const template = await read("deployment/ansible/roles/app/templates/app.env.j2");
  const vaultSecret = await read("ford-ai-service/.mise/tasks/deploy/vault-secret");

  for (const variable of [
    "FORD_META_DELIVERY_ENABLED",
    "FORD_META_WHATSAPP_DELIVERY_ENDPOINT",
    "FORD_META_WHATSAPP_ACCESS_TOKEN",
    "FORD_OUTBOX_ENABLED"
  ]) {
    assert.match(example, new RegExp(`^${variable}=`, "m"), `${variable} must be documented`);
    assert.match(remote, new RegExp(variable), `${variable} must reach the inventory`);
    assert.match(template, new RegExp(`^${variable}=`, "m"), `${variable} must reach app.env.j2`);
  }

  assert.match(vaultSecret, /FORD_META_WHATSAPP_ACCESS_TOKEN/);
  assert.match(template, /FORD_VAULT_SECRET_OCID_FORD_META_WHATSAPP_ACCESS_TOKEN/);
});

test("Ansible propagates the human handoff feature flag", async () => {
  const example = await read("deployment/config/oci.env.example");
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const template = await read("deployment/ansible/roles/app/templates/app.env.j2");

  for (const source of [example, remote, template]) {
    assert.match(source, /FORD_HUMAN_HANDOFF_ENABLED/);
  }
});

test("Ansible accepts the source Ford MCP configuration names", async () => {
  const example = await read("deployment/config/oci.env.example");
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const vaultSecret = await read("ford-ai-service/.mise/tasks/deploy/vault-secret");

  for (const variable of ["FORD_MCP_URL", "FORD_MCP_ENDPOINT", "FORD_MCP_TOKEN"]) {
    assert.match(example, new RegExp(`^${variable}=`, "m"), `${variable} must be documented`);
    assert.match(remote, new RegExp(variable), `${variable} must reach the inventory`);
  }
  assert.match(remote, /FORD_MCP_API_TOKEN/);
  assert.match(vaultSecret, /FORD_MCP_API_TOKEN/);
  assert.match(vaultSecret, /FORD_MCP_TOKEN/);
});

test("Ford MCP keeps combined vehicle and financing search in app allowlists", async () => {
  const application = await read("ford-ai-service/src/main/resources/application.yml");
  const template = await read("deployment/ansible/roles/app/templates/app.env.j2");

  assert.match(application, /read-only-tool-names:.*search_vehicle_and_offers/);
  assert.match(template, /^FORD_MCP_READ_ONLY_TOOL_NAMES=.*search_vehicle_and_offers/m);
});

test("app deployment transfers local images and checks the webhook route", async () => {
  const appTask = await read("ford-ai-service/.mise/tasks/deploy/app");
  const appRole = await read("deployment/ansible/roles/app/tasks/main.yml");
  const appCompose = await read("deployment/ansible/roles/app/templates/compose.yml.j2");
  const playbook = await read("deployment/ansible/site.yml");

  assert.match(appTask, /docker save/);
  assert.match(appTask, /app_image_archive/);
  assert.match(appTask, /app_image_preloaded=true/);
  assert.match(appTask, /app_image_preloaded=false|unset|archive/);
  assert.match(appTask, /ghcr\.io\/\*/);
  assert.match(appRole, /docker pull/);
  assert.match(appRole, /ghcr\.io/);
  assert.doesNotMatch(appRole, /ford_bff_image|ford-bff/i);
  assert.doesNotMatch(appCompose, /ford_bff|FORD_BFF/);
  assert.match(appCompose, /otel-collector/);
  assert.match(appCompose, /127\.0\.0\.1:8080:8080/);
  assert.match(playbook, /webhooks\/meta\/whatsapp/);
  assert.match(playbook, /status_code: 400/);
});

test("GHCR publication is exposed through a Mise task", async () => {
  const task = "ford-ai-service/.mise/tasks/release/publish";
  const source = await read(task);
  assert.match(source, /docker buildx build/);
  assert.match(source, /docker push/);
  assert.match(source, /ghcr\.io/);
  assert.match(source, /MISE_PROJECT_ROOT/);
});

test("remote image rollback archive is exposed through a Mise task", async () => {
  const source = await read("ford-ai-service/.mise/tasks/deploy/image-backup");
  assert.match(source, /ssh/);
  assert.match(source, /docker save/);
  assert.match(source, /gzip/);
  assert.match(source, /ServerAliveInterval/);
  assert.match(source, /mktemp/);
  assert.match(source, /gzip -t/);
  assert.match(source, /mv .*archive/);
  assert.match(source, /deploy_state_dir/);
});

test("Vault credential provisioning keeps values off disk and stores only OCID mappings", async () => {
  const task = await read("ford-ai-service/.mise/tasks/deploy/secrets");
  const vaultTask = await read("ford-ai-service/.mise/tasks/deploy/vault-secret");
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");

  assert.match(task, /disabled because it stored credential values/);
  assert.match(task, /deploy:vault-secret/);
  assert.match(vaultTask, /read -r -s/);
  assert.match(vaultTask, /create-base64/);
  assert.match(vaultTask, /update-base64/);
  assert.match(vaultTask, /unset secret_value/);
  assert.doesNotMatch(vaultTask, /echo.*secret_value|printf.*secret_value.*>>/);
  assert.match(remote, /load_vault_secret_mappings/);
  assert.match(remote, /ocid1\\\.vaultsecret/);
});

test("Keychain-backed deployment secrets override legacy oci.env values", async () => {
  const library = path.resolve("ford-ai-service/.mise/lib/remote.sh");
  const temporary = await mkdtemp(path.join(os.tmpdir(), "ford-keychain-deploy-config-"));
  const configFile = path.join(temporary, "oci.env");
  const miseRoot = path.join(temporary, "ford-ai-service");
  await mkdir(miseRoot);

  try {
    await writeFile(configFile, [
      "FORD_SECURITY_JWK_PRIVATE_KEY=legacy-jwk",
      "FORD_META_WHATSAPP_ACCESS_TOKEN=legacy-meta-token",
      "FORD_BFF_OIDC_CLIENT_SECRET=legacy-oidc-secret",
      "OPENROUTER_API_KEY=legacy-openrouter-key"
    ].join("\n"), { mode: 0o600 });

    const output = execFileSync("bash", ["-c", `source "${library}"; load_deploy_config; printf '%s\\n' "$FORD_SECURITY_JWK_PRIVATE_KEY" "$FORD_META_WHATSAPP_ACCESS_TOKEN" "$FORD_BFF_OIDC_CLIENT_SECRET" "$OPENROUTER_API_KEY"`], {
      encoding: "utf8",
      env: {
        ...process.env,
        MISE_PROJECT_ROOT: miseRoot,
        FORD_DEPLOY_CONFIG_FILE: configFile,
        FORD_SECURITY_JWK_PRIVATE_KEY: "keychain-jwk",
        FORD_META_WHATSAPP_ACCESS_TOKEN: "keychain-meta-token",
        FORD_BFF_OIDC_CLIENT_SECRET: "keychain-oidc-secret",
        OPENROUTER_API_KEY: "keychain-openrouter-key"
      }
    });

    assert.equal(output, [
      "keychain-jwk",
      "keychain-meta-token",
      "keychain-oidc-secret",
      "keychain-openrouter-key",
      ""
    ].join("\n"));
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("Vault request and mapping helpers encode secret input and atomically save non-secret OCIDs", async () => {
  const request = await read("ford-ai-service/scripts/oci/create_secret_request.py");
  const mapping = await read("ford-ai-service/scripts/oci/update_secret_mapping.py");

  assert.match(request, /sys\.stdin\.buffer\.read\(\)/);
  assert.match(request, /base64\.b64encode\(secret_value\)/);
  assert.match(request, /secretContentContent/);
  assert.match(mapping, /NamedTemporaryFile/);
  assert.match(mapping, /os\.fchmod\(temp_file\.fileno\(\), 0o600\)/);
  assert.match(mapping, /os\.replace\(temp_name, path\)/);
  assert.match(mapping, /SECRET_OCID\.fullmatch/);
});

test("deployment config preserves externally supplied secret values", async () => {
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const secretsTask = await read("ford-ai-service/.mise/tasks/deploy/secrets");

  assert.match(remote, /preserved_meta_app_secret/);
  assert.match(remote, /export FORD_META_APP_SECRET=/);
  assert.match(remote, /preserved_ghcr_username/);
  assert.match(remote, /export GHCR_USERNAME=/);
  assert.match(remote, /preserved_oci_namespace/);
  assert.match(remote, /export OCI_OBJECT_STORAGE_NAMESPACE=/);
  assert.match(remote, /is_valid_tenant_id/);
  assert.match(remote, /00000000-0000-0000-0000-000000000001/);
  assert.match(remote, /adopt_environment_secret/);
  assert.match(secretsTask, /disabled because it stored credential values/);
  assert.doesNotMatch(secretsTask, /adopt_environment_secret/);
});
