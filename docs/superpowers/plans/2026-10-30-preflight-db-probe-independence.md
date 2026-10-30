# Preflight DB Probe Independence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure log retrieval failures cannot skip Service-to-database TCP probes.

**Architecture:** Keep current sanitized log scan and existing TCP probe behavior. Move the DB probe block before `docker logs`; keep log-scan failures as fixed `SCAN_UNAVAILABLE` output. Add a contract assertion proving ordering.

**Tech Stack:** GitHub Actions YAML, embedded Python, Python `unittest`.

## Global Constraints

- Never print JDBC URLs, hosts, credentials, or raw log content.
- Keep all production checks read-only.
- Do not change health, collector, or PostgreSQL gates.
- No deploy, restart, route change, DB query, or DB write.

---

### Task 1: Make DB probes independent of log scan

**Files:**
- Modify: `.github/workflows/deployment-preflight.yml` — reorder the existing DB probe block before log retrieval.
- Test: `ford-ai-service/scripts/tests/test_oci_action_contract.py` — assert ordering in the emme-1 workflow section.

**Interfaces:**
- Consumes: Existing sanitized slot health and database URL environment extraction.
- Produces: Per-slot Service/Catalog TCP labels independent of `docker logs` success.

- [x] **Step 1: Add a failing ordering assertion**

In `test_vm_preflight_is_branch_scoped_and_read_only`, assert the existing probe label appears before the Docker log command:

```python
self.assertLess(
    app_check.index("Service DB TCP"),
    app_check.index('["docker", "logs", "--since", "24h", "--tail", "5000", container]'),
)
```

- [x] **Step 2: Run the focused test and verify failure**

Run:

```bash
python3 -m unittest ford-ai-service.scripts.tests.test_oci_action_contract.OciActionContractTest.test_vm_preflight_is_branch_scoped_and_read_only -v
```

Expected: FAIL because `docker logs` currently precedes the database probes.

Observed: FAIL, `5321 not less than 3777`; DB probe label followed the log command.

- [x] **Step 3: Move the existing DB probe block above log retrieval**

Moved DB endpoint extraction and TCP checks ahead of `docker logs`. Preserved the status labels, URL validation, output filtering, and handling. Log-scan failure `continue` statements now occur only after DB probe output.

- [x] **Step 4: Run contract tests and syntax checks**

Run:

```bash
python3 -m unittest discover -s ford-ai-service/scripts/tests -p 'test_*.py'
python3 - <<'PY'
import ast
import re
import textwrap
from pathlib import Path
workflow = Path('.github/workflows/deployment-preflight.yml').read_text()
scripts = re.findall(r"^\s*(?:if )?python3 - <<'PY'\n(.*?)^\s+PY\s*$", workflow, re.MULTILINE | re.DOTALL)
assert len(scripts) == 2, f"expected 2 embedded Python blocks, found {len(scripts)}"
for script in scripts:
    ast.parse(textwrap.dedent(script))
print('embedded Python blocks parsed')
PY
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/deployment-preflight.yml", aliases: true); puts "workflow YAML parsed"'
git diff --check
```

Observed: 103 tests pass; focused regression passes; both Python blocks and workflow YAML parse; `git diff --check` passes.

- [ ] **Step 5: Commit the fix**

```bash
git add .github/workflows/deployment-preflight.yml ford-ai-service/scripts/tests/test_oci_action_contract.py
git commit -m "ops: keep DB probes independent of log scan"
git push
```
