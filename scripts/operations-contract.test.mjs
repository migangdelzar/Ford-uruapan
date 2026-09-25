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
