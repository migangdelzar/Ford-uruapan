# Read-Only Service Health Status Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Identify top-level Ford Service health components that report DOWN without exposing their details or changing production state.

**Architecture:** Extend the existing read-only GitHub Actions preflight to parse the public `/actuator/health` JSON and print only component names and statuses. Remove the `/actuator/health/db` and `/actuator/health/catalogReadiness` probes because application security intentionally returns 401 for those paths; keep the root health result as the sole blocking gate.

**Tech Stack:** GitHub Actions YAML, embedded Python 3, Python `unittest` contract tests.

## Global Constraints

- Emit only top-level health component names and `UP`/`DOWN`/`UNKNOWN` statuses; never emit response details, messages, exception text, credentials, or logs.
- Keep root `/actuator/health` HTTP 200 plus status `UP` as the sole success condition.
- Keep all production operations read-only: no restart, route change, migration, image pull, or database write.
- Do not change application security or actuator exposure.

---

### Task 1: Report safe component statuses in read-only preflight

**Files:**
- Modify: `.github/workflows/deployment-preflight.yml`
- Test: `ford-ai-service/scripts/tests/test_oci_action_contract.py`

**Interfaces:**
- Consumes: JSON returned by the existing unauthenticated `GET /actuator/health` probe.
- Produces: one preflight line per listener `Health components port PORT: name=STATUS, ...` containing only top-level component names and status values; no new runtime/API interface.

- [ ] **Step 1: Add failing contract assertions**

In `OciActionContractTest.test_vm_preflight_is_branch_scoped_and_read_only`, retain the root health-probe assertions and add assertions that the workflow:

```python
self.assertIn('components = payload.get("components", {})', app_check)
self.assertIn('component.get("status", "UNKNOWN")', app_check)
self.assertIn("Health components:", app_check)
self.assertNotIn("/actuator/health/db", app_check)
self.assertNotIn("/actuator/health/catalogReadiness", app_check)
self.assertNotIn('component.get("details"', app_check)
self.assertNotIn('component.get("error"', app_check)
```

Keep existing assertions that container diagnostics run before the health failure exits and that only port 8080 gates success.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
python3 -m unittest ford-ai-service.scripts.tests.test_oci_action_contract.OciActionContractTest.test_vm_preflight_is_branch_scoped_and_read_only -v
```

Expected: FAIL because the workflow currently omits component statuses and still contains protected subprobes.

- [ ] **Step 3: Add minimal safe status extraction**

In the embedded Python in `.github/workflows/deployment-preflight.yml`, after parsing the root health response, extract only top-level component names/statuses. Ignore malformed entries. Preserve only exact `UP` and `DOWN` values; map missing, malformed, or any other status to `UNKNOWN`. Print no component payload fields other than `status`.

Remove the loop probing `/actuator/health/db` and `/actuator/health/catalogReadiness`; their 401 responses are expected under the existing security policy and do not reveal health state.

Preserve the existing gate exactly: the result succeeds only when port 8080 returns HTTP 200 and overall status `UP`. Probes on ports 18081 and 18082 remain diagnostic only.

- [ ] **Step 4: Run focused and full contract tests**

Run:

```bash
python3 -m unittest ford-ai-service.scripts.tests.test_oci_action_contract.OciActionContractTest.test_vm_preflight_is_branch_scoped_and_read_only -v
python3 -m unittest discover -s ford-ai-service/scripts/tests -p 'test_*.py'
```

Expected: focused test passes and the full suite reports 103 tests passing (or the current suite count if it has changed).

- [ ] **Step 5: Validate workflow syntax and patch hygiene**

Run:

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/deployment-preflight.yml", aliases: true); puts "workflow YAML parsed"'
git diff --check
```

Expected: `workflow YAML parsed`; `git diff --check` exits 0.

- [ ] **Step 6: Commit and verify the change on protected main**

Commit the workflow, contract test, and approved design/plan documents with message:

```bash
git add .github/workflows/deployment-preflight.yml ford-ai-service/scripts/tests/test_oci_action_contract.py docs/superpowers/specs/2026-10-30-preflight-health-status-diagnostics-design.md docs/superpowers/plans/2026-10-30-preflight-health-status-diagnostics.md
git commit -m "ops: report safe health component statuses"
```

Open a PR, require all applicable CI checks and review to pass, then merge. Confirm main CI passes for the merge SHA before dispatching the read-only preflight.

- [ ] **Step 7: Rerun read-only preflight and inspect sanitized result**

Dispatch `deployment-preflight.yml` from protected `main`. Confirm:

- emme-2 PostgreSQL/scheduler checks still run independently.
- The report lists only component names and `UP`/`DOWN`/`UNKNOWN` values.
- Root Service health remains a hard gate; no deploy/restart/routing/database-write step runs.

Use the failing component status only to determine the next approved read-only investigation; do not restart or deploy based on this diagnostic alone.
