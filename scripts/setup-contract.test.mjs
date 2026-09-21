import assert from "node:assert/strict";
import { access, chmod, constants, mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import test from "node:test";

const execFileAsync = promisify(execFile);
const serviceRoot = "ford-ai-service";

async function read(path) {
  return readFile(path, "utf8");
}

async function assertExecutable(path) {
  await access(path, constants.X_OK);
  const file = await stat(path);
  assert.ok((file.mode & 0o111) !== 0, `${path} must be executable`);
}

async function mockCommand(directory, name, body = "exit 0") {
  const path = join(directory, name);
  await writeFile(path, `#!/usr/bin/env bash\n${body}\n`);
  await chmod(path, 0o755);
}

test("setup task files and adapters exist and are executable", async () => {
  for (const task of ["setup/check", "setup/host", "setup/docker", "setup/env"]) {
    const path = `${serviceRoot}/.mise/tasks/${task}`;
    await assertExecutable(path);
    assert.match(await read(path), /MISE_PROJECT_ROOT/);
    assert.match(await read(path), /\$@/);
  }

  for (const path of [
    "scripts/setup/common.sh",
    "scripts/setup/runtime.sh",
    "scripts/setup/dnf.sh",
    "scripts/setup/macos.sh"
  ]) {
    await assertExecutable(path);
    assert.match(await read(path), /set -euo pipefail/);
  }
});

test("setup sources define platform, rootless, and safety contracts", async () => {
  const sources = await Promise.all([
    read("scripts/setup/common.sh"),
    read("scripts/setup/runtime.sh"),
    read("scripts/setup/dnf.sh"),
    read("scripts/setup/macos.sh")
  ]);
  const source = sources.join("\n");

  assert.match(source, /SETUP_APPLY/);
  assert.match(source, /Fedora|fedora/);
  assert.match(source, /Oracle Linux|oracle|ol/);
  assert.match(source, /rootless/);
  assert.match(source, /systemctl --user/);
  assert.match(source, /Homebrew|brew/);
  assert.match(source, /emme/);
  assert.match(source, /DOCKER_HOST/);
  assert.match(source, /TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE/);
  assert.doesNotMatch(source, /groupadd.*docker|usermod.*docker/);
  assert.doesNotMatch(source, /dnf\s+(remove|erase)/);
  assert.doesNotMatch(source, /docker\s+volume\s+(prune|rm)/);
  assert.doesNotMatch(source, /colima\s+delete/);
});

test("setup env task refuses to overwrite an existing file", async () => {
  const root = await mkdtemp(join(tmpdir(), "ford-setup-"));
  await writeFile(join(root, ".env.example"), "FORD_DATABASE_URL=jdbc:example\n");
  const script = join(process.cwd(), serviceRoot, ".mise/tasks/setup/env");

  await assert.rejects(execFileAsync(script, [], {
    cwd: root,
    env: { ...process.env, MISE_PROJECT_ROOT: root }
  }));
});

test("dnf host setup is dry-run by default", async () => {
  const root = await mkdtemp(join(tmpdir(), "ford-dnf-dry-run-"));
  const bin = join(root, "bin");
  const osRelease = join(root, "os-release");
  await import("node:fs/promises").then(({ mkdir }) => mkdir(bin));
  await writeFile(osRelease, "ID=fedora\n");
  await mockCommand(bin, "dnf");
  await mockCommand(bin, "sudo");

  const result = await execFileAsync("scripts/setup/dnf.sh", ["host"], {
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      SETUP_OS_RELEASE_FILE: osRelease
    }
  });

  assert.match(result.stdout, /download\.docker\.com\/linux\/fedora\/docker-ce\.repo/);
  assert.match(result.stdout, /docker-ce-rootless-extras/);
  assert.match(result.stdout, /\[dry-run\]/);
});

test("dnf Docker setup uses rootless user services without Docker group changes", async () => {
  const root = await mkdtemp(join(tmpdir(), "ford-dnf-rootless-"));
  const bin = join(root, "bin");
  const osRelease = join(root, "os-release");
  const subuid = join(root, "subuid");
  const subgid = join(root, "subgid");
  const userName = process.env.USER ?? "developer";
  await import("node:fs/promises").then(({ mkdir }) => mkdir(bin));
  await writeFile(osRelease, "ID=ol\n");
  await writeFile(subuid, `${userName}:100000:65536\n`);
  await writeFile(subgid, `${userName}:100000:65536\n`);
  await mockCommand(bin, "docker", "if [[ \"$1\" == info ]]; then printf '[rootless]'; fi");
  await mockCommand(bin, "dockerd-rootless-setuptool.sh");
  await mockCommand(bin, "newuidmap");
  await mockCommand(bin, "newgidmap");
  await mockCommand(bin, "loginctl");
  await mockCommand(bin, "sudo", "\"$@\"");
  await mockCommand(bin, "systemctl", "if [[ \"$1\" == is-active ]]; then exit 1; fi");

  const result = await execFileAsync("scripts/setup/dnf.sh", ["docker", "--apply"], {
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      SETUP_OS_RELEASE_FILE: osRelease,
      SETUP_SUBUID_FILE: subuid,
      SETUP_SUBGID_FILE: subgid,
      XDG_RUNTIME_DIR: root,
      USER: userName
    }
  });

  assert.match(result.stdout, /rootless/);
  assert.doesNotMatch(result.stdout, /groupadd|usermod/);
});

test("setup documentation describes both supported platforms", async () => {
  const readme = await read(`${serviceRoot}/README.md`);
  const operations = await read(`${serviceRoot}/docs/operations.md`);
  const ci = await read("docs/ci-cd.md");

  for (const source of [readme, operations, ci]) {
    assert.match(source, /setup:check/);
    assert.match(source, /setup:host/);
    assert.match(source, /--apply/);
    assert.match(source, /rootless/);
    assert.match(source, /Colima/);
  }
});
