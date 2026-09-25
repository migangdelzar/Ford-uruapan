# Grafana Cloud Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** Send Ford application traces, Micrometer metrics, and container logs to Grafana Cloud while preserving redacted AI trace export to Langfuse Cloud.

**Architecture:** The Spring Boot application continues to emit traces to the local OpenTelemetry Collector on the application host. A Grafana Cloud Collector profile receives OTLP traces, scrapes `/actuator/prometheus`, and reads Docker JSON logs, then exports all signals to Grafana Cloud through one authenticated OTLP endpoint. The profile also exports traces to Langfuse Cloud; local Tempo/Loki/Alloy remain available for the local-only profile.

**Tech Stack:** OpenTelemetry Collector Contrib, Grafana Cloud OTLP, Spring Boot Actuator/Micrometer, Docker Compose, Mise, Node test runner.

## Global Constraints

- Grafana credentials remain server-side and are never exposed to the frontend.
- Collector export is bounded, batched, retried, PII-redacted where applicable, and fail-open for business requests.
- The existing Langfuse Cloud profile and local observability profile must continue to work.
- No WebFlux or Spring AI streaming changes are part of this task.

---

### Task 1: Add failing Grafana Cloud contracts

**Files:**
- Modify: `scripts/observability-contract.test.mjs`

- [ ] Add tests requiring the Grafana Cloud Collector profile to define Grafana authentication, OTLP, Prometheus, and Docker log pipelines.
- [ ] Add tests requiring Compose to pass Grafana variables and mount Docker container logs.
- [ ] Add tests requiring Mise lifecycle tasks for the Grafana Cloud profile.
- [ ] Run `node --test scripts/observability-contract.test.mjs` and confirm the new assertions fail because the profile does not exist.

### Task 2: Implement the Grafana Cloud Collector profile

**Files:**
- Create: `deployment/observability/otel-collector/grafana-cloud-config.yaml`
- Modify: `deployment/observability/otel-collector/README.md`

- [ ] Add OTLP receivers for application traces.
- [ ] Add a Prometheus receiver scraping `ford-service:8080/actuator/prometheus`.
- [ ] Add a Docker JSON `filelog` receiver for `/var/lib/docker/containers/*/*-json.log`.
- [ ] Add bounded memory, batching, trace redaction, and retry queues.
- [ ] Add Grafana Basic Auth using `GRAFANA_CLOUD_INSTANCE_ID` and `GRAFANA_CLOUD_API_TOKEN`.
- [ ] Export traces, metrics, and logs to `GRAFANA_CLOUD_OTLP_ENDPOINT`.
- [ ] Export redacted traces to Langfuse Cloud.
- [ ] Document required variables and the Linux Docker log mount.

### Task 3: Wire Compose and Mise

**Files:**
- Modify: `ford-ai-service/docker-compose.yml`
- Modify: `ford-ai-service/.mise/lib/compose.sh`
- Create: `ford-ai-service/.mise/tasks/telemetry/grafana-cloud/start`
- Create: `ford-ai-service/.mise/tasks/telemetry/grafana-cloud/stop`
- Create: `ford-ai-service/.mise/tasks/telemetry/grafana-cloud/restart`
- Create: `ford-ai-service/.mise/tasks/telemetry/grafana-cloud/status`
- Create: `ford-ai-service/.mise/tasks/telemetry/grafana-cloud/check`

- [ ] Pass Grafana Cloud endpoint, instance ID, and token only to the Collector container.
- [ ] Allow the Collector config and Docker log root to be selected by environment.
- [ ] Add `require_grafana_cloud_env` and a complete start/stop/restart/status/check lifecycle.
- [ ] Start only the app, Collector, and required local database services for the Grafana Cloud profile; do not require local Loki, Tempo, Prometheus, or Alloy.

### Task 4: Verify locally and document operations

**Files:**
- Modify: `docs/observability.md`

- [ ] Run the contract tests, YAML/config validation, Compose config validation, and shell syntax checks.
- [ ] Run the local Grafana Cloud profile with credentials supplied only by the shell environment.
- [ ] Verify Collector readiness and application health.
- [ ] Document Grafana Explore verification and OCI deployment variables.

### Task 5: Prepare OCI deployment

**Files:**
- Modify: `deployment/config/oci.env.example` if present, otherwise document the required ignored variables.
- Modify: relevant Ansible deployment templates only if the existing app role does not pass the new variables.

- [ ] Pass Grafana variables to `emme-1` using Ansible secret handling with no task output.
- [ ] Deploy the profile to the app VM only after local validation passes.
- [ ] Verify the remote Collector, application health, and outbound Grafana connectivity without printing secrets.
