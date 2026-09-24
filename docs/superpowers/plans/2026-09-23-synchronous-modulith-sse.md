# Synchronous Modulith Events and MVC SSE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add transactional Spring Modulith events and a tenant-scoped MVC SSE bridge while retaining synchronous Spring AI and the existing outbox.

**Architecture:** Channel persistence publishes typed internal events after the transaction. A realtime application listener maps those events to frontend-safe envelopes and sends them through an in-process tenant-filtered hub. `SseEmitter` is only the transport; the database remains authoritative.

**Tech Stack:** Spring Boot 4.1, Spring Modulith JDBC events, Spring MVC `SseEmitter`, Spring JDBC/Liquibase, JUnit 5, AssertJ.

## Global Constraints

- Do not add WebFlux or Spring AI streaming.
- Preserve the existing JDBC outbox for provider delivery.
- Do not expose internal event records directly to the frontend.
- Do not trust SSE events as durable state; clients refetch after reconnect.
- Preserve unrelated working-tree changes.

### Task 1: Add Modulith dependency and application event publication

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `ford-ai-service/build.gradle.kts`
- Modify: `ford-ai-service/src/main/java/com/ford/service/FordServiceApplication.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/domain/InboundMessageAccepted.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/application/ChannelMessageService.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/ChannelMessageEventTest.java`

**Interfaces:**
- `InboundMessageAccepted(InboundMessage message)` is the internal event.
- `ChannelMessageService` publishes exactly one event for a first delivery and none for duplicates.

- [ ] **Step 1: Write the failing event publication test**

Create a test using a recording `ApplicationEventPublisher` and the existing no-arg service path. Assert first delivery publishes `InboundMessageAccepted` and the duplicate does not.

- [ ] **Step 2: Run the focused test and verify it fails**

Run `./gradlew test --tests com.ford.service.channels.ChannelMessageEventTest --no-scan`.
Expected: compilation or assertion failure because the event and publisher integration are absent.

- [ ] **Step 3: Add the Modulith JDBC dependency and application annotation**

Add the Spring Modulith BOM/starter version compatible with Spring Boot 4.1 and annotate the application with `@Modulithic`.

- [ ] **Step 4: Publish after persistence**

Inject `ApplicationEventPublisher` into `ChannelMessageService`, use a no-op publisher in its test-only no-arg constructor, persist the accepted message, then publish `new InboundMessageAccepted(message)`.

- [ ] **Step 5: Run the focused test and verify it passes**

Run the same focused Gradle test; expected result is `BUILD SUCCESSFUL`.

### Task 2: Add frontend event envelope and tenant-filtered hub

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/realtime/domain/FrontendEvent.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/realtime/application/RealtimeEventHub.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/realtime/application/InboundMessageRealtimeListener.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/realtime/RealtimeEventHubTest.java`

**Interfaces:**
- `FrontendEvent` has `id`, `tenantId`, `aggregateId`, `type`, `occurredAt`, and `data`.
- `RealtimeEventHub.subscribe(UUID tenantId, Consumer<FrontendEvent>)` returns an `AutoCloseable` registration.
- `RealtimeEventHub.publish(FrontendEvent)` delivers only to matching tenant subscribers.

- [ ] **Step 1: Write failing hub tests**

Cover tenant isolation and cleanup after a registration is closed.

- [ ] **Step 2: Run the focused test and verify it fails**

Run `./gradlew test --tests com.ford.service.realtime.RealtimeEventHubTest --no-scan`.
Expected: compilation failure because the hub does not exist.

- [ ] **Step 3: Implement the immutable event and hub**

Use a thread-safe subscriber collection, copy event data defensively, and catch subscriber exceptions so one stale client cannot interrupt other deliveries.

- [ ] **Step 4: Add the Modulith listener**

Use `@ApplicationModuleListener` to translate `InboundMessageAccepted` into `message.created` with sanitized message fields (`providerMessageId`, `channel`, `participantId`, `text`, `receivedAt`).

- [ ] **Step 5: Run focused tests and verify they pass**

Run both realtime and channel event tests; expected result is `BUILD SUCCESSFUL`.

### Task 3: Expose the MVC SSE endpoint

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/realtime/adapter/in/web/RealtimeEventStreamController.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/config/SecurityConfiguration.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/realtime/RealtimeEventStreamControllerTest.java`

**Interfaces:**
- `GET /api/v1/operator/events/stream?tenantId={uuid}` returns `text/event-stream`.
- Each event uses its envelope ID as SSE `id`, envelope type as SSE `event`, and the envelope as JSON `data`.
- Client disconnect or send failure removes its registration.

- [ ] **Step 1: Write the failing MVC test**

Verify the controller creates an emitter and returns `text/event-stream`; verify a published tenant event is sent to the matching emitter and not to another tenant.

- [ ] **Step 2: Run the focused test and verify it fails**

Run `./gradlew test --tests com.ford.service.realtime.RealtimeEventStreamControllerTest --no-scan`.
Expected: compilation failure because the controller does not exist.

- [ ] **Step 3: Implement the `SseEmitter` controller**

Register an emitter with the hub, send an initial `connected` event, and register completion/timeout/error cleanup callbacks. Use a bounded emitter timeout configured by the controller constant.

- [ ] **Step 4: Secure the endpoint**

Require `ROLE_OPERATOR` for `/api/v1/operator/events/**` while leaving webhook and actuator rules unchanged.

- [ ] **Step 5: Run focused MVC tests and verify they pass**

Run the controller test and the existing security tests; expected result is `BUILD SUCCESSFUL`.

### Task 4: Verify and commit

**Files:**
- Modify: none beyond the files above.

- [ ] **Step 1: Run unit tests**

Run `./gradlew test --no-scan`.

- [ ] **Step 2: Run integration tests**

Run `./gradlew integrationTest --no-scan`.

- [ ] **Step 3: Inspect dependency graph**

Run `./gradlew dependencies --configuration runtimeClasspath --no-scan` and confirm WebFlux is not introduced and Spring AI remains non-streaming.

- [ ] **Step 4: Review the diff**

Run `git diff --check` and `git diff --stat`; confirm unrelated user changes remain untouched.

- [ ] **Step 5: Commit only the feature files**

Stage the plan/spec and feature files explicitly, then commit with `feat: add synchronous modulith realtime events`.
