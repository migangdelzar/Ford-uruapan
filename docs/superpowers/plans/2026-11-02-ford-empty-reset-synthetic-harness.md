# Ford Empty-Reset Synthetic Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exercise the real guarded Ford reset/rollback Ansible playbooks against synthetic directories in a disposable Linux container without granting access to any Docker socket or production endpoint.

**Architecture:** A host-side Python unittest validates a digest-pinned, already-local Ansible runner image and an explicitly approved local Docker socket, then starts a `--pull=never`, network-isolated container. A container-side Python driver creates synthetic backup artifacts and local inventory, invokes the actual playbooks, and validates state transitions using a fake `docker` executable that only logs the exact expected stop arguments.

**Tech Stack:** Python 3 standard library `unittest`, Docker CLI, Ansible Core in a preloaded immutable-digest test image, the existing Ansible reset/rollback playbooks, synthetic files only.

## Global Constraints

- Never target production hosts, use production credentials, access backup objects, or connect test applications to production endpoints.
- Never mount `/var/run/docker.sock`, any Colima socket, a home directory, or deployment configuration inside the runner container.
- Runner container uses `--network none`, `--read-only`, `--pull=never`, automatic removal, and only the repository (read-only) plus temporary fixture (read/write) bind mounts.
- `FORD_EMPTY_RESET_HARNESS_IMAGE` must be an immutable `registry/image@sha256:<64 lowercase hex>` reference already present locally; never default to `latest`, pull implicitly, or invent a digest.
- Docker host must be an explicitly configured approved local Unix socket; unset Docker context and reject SSH, `emme`, and `testbox` endpoints.
- A passing result is labeled `SYNTHETIC PLAYBOOK SAFETY TEST`; it is not a completed Task 7 production rehearsal or readiness evidence.
- Preserve existing plan-approved active-state semantics; do not add production behavior or loosen reset/rollback guards merely to satisfy the harness.

---

### Task 1: Add host-runner safety contracts

**Files:**
- Create: `deployment/ansible/roles/backup/tests/empty_reset_harness.py`
- Create: `deployment/ansible/roles/backup/tests/test_empty_reset_harness.py`
- Test: `deployment/ansible/roles/backup/tests/test_empty_reset_harness.py`

**Interfaces:**
- `validate_image_ref(value: str) -> str` returns the original immutable image reference or raises `HarnessError`.
- `validate_local_docker_host(env: dict, home: str, hostname: str) -> str` returns an approved existing socket path or raises `HarnessError`.
- `container_argv(image: str, repo_root: str, fixture_dir: str) -> list[str]` returns the complete safe `docker run` argv; caller supplies only validated inputs.
- `run_harness()` runs image inspection and the container command with an environment allowlist; it never uses a shell string.

- [x] **Step 1: Write failing image/socket/argv tests**

Test immutable digest acceptance and rejection of tags, malformed digests, absent values, absent `DOCKER_HOST`, TCP/SSH endpoints, unapproved socket paths, emme/testbox host names, SSH sessions, and any argv containing a socket mount, network access, implicit pull, writable repository mount, or shell invocation.

```python
def test_image_requires_registry_digest(self):
    self.assertEqual(validate_image_ref("example.invalid/ansible-runner@sha256:" + "a" * 64),
                     "example.invalid/ansible-runner@sha256:" + "a" * 64)
    for value in ("", "ansible-runner:latest", "example.invalid/runner@sha256:abc"):
        with self.subTest(value=value), self.assertRaises(HarnessError):
            validate_image_ref(value)
```

- [x] **Step 2: Run tests and confirm RED**

Run: `python3 -m unittest discover deployment/ansible/roles/backup/tests -p 'test_empty_reset_harness.py' -v`
Expected: import failure for `empty_reset_harness`; no Docker command runs.

- [x] **Step 3: Implement host-side validation and command construction**

Use only `subprocess.run(argv, shell=False, check=True, env=sanitized_env)`. Require exact digest syntax, verify the image exists with `docker image inspect` under explicit local `DOCKER_HOST` and empty `DOCKER_CONTEXT`, then run it using `--rm --pull=never --network none --read-only --cap-drop ALL --cap-add CHOWN --security-opt no-new-privileges --entrypoint python3`. Mount repository read-only at `/workspace`, temporary fixture at `/fixture`, and a temporary `/tmp`; pass only the validated image and fixed container-side paths. Do not pass host environment wholesale.

- [x] **Step 4: Run host-runner safety tests and verify GREEN**

Run: `python3 -m unittest discover deployment/ansible/roles/backup/tests -p 'test_empty_reset_harness.py' -v`
Expected: all pure validation/argv tests pass; no Docker daemon request except mocked image-inspection tests.

- [x] **Step 5: Commit host-runner safety contracts**

Committed as `aa28550` (`test(deployment): guard synthetic reset harness runner`).

