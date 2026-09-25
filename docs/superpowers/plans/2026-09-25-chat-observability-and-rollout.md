# Chat Observability and Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make accepted-to-delivered chat progress and failures visible without exposing customer content, credentials, or raw tool payloads, then release changes safely.

**Architecture:** Extend the existing Micrometer observation and PII-redaction boundaries with low-cardinality lifecycle events. Reuse the existing evaluator and mise deployment tasks; roll out an immutable image on the application host only after tests and image rollback preparation.

**Tech Stack:** Spring Boot Actuator/Micrometer/OpenTelemetry, Spring Modulith publication registry, Gradle, mise, Ansible, GHCR, Docker Compose.

## Global Constraints

- Correlate lifecycle events with stable internal IDs; never log message text, API tokens, MCP credentials, or raw tool results.
- Use low-cardinality metric tags; never tag by tenant, conversation, phone number, message ID, model response, or prompt.
- App runs on `emme-1`; PostgreSQL and Redis stay on `emme-2` unless the deployment inventory explicitly says otherwise.
- Deploy through existing mise/Ansible/GHCR tasks; do not hand-edit remote containers or propagate secrets from logs.
- Keep the previous immutable app image available for rollback; no production replay or customer test message without explicit approval.

---

### Task 1: Add privacy-safe lifecycle observations

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/observability/DefaultAiObservationService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/observability/MicrometerAiMetrics.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/ProviderRoutingChatCompletionPort.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/outbox/OutboxPublisher.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/observability/AiObservationServiceTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/observability/PiiObservationFilterTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationWorkflowTest.java`

**Interfaces:**
- Reuse: `AiObservationService.observe(String, Map<String,String>, Supplier<T>)` and `event(String, Map<String,String>)`.
- Produce: bounded outcome/category events for inbound processing, provider attempts, projection outcomes, and outbound delivery/retry/dead-letter.

- [ ] **Step 1: Add failing observation tests.** Assert provider category and channel/outcome can be recorded; assert tenant UUIDs, conversation IDs, user text, phone numbers, tokens, and raw tool payloads never become metric tags or log fields.
- [ ] **Step 2: Run focused tests.** Run `./gradlew test --tests com.ford.service.observability.AiObservationServiceTest --tests com.ford.service.observability.PiiObservationFilterTest`. Expected: FAIL for missing lifecycle dimensions or privacy assertions.
- [ ] **Step 3: Add only bounded event dimensions.** Inject existing `AiObservationService` through the current composition roots and emit observations around processing boundaries using fixed operation names and bounded enums. Reuse `PiiRedactor`; do not attach full exception messages or provider response bodies.
- [ ] **Step 4: Run observability tests.** Expected: outcomes and failure categories are visible, all PII and high-cardinality tags remain absent.
- [ ] **Step 5: Commit metrics and tests.** Commit only observability/source/tests with `feat(observability): trace chat turn lifecycle safely`.

### Task 2: Gate release on deterministic and PostgreSQL verification

**Files:**
- Modify: `ford-ai-service/docs/operations.md`
- Modify: `docs/observability.md`

**Interfaces:**
- Existing commands: `mise run test:unit`, `mise run test:integration`, `mise run test:check`, `mise run test:ai-evaluation`.
- Produces: documented release gate and production indicators for memory projection, provider failures, and outbox delivery.

- [ ] **Step 1: Update the runbook.** Document exact commands, required Docker/Testcontainers setup, safe inspection of Modulith publications and outbox state, health checks, and redacted log filters. State that a `202` webhook response is acceptance only, not proof of assistant delivery.
- [ ] **Step 2: Document actionable indicators.** List metric names and bounded outcome/category values created in Task 1; include recovery interpretation for 429, projection failure, and dead-letter delivery. Do not include deployment secrets or values from `.env`.
- [ ] **Step 3: Check the docs diff.** Run `git diff --check -- ford-ai-service/docs/operations.md docs/observability.md`. Expected: no whitespace errors and no credentials or customer content in either file.
- [ ] **Step 4: Run release gates.** From `ford-ai-service`, run `mise run test:unit`, `mise run test:integration`, `mise run test:ai-evaluation`, and `mise run test:check`. Expected: all pass with Testcontainers available and deterministic evaluation requiring no API key.
- [ ] **Step 5: Commit docs and test-gate changes.** Commit only files listed above with `docs(operations): document chat failure triage and release gates`.

### Task 3: Deploy and verify the immutable app image

**Files:**
- No code edits. Use existing task definitions in `ford-ai-service/.mise/tasks/deploy/`, `ford-ai-service/.mise/tasks/release/`, and Ansible deployment roles.

**Interfaces:**
- Existing tasks: `mise run deploy:check`, `mise run release:publish`, `mise run deploy:image-backup`, `mise run deploy:app`, `mise run deploy:status`, `mise run deploy:logs`.
- Produces: verified app rollout on `emme-1`, unchanged data services on `emme-2`, and a known prior image for rollback.

- [ ] **Step 1: Validate config without exposing values.** Run `mise run deploy:check` and `mise run deploy:status`; expected: inventory and required tools validate, app/data roles match `emme-1`/`emme-2`.
- [ ] **Step 2: Capture rollback image.** Record the current image reference/digest from `mise run deploy:status` as `PREVIOUS_APP_IMAGE`, then run `mise run deploy:image-backup "$PREVIOUS_APP_IMAGE"` before publishing.
- [ ] **Step 3: Publish and deploy through the existing pipeline.** Run `mise run release:publish`, then `mise run deploy:app`. Expected: Ansible replaces only the app on `emme-1`; no data volume or `emme-2` service is reset.
- [ ] **Step 4: Verify deployment and logs.** Run `mise run deploy:status`, `mise run deploy:logs`, and the existing health check. Confirm startup, MCP tool inventory, DB/Redis health, no JSONB errors, no `UnexpectedRollbackException`, and no sustained 429 retry loop.
- [ ] **Step 5: Run a synthetic conversation only.** Use the existing automated vertical-flow test or a non-customer test fixture. Do not send a production WhatsApp message without separate approval.
- [ ] **Step 6: Roll back on a critical regression.** If health, memory writes, outbox delivery, or privacy checks fail, run `EMME_APP_IMAGE="$PREVIOUS_APP_IMAGE" mise run deploy:app`; verify with `mise run deploy:status` and `mise run deploy:logs`. Do not edit containers directly.
- [ ] **Step 7: Record release evidence.** Add image tag/digest, test gate results, health result, and redacted log summary to the release record without secrets or message contents.

## Dependencies and verification

- Task 1 precedes Task 2; Task 3 follows all reliability/provider/grounding plans and their test gates.
- Do not run Task 3 until the user explicitly authorizes deployment after implementation; `deploy:app` is a production mutation.
- Existing mise task inventory was checked; use those tasks instead of direct SSH/Docker commands.
