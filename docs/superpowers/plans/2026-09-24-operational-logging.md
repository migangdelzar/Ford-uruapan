# Operational Logging Implementation Plan

**Goal:** Make production logs concise and operationally useful at `INFO`, including safe latency measurements.

**Architecture:** Use the existing SLF4J facade directly at application boundaries. Configure noisy Spring and infrastructure packages at `WARN`, while application events remain at `INFO`. Log only bounded technical metadata; never log message text, prompts, payloads, tokens, phone numbers, or provider response bodies.

**Tech Stack:** Spring Boot 4.1, SLF4J, YAML logging configuration, JUnit 6/AssertJ.

## Global Constraints

- The current operational level is `INFO`; latency is also logged at `INFO` for the current debugging phase.
- `DEBUG` remains available for future detail but is not required to operate the service now.
- Errors include exception type and bounded technical context at `INFO`; stack traces are available at `DEBUG` and normal logs never include customer content or secrets.
- Preserve unrelated working-tree changes.

### Task 1: Configure quiet framework logging

**Files:**
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: `ford-ai-service/build.gradle.kts`

Set application logging to `INFO`, framework/infrastructure noise to `WARN`, disable the startup banner, and keep Spring Boot startup messages visible.
Add the Micrometer Prometheus registry and an application-wide `application` metric tag.

### Task 2: Log inbound message and webhook outcomes

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/MetaWebhookController.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/application/ChannelMessageService.java`
- Test: existing webhook/service tests plus focused logging assertions where stable.

Log accepted, duplicate, invalid, unauthorized, and unknown-channel outcomes with channel, result, and `durationMs`; never log request bodies or message text.

### Task 3: Log AI, outbox, synchronization, prompt, and SSE operations

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/realtime/adapter/in/web/RealtimeEventStreamController.java`
- Modify: `SpringAiChatCompletionAdapter.java`
- Modify: `OutboxPublisher.java`
- Modify: `DefaultSyncCoordinator.java`
- Modify: `SystemPromptService.java`
- Modify: `RealtimeEventStreamController.java`

Log successful completions, failed deliveries, sync summaries, prompt version changes, SSE connection lifecycle, and duration at `INFO`. Log exception type at `INFO` and stack traces only at `DEBUG`.

### Task 4: Provide the telemetry transport stack

**Files:**
- Modify: `ford-ai-service/docker-compose.yml`
- Create: `deployment/observability/prometheus/prometheus.yml`
- Create: `deployment/observability/loki/loki.yml`
- Create: `deployment/observability/tempo/tempo.yml`
- Create: `deployment/observability/otel-collector/tempo-config.yaml`
- Create: `deployment/observability/alloy/config.alloy`
- Create: `deployment/observability/grafana/provisioning/datasources/datasources.yml`
- Modify: `docs/observability.md`

Run Prometheus, Loki, Tempo, Grafana, Alloy, and the trace collector on the Compose network. Prometheus scrapes Actuator, Alloy forwards Docker stdout to Loki, and OTLP traces route through the collector to Tempo.

### Task 5: Verify and document

Run `git diff --check`, `./gradlew test integrationTest --no-scan`, and inspect the staged diff to ensure no payload or secret is logged. Update operations documentation with the active logging policy.
