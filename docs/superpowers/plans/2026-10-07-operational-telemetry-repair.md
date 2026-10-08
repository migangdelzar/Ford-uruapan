# Operational Telemetry Repair Implementation Plan

> Inline execution, following test-first steps. No real customer conversations or data mutations.

**Goal:** Restore secure metrics and traces, improve MCP/PostgreSQL failure diagnostics, and define actionable alerts.

**Architecture:** The app pushes Micrometer metrics and OpenTelemetry traces to the existing in-network Collector. The Collector forwards redacted telemetry to existing backends; it no longer scrapes the authenticated application endpoint. MCP failures use bounded categories, and PostgreSQL logs identify app/SQLSTATE without recording query text.

**Tech Stack:** Spring Boot 4 / Spring Security / Micrometer / OpenTelemetry Collector, PostgreSQL, Prometheus/Grafana, Gradle and Mise/Ansible.

## Global Constraints

- Do not expose `/actuator/prometheus` publicly or add customer text, SQL statements, secrets, or raw payloads to logs/telemetry.
- Preserve current app/data VM topology and existing secret flow.
- Do not reset databases, create CRM rows, contact customers, or weaken MCP read-only policy.
- Use existing framework telemetry support and bounded labels.

## Task 1: OTLP metrics path

**Files:** `application.yml`, app `.env` examples, Ansible app env template, production/local Collector configs, Prometheus config, Compose files, config contract tests.

- [x] Add a failing config contract proving production metrics are enabled and point to `otel-collector:4318/v1/metrics`.
- [x] Change the production Collector metrics receiver from authenticated app scraping to OTLP input.
- [x] Add a local Prometheus exporter to the local Collector; scrape that internal exporter instead of `/actuator/prometheus`.
- [x] Verify config contracts and build.

## Task 2: MCP failure observability

**Files:** `ObservedToolCallback`, a small failure-classification type, `DefaultAiObservationService`, corresponding tests and observability docs.

- [x] Add failing tests for timeout/network/auth/source/tool-error categories and low-cardinality tool/outcome tags.
- [x] Emit timer/count observations and safe root-cause class/status only; keep arguments and result bodies out of logs.
- [x] Verify relevant tests and build.

## Task 3: PostgreSQL attribution without statements

**Files:** app JDBC URL templates, PostgreSQL Compose templates, operations contract tests and docs.

- [x] Add failing contracts for `ApplicationName=ford-service`, SQLSTATE/application log prefix, verbose error detail, and suppressed error statements.
- [x] Configure both local and emme PostgreSQL containers consistently without changing data volumes.
- [x] Verify contracts, Compose rendering, and live emme-2 settings.

## Task 4: Trace export and alert definitions

**Files:** production app env, local app Compose, alert rule files, Prometheus/Grafana provisioning, docs.

- [x] Assert trace export is enabled in the production profile and targets the existing Collector.
- [x] Add bounded local alerts for Collector scrape, MCP tool, and provider failures. PostgreSQL/application runtime alerting remains a gap because no Prometheus server or Postgres metrics exporter is deployed on emme; Ansible/Docker health checks cover deployment status.
- [x] Document Grafana Cloud API permissions needed if the current write-only telemetry token cannot provision alerts.
- [x] Verify redaction/export configuration and alert YAML syntax. Live Prometheus rule loading is not available in the current remote topology.

## Task 5: Verify and deploy

- [x] Run focused Gradle tests, operations contract tests, full app build (excluding Docker-backed integration tests), and config validation. The unit suite passes; Testcontainers integration tests cannot reach Docker from this workstation.
- [x] Deploy app/collector config via Mise/Ansible; apply additive PostgreSQL logging config without touching stored data.
- [x] Verify app/data health, Collector health and no recent 401/export errors, OTLP receiver startup, and PostgreSQL SQLSTATE/application settings. Live MCP metrics were not generated because verification used no customer conversations.
- [x] Update operations docs with verified outcomes and the Grafana Cloud alert provisioning blocker.

## Deployment notes

- `mise run deploy:data` first stalled while the main site play redundantly installed the OCI CLI backup role. The backup play is now separately tagged and the dedicated `mise run deploy:backup` remains available; the normal data deployment then completed.
- The existing `deploy:restart` task had an empty-array `set -u` bug and passed its selected role through as an unexpected Ansible argument. Both are fixed.
- emme-1 and emme-2 are healthy after rollout. PostgreSQL data volumes were preserved; no reset or migration was run.
- Grafana Cloud alert policies and notifications remain unprovisioned because no Grafana alert API URL/service-account credential is configured. Local Prometheus rule definitions exist but no Prometheus service runs on the emme VMs.
- Follow-up audit found the checked-in production Collector template still had the authenticated `/actuator/prometheus` receiver beside OTLP, despite the earlier rollout note. The stale receiver and Collector-only operator credentials were removed; operations contracts and deployment preflight pass. This correction is pending deployment and live OTLP signal verification.