```bash
git add deployment/ansible/roles/backup/tests/empty_reset_harness.py deployment/ansible/roles/backup/tests/test_empty_reset_harness.py
git commit -m "test(deployment): guard synthetic reset harness runner"
```

### Task 2: Exercise real reset and rollback on synthetic data

**Files:**
- Create: `deployment/ansible/roles/backup/tests/empty_reset_container_runner.py`
- Modify: `deployment/ansible/roles/backup/tests/empty_reset_harness.py`
- Test: `deployment/ansible/roles/backup/tests/test_empty_reset_harness.py`

**Interfaces:**
- Container runner entry point: `main() -> int`; all paths live beneath `/fixture`; success prints the exact label `SYNTHETIC PLAYBOOK SAFETY TEST: PASS`.
- `run_playbook(playbook: str, inventory: str, extra_vars: dict, env: dict) -> subprocess.CompletedProcess` executes Ansible with JSON extra vars and no shell.
- `make_fixture(root: Path, approval_id: str) -> dict` creates deterministic sentinel files, fake Compose inputs, a synthetic legacy `ford_service` backup manifest, and matching PostgreSQL/Redis artifact checksums.

- [x] **Step 1: Add failing happy-path contract for state transitions**

Add unit tests for `make_fixture()` that assert the generated archive pair verifies through the existing `verify_backup.py`, the synthetic manifest digest is reproducible, the inventory contains only alias `emme-2` in group `data`, and sentinel bytes start in the active directories. Add a container integration test that invokes `run_harness()` with a preloaded digest image and requires reset to archive exact old sentinel files, create empty active directories and a mode-0600 `status=reset` state record, then rollback to restore old bytes, preserve replacement bytes under `*-empty-<approval-id>`, and set `status=rolled_back`.

- [x] **Step 2: Run the happy-path contract and confirm RED**

Run: `python3 -m unittest discover deployment/ansible/roles/backup/tests -p 'test_empty_reset_harness.py' -v`
Expected: fail because the container runner does not exist; existing production reset/rollback entry points remain untouched.

- [x] **Step 3: Implement the in-container synthetic driver**

Generate one inventory containing only `emme-2` in `data`, with `ansible_connection=local` and `/usr/bin/python3`. Create exact fixture paths and a valid manifest dated `20261102T120000Z`; compute manifest, PostgreSQL, and Redis SHA-256 values from synthetic bytes. Create an executable fixture `docker` stub that records JSON argv and accepts only `compose --project-name data --env-file <fixture>/remote/data/.env -f <fixture>/remote/data/compose.yml stop postgres redis`. Put that stub first in `PATH`, assert `shutil.which("docker")` resolves to it, and assert `/var/run/docker.sock` is absent. Run the real `ford-empty-reset.yml`, verify reset state, add synthetic replacement bytes, run real `ford-empty-rollback.yml`, and verify restored/preserved bytes and two exact stub calls.

- [x] **Step 4: Run happy-path harness with an explicit local digest image**

Run:

```bash
: "${FORD_EMPTY_RESET_HARNESS_IMAGE:?set a reviewed local registry/image@sha256 digest}"
DOCKER_HOST="unix://${HOME}/.colima/native/docker.sock" \
DOCKER_CONTEXT= \
python3 deployment/ansible/roles/backup/tests/empty_reset_harness.py
```
Expected: reset and rollback complete only inside the disposable ARM64 container; output labels the result synthetic. If the digest image is not already local, stop with the explicit prerequisite message and do not pull it.

- [x] **Step 5: Commit reset/rollback happy-path rehearsal**

Included with the fail-closed matrix and documentation in the implementation commit.

```bash
git add deployment/ansible/roles/backup/tests/empty_reset_harness.py deployment/ansible/roles/backup/tests/empty_reset_container_runner.py deployment/ansible/roles/backup/tests/test_empty_reset_harness.py
git commit -m "test(deployment): rehearse reset rollback with synthetic files"
```

### Task 3: Prove refusal paths leave fixture data unchanged

**Files:**
- Modify: `deployment/ansible/roles/backup/tests/empty_reset_container_runner.py`
- Modify: `deployment/ansible/roles/backup/tests/test_empty_reset_harness.py`

**Interfaces:**
- `run_refusal_case(case_name: str, fixture_root: Path) -> None` requires the expected Ansible failure, expected refusal point, unchanged source sentinel hashes, and no new unexpected paths.
- Each case uses a fresh directory tree and generated inventory; cases never share previous state.

- [x] **Step 1: Add failing refusal matrix tests**

Cover missing reset approval, wrong inventory alias, wrong manifest SHA, corrupted or missing backup artifact, pre-existing reset archive path, mismatched rollback approval and digest, symlinked protected path, and occupied rollback `*-empty-*` destination. Each must assert a nonzero playbook exit, exact refused boundary, unchanged original sentinel SHA-256 values, and no fake Docker call when failure is before service stop.

- [x] **Step 2: Run refusal matrix and confirm RED**

