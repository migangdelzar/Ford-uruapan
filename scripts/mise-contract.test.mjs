import assert from "node:assert/strict";
import { access, constants, readFile, stat } from "node:fs/promises";
import test from "node:test";

const serviceRoot = "ford-ai-service";

async function read(path) {
  return readFile(path, "utf8");
}

async function assertExecutable(path) {
  await access(path, constants.X_OK);
  const file = await stat(path);
  assert.ok((file.mode & 0o111) !== 0, `${path} must be executable`);
}

test("mise selects development by default and manages Java 25", async () => {
  const miserc = await read(`${serviceRoot}/.miserc.toml`);
  const config = await read(`${serviceRoot}/mise.toml`);
  const dev = await read(`${serviceRoot}/mise.dev.toml`);
  const prod = await read(`${serviceRoot}/mise.prod.toml`);

  assert.match(miserc, /env\s*=\s*\[\s*["']dev["']\s*\]/);
  assert.match(config, /\[tools\][\s\S]*\bjava\s*=\s*["']25["']/);
  assert.match(config, /\bnode\s*=\s*["']22["']/);
  assert.match(dev, /FORD_RUNTIME_ENV\s*=\s*["']development["']/);
  assert.match(dev, /_\.file\s*=\s*\{[^}]*path\s*=\s*["']\.env["']/);
  assert.match(prod, /FORD_RUNTIME_ENV\s*=\s*["']production["']/);
  assert.match(prod, /FORD_DATABASE_URL\s*=\s*\{\s*required\s*=/);
  assert.match(prod, /FORD_DATABASE_PASSWORD\s*=\s*\{[^}]*required\s*=.*redact\s*=\s*true/);
});

test("grouped mise task files exist and are executable", async () => {
  const tasks = [
    "app/run",
    "app/build",
    "test/unit",
    "test/integration",
    "test/check",
    "test/ai-evaluation",
    "env/check",
    "env/list",
    "docker/up",
    "docker/deps/up",
    "docker/deps/down",
    "docker/deps/ps",
    "docker/down",
    "docker/build",
    "docker/config",
    "docker/ps",
    "docker/logs",
    "observability/up",
    "observability/down",
    "observability/config",
    "observability/ps",
    "observability/logs",
    "load/gate",
    "setup/check",
    "setup/host",
    "setup/docker",
    "setup/env",
    "doctor"
  ];

  for (const task of tasks) {
    const path = `${serviceRoot}/.mise/tasks/${task}`;
    await assertExecutable(path);
    assert.match(await read(path), /MISE_PROJECT_ROOT/);
  }
});

test("catalog sync operator task targets emme-2 and reuses the backup-gated playbook", async () => {
  const task = await read(`${serviceRoot}/.mise/tasks/deploy/catalog-sync`);
  const helpers = await read(`${serviceRoot}/.mise/lib/remote.sh`);

  await assertExecutable(`${serviceRoot}/.mise/tasks/deploy/catalog-sync`);
  assert.match(task, /render_backup_inventory/);
  assert.match(task, /catalog-sync\.yml/);
  assert.match(task, /EMME_2_SSH_KEY/);
  assert.match(task, /ansible-playbook/);
  assert.match(helpers, /backup_inventory_file\(\)/);
  assert.doesNotMatch(task, /require_deploy_config/);
  assert.doesNotMatch(task, /mcp\.yml|migrate/);
});

test("Docker task groups support Compose v2 and standalone Compose", async () => {
  const helper = await read(`${serviceRoot}/.mise/lib/compose.sh`);
  assert.match(helper, /docker compose/);
  assert.match(helper, /docker-compose/);

  const tasks = [
    "docker/up",
    "docker/deps/up",
    "docker/deps/down",
    "docker/deps/ps",
    "docker/down",
    "docker/build",
    "docker/config",
    "docker/ps",
    "docker/logs",
    "observability/up",
    "observability/down",
    "observability/config",
    "observability/ps",
    "observability/logs"
  ];

  for (const task of tasks) {
    const source = await read(`${serviceRoot}/.mise/tasks/${task}`);
    assert.match(source, /compose\.sh/);
    assert.match(source, /ford_compose/);
  }
});

test("Docker development modes separate dependencies from the host JVM", async () => {
  const dependencyUp = await read(`${serviceRoot}/.mise/tasks/docker/deps/up`);
  const dependencyDown = await read(`${serviceRoot}/.mise/tasks/docker/deps/down`);
  const dependencyPs = await read(`${serviceRoot}/.mise/tasks/docker/deps/ps`);
  const fullStack = await read(`${serviceRoot}/.mise/tasks/docker/up`);

  for (const source of [dependencyUp, dependencyDown, dependencyPs]) {
    assert.match(source, /compose\.sh/);
    assert.match(source, /ford_compose/);
    assert.match(source, /postgres/);
    assert.match(source, /redis/);
  }
  assert.match(dependencyUp, /ford_compose up -d postgres redis/);
  assert.match(dependencyDown, /ford_compose stop postgres redis/);
  assert.match(dependencyPs, /ford_compose ps postgres redis/);
  assert.match(fullStack, /ford_compose up --build -d/);
  assert.doesNotMatch(fullStack, /postgres redis/);
});

test("tracked mise configuration contains no credential-shaped values", async () => {
  const paths = [
    `${serviceRoot}/.miserc.toml`,
    `${serviceRoot}/mise.toml`,
    `${serviceRoot}/mise.dev.toml`,
    `${serviceRoot}/mise.prod.toml`,
    `${serviceRoot}/.env.prod.example`
  ];

  for (const path of paths) {
    const source = await read(path);
    assert.doesNotMatch(source, /sk-[A-Za-z0-9]{20,}/, `${path} must not contain an OpenAI-style key`);
    assert.doesNotMatch(source, /EAA[A-Za-z0-9]{20,}/, `${path} must not contain a Meta-style token`);
  }
});

test("documentation exposes grouped tasks and environment selection", async () => {
  const readme = await read(`${serviceRoot}/README.md`);
  const operations = await read(`${serviceRoot}/docs/operations.md`);
  const gitignore = await read(".gitignore");

  assert.match(readme, /mise run test:unit/);
  assert.match(readme, /mise -E prod/);
  assert.match(operations, /env:list/);
  assert.match(operations, /mise\.prod\.local\.toml/);
  assert.match(gitignore, /^mise\.local\.toml$/m);
  assert.match(gitignore, /^mise\.\*\.local\.toml$/m);
});
