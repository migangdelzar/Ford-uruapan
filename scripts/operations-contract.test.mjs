import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
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
  assert.match(backupListTask, /oci os object list/);
  assert.match(backupVerifyTask, /oci os object head/);
  assert.match(backupRunTask, /ford-backup\.service/);
  assert.match(deployBackupTask, /backup\.yml/);
  assert.match(backupPlaybook, /roles:\n\s+- backup/);
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
    "FORD_TENANT_ID"
  ]) {
    assert.match(example, new RegExp(`^${variable}=`, "m"), `${variable} must be documented`);
  }

  assert.match(remote, /FORD_META_APP_SECRET/);
  assert.match(remote, /FORD_META_VERIFY_TOKEN/);
  assert.match(remote, /FORD_META_AUTO_BINDING_ENABLED/);
  assert.match(remote, /FORD_META_PROVIDER_ACCOUNT_ID/);
  assert.match(remote, /FORD_TENANT_ID/);
  assert.match(remote, /ford_meta_auto_binding_enabled/);
  assert.match(remote, /ford_meta_provider_account_id/);
  assert.match(remote, /ford_tenant_id/);

  for (const variable of [
    "FORD_META_APP_SECRET",
    "FORD_META_VERIFY_TOKEN",
    "FORD_META_AUTO_BINDING_ENABLED",
    "FORD_META_PROVIDER_ACCOUNT_ID",
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
    "FORD_AI_LOCAL_ENABLED",
    "FORD_AI_CLOUD_BASE_URL",
    "FORD_AI_CLOUD_MODEL_VERSION"
  ]) {
    assert.match(example, new RegExp(`^${variable}=`, "m"), `${variable} must be documented`);
    assert.match(remote, new RegExp(variable), `${variable} must reach the inventory`);
    assert.match(template, new RegExp(`^${variable}=`, "m"), `${variable} must reach app.env.j2`);
  }
});

test("Ansible propagates Meta delivery and outbox runtime settings", async () => {
  const example = await read("deployment/config/oci.env.example");
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const template = await read("deployment/ansible/roles/app/templates/app.env.j2");
  const secrets = await read("ford-ai-service/.mise/tasks/deploy/secrets");

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

  assert.match(secrets, /FORD_META_WHATSAPP_ACCESS_TOKEN/);
});

test("app deployment transfers local images and checks the webhook route", async () => {
  const appTask = await read("ford-ai-service/.mise/tasks/deploy/app");
  const appRole = await read("deployment/ansible/roles/app/tasks/main.yml");
  const playbook = await read("deployment/ansible/site.yml");

  assert.match(appTask, /docker save/);
  assert.match(appTask, /app_image_archive/);
  assert.match(appTask, /app_image_preloaded=true/);
  assert.match(appTask, /app_image_preloaded=false|unset|archive/);
  assert.match(appTask, /ghcr\.io\/\*/);
  assert.match(appRole, /docker pull/);
  assert.match(appRole, /ghcr\.io/);
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
  assert.match(source, /deploy_state_dir/);
});

test("one Mise task collects and protects deployment secrets before propagation", async () => {
  const task = await read("ford-ai-service/.mise/tasks/deploy/secrets");
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const appTask = await read("ford-ai-service/.mise/tasks/deploy/app");

  for (const variable of [
    "POSTGRES_PASSWORD",
    "REDIS_PASSWORD",
    "FORD_OPERATOR_PASSWORD",
    "FORD_META_APP_SECRET",
    "FORD_META_VERIFY_TOKEN",
    "GHCR_USERNAME",
    "GRAFANA_CLOUD_API_TOKEN",
    "LANGFUSE_SECRET_KEY"
  ]) {
    assert.match(task, new RegExp(variable), `${variable} must be managed by deploy:secrets`);
  }

  assert.match(remote, /read -r -s/);
  assert.match(remote, /chmod 600/);
  assert.match(remote, /mktemp/);
  assert.match(task, /prompt_deploy_secret/);
  assert.match(task, /ensure_deploy_secret/);
  assert.doesNotMatch(task, /printf.*\$value|echo.*\$value/);
  assert.match(appTask, /load_deploy_config/);
  assert.match(appTask, /ansible_command/);
});

test("deployment config preserves externally supplied secret values", async () => {
  const remote = await read("ford-ai-service/.mise/lib/remote.sh");
  const secretsTask = await read("ford-ai-service/.mise/tasks/deploy/secrets");

  assert.match(remote, /preserved_meta_app_secret/);
  assert.match(remote, /export FORD_META_APP_SECRET=/);
  assert.match(remote, /preserved_ghcr_username/);
  assert.match(remote, /export GHCR_USERNAME=/);
  assert.match(remote, /is_valid_tenant_id/);
  assert.match(remote, /00000000-0000-0000-0000-000000000001/);
  assert.match(remote, /adopt_environment_secret/);
  assert.match(secretsTask, /adopt_environment_secret/);
  assert.match(secretsTask, /FORD_META_APP_SECRET/);
  assert.match(secretsTask, /FORD_META_VERIFY_TOKEN/);
});