Run: `python3 -m unittest discover deployment/ansible/roles/backup/tests -p 'test_empty_reset_harness.py' -v`
Expected: failures identify missing refusal cases; no production config or endpoint is read.

- [x] **Step 3: Implement isolated refusal cases**

Create one fresh fixture per case, alter only that case's approval, inventory, manifest, artifact, symlink, or collision precondition, invoke the real playbook, and compare before/after hashes and relative path inventories. Treat an Ansible result that fails for an unrelated reason as a test failure. For rollback cases, seed only a synthetic mode-0600 state file matching the production state schema.

- [x] **Step 4: Run full synthetic matrix and verify cleanup**

Run:

```bash
: "${FORD_EMPTY_RESET_HARNESS_IMAGE:?set a reviewed local registry/image@sha256 digest}"
DOCKER_HOST="unix://${HOME}/.colima/native/docker.sock" \
DOCKER_CONTEXT= \
python3 deployment/ansible/roles/backup/tests/empty_reset_harness.py
```
Expected: all 11 refusal cases prove fixture trees unchanged; the runner exits and is removed; no Docker socket or container remains after the test.

- [x] **Step 5: Commit fail-closed scenarios**

Included with the happy path and documentation in the implementation commit.

```bash
git add deployment/ansible/roles/backup/tests/empty_reset_container_runner.py deployment/ansible/roles/backup/tests/test_empty_reset_harness.py
git commit -m "test(deployment): verify reset rollback refusal paths"
```

### Task 4: Document exact local invocation and verify scope boundaries

**Files:**
- Modify: `docs/operations/database-backups.md`
- Modify: `scripts/operations-contract.test.mjs`
- Modify: `deployment/ansible/roles/backup/tests/test_empty_reset_harness.py`

**Interfaces:**
- Documentation exposes the invocation and clearly distinguishes synthetic playbook safety testing from production rehearsal.
- Existing Node contracts enforce no `latest`, no implicit image pull, no socket mount, no production endpoint, and no acceptance claim.

- [x] **Step 1: Add failing docs/scope contract**

Assert documentation contains the exact test name and environment variables and states synthetic-only / not production acceptance. Assert runner source has `--pull=never`, `--network none`, read-only repository mount, no socket mount, and an immutable digest check.

- [x] **Step 2: Run contract and confirm RED**

Run: `node --test --test-name-pattern='synthetic reset harness' scripts/operations-contract.test.mjs`
Expected: fail because no harness documentation contract exists.

- [x] **Step 3: Document invocation and scope**

Add a short section to `docs/operations/database-backups.md` with the exact command from Task 3, image preloading/review prerequisite, cleanup behavior, expected synthetic label, and explicit statement that approved backup restoration and production release readiness remain separate gates.

- [x] **Step 4: Run all focused tests and syntax checks**

Run: `python3 -m unittest discover deployment/ansible/roles/backup/tests -v`, `node --test scripts/operations-contract.test.mjs scripts/workflow-contract.test.mjs scripts/mise-contract.test.mjs`, `ansible-playbook --syntax-check -i localhost, -c local deployment/ansible/ford-empty-reset.yml`, `ansible-playbook --syntax-check -i localhost, -c local deployment/ansible/ford-empty-rollback.yml`, and `git diff --check`.
Expected: local contracts and syntax checks pass. Actual result: 23 Python tests pass, 93 Node contracts pass, both playbooks pass syntax checks, and the digest-pinned synthetic harness prints `SYNTHETIC PLAYBOOK SAFETY TEST: PASS`; all test containers are removed and Colima is stopped.

- [x] **Step 5: Commit documentation and contracts**

Included with the harness and playbook guard corrections in the implementation commit.

```bash
git add docs/operations/database-backups.md scripts/operations-contract.test.mjs deployment/ansible/roles/backup/tests
git commit -m "docs(deployment): document synthetic reset safety harness"
```

## Self-review

- Spec coverage: actual playbook success flow, backup manifest checksums, byte-preserving reset/rollback, refused preconditions, fake Docker boundary, no mounted socket, disposable cleanup, and non-acceptance labeling each have explicit steps.
- Scope: only the synthetic reset/rollback test subsystem is covered. Production rollout readiness and external artifacts remain explicitly deferred.
- Portability: host orchestration uses Python 3 standard library; Ansible runs only in the pinned test image. GNU/Linux `realpath` and `mv` semantics therefore match the production host role.
- Image prerequisite: the plan refuses mutable image tags and implicit pulls. Without a preloaded immutable Ansible test image, host-side contracts can pass, but actual playbook execution remains blocked and must be reported as such.
- Synthetic run surfaced three existing playbook defects without weakening guards: absent `stat.islnk` fields now default false while `.exists` checks remain explicit; the backup verifier script is run through `python3` rather than relying on an executable bit; and reset-state JSON uses a valid multiline Jinja expression.
