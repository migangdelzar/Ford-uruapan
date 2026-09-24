# OCI Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run bounded Prometheus, Loki, Tempo, Alloy, OpenTelemetry Collector, and optional Grafana on `emme-1` without disturbing the data plane.

**Architecture:** Ansible configures a separate observability Compose project and a shared external Docker network. The app joins that network for trace export and metrics scraping. Mise exposes lifecycle tasks; all services bind privately.

**Tech Stack:** Docker Compose v2, Ansible, Prometheus, Loki, Tempo, Grafana Alloy, OpenTelemetry Collector, Grafana, Mise.

## Global Constraints

- `emme-1` hosts app and observability; `emme-2` hosts only PostgreSQL/pgvector and Redis.
- Service memory limits are Prometheus 384 MiB, Loki 512 MiB, Tempo 384 MiB, Collector 256 MiB, Alloy 192 MiB, Grafana 256 MiB.
- No observability service is publicly exposed through OCI or Cloudflare by default.
- Collector redacts prompt, completion, customer, financial, and raw payload attributes.
- Observability failures are fail-open for application requests.

### Task 1: Add remote observability Compose and Ansible role

**Files:**
- Create: `deployment/runtime/observability/compose.yml`
- Create: `deployment/ansible/roles/observability/tasks/main.yml`
- Create: `deployment/ansible/roles/observability/templates/compose.yml.j2`
- Modify: `deployment/ansible/site.yml`
- Test: `scripts/observability-contract.test.mjs`

- [ ] **Step 1: Add failing contract checks**

Require all six services, explicit memory limits, named volumes, health checks,
the shared network, loopback-only UI binding, and absence of database services.

- [ ] **Step 2: Implement Compose and role**

Create the `ford-observability` network, render the Compose file under
`{{ remote_root }}/observability`, validate it with `docker compose config`, and
start it with `docker compose up -d --wait`. Keep Grafana behind a Compose
profile named `ui`.

- [ ] **Step 3: Run static validation**

Run `node --test scripts/observability-contract.test.mjs` and
`docker compose -f deployment/runtime/observability/compose.yml config --quiet`.

- [ ] **Step 4: Commit**

```bash
git add deployment/runtime/observability deployment/ansible/site.yml deployment/ansible/roles/observability scripts/observability-contract.test.mjs
git commit -m "ops: add bounded OCI observability capability"
```

### Task 2: Join the app to telemetry network

**Files:**
- Modify: `deployment/ansible/roles/app/templates/compose.yml.j2`
- Modify: `deployment/ansible/roles/app/templates/app.env.j2`
- Modify: `deployment/observability/prometheus/prometheus.yml`
- Modify: `deployment/observability/otel-collector/tempo-config.yaml`
- Test: `scripts/operations-contract.test.mjs`

- [ ] **Step 1: Add failing integration contract assertions**

Require the app to join `ford-observability`, traces to point at
`http://otel-collector:4318/v1/traces`, and Prometheus to scrape the app service
over the shared network.

- [ ] **Step 2: Implement the app integration**

Add the external network to the app Compose service and enable bounded tracing.
Keep metrics scrape-based and leave OTLP metrics disabled until an exporter
dependency is explicitly added. Keep telemetry export fail-open.

- [ ] **Step 3: Verify configuration**

Run the contract tests and both Compose config checks.

- [ ] **Step 4: Commit**

```bash
git add deployment/ansible/roles/app deployment/observability scripts/operations-contract.test.mjs
git commit -m "feat: connect app telemetry to remote observability"
```

### Task 3: Add Mise lifecycle tasks and remote verification

**Files:**
- Create: `ford-ai-service/.mise/tasks/deploy/observability/check`
- Create: `ford-ai-service/.mise/tasks/deploy/observability/start`
- Create: `ford-ai-service/.mise/tasks/deploy/observability/start-ui`
- Create: `ford-ai-service/.mise/tasks/deploy/observability/status`
- Create: `ford-ai-service/.mise/tasks/deploy/observability/logs`
- Create: `ford-ai-service/.mise/tasks/deploy/observability/restart`
- Create: `ford-ai-service/.mise/tasks/deploy/observability/stop`
- Modify: `scripts/operations-contract.test.mjs`

- [ ] **Step 1: Add failing task assertions**

Require every task to load the rendered inventory, target only `emme-1`, and
avoid `docker compose down --volumes`.

- [ ] **Step 2: Implement lifecycle tasks**

Use the existing `remote.sh` and Ansible role. `status` must display only
service names and health, not environment files. `logs` accepts a service name
and defaults to the collector.

- [ ] **Step 3: Verify the command contract**

Run `node --test scripts/operations-contract.test.mjs`.

- [ ] **Step 4: Commit**

```bash
git add ford-ai-service/.mise/tasks/deploy/observability scripts/operations-contract.test.mjs
git commit -m "ops: add remote observability lifecycle tasks"
```

### Task 4: Deploy and run end-to-end checks

- [ ] **Step 1: Run Ansible check mode**

Run `mise run deploy:observability:check` with the real inventory and confirm
only `emme-1` changes.

- [ ] **Step 2: Start the capability**

Run `mise run deploy:observability:start` and verify Docker memory usage remains
below the stated limits.

- [ ] **Step 3: Verify signals**

Check app health, Prometheus target state, Loki readiness, Tempo readiness, and
collector health from the private VM.

- [ ] **Step 4: Verify failure isolation**

Restart the collector and confirm the app health and SSE endpoint remain 200.

- [ ] **Step 5: Enable UI only after signal checks**

Run `mise run deploy:observability:start-ui` only after the core stack passes;
access Grafana through an SSH tunnel, not a public port.
