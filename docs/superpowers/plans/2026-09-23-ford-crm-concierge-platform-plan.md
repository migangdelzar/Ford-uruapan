# Ford CRM Concierge Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose a secure, tenant-scoped `/api/v1` operator API backed by the existing Ford conversation domain, and add durable AI, lead, delivery, conversation, and audit metrics that the future BFF can query without reading provider tables directly.

**Architecture:** Keep Meta webhooks, AI orchestration, PostgreSQL persistence, and outbound delivery inside the OCI service. Add thin REST adapters under `/api/v1` that call application ports and return stable DTOs. Use JWT authorities and tenant claims for authorization, optimistic concurrency for conversation mutations, `Idempotency-Key` for retriable commands, RFC 9457 Problem Details for errors, and an append-only metric-fact model with query projections. The new application exposes one versioned operator API surface; Meta webhooks remain provider callbacks outside that surface.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Security 7, Spring MVC, Spring JDBC `JdbcClient`, Liquibase, PostgreSQL, Spring Modulith events, Micrometer, JUnit 5, Spring Security Test, Testcontainers PostgreSQL.

## Global Constraints

- Do not place browser-facing code or provider-specific Meta/AI payloads in the OCI service's public DTOs.
- Do not trust `X-User-Role`, `X-Dealership-ID`, or tenant headers for authorization; identity comes from the validated JWT.
- Every tenant-scoped query must include the authenticated tenant boundary before applying user filters.
- Mutating conversation endpoints must require `Idempotency-Key`; conversation commands must support `If-Match` using the conversation version.
- Webhook endpoints remain public only for Meta verification/signature validation and must not be moved behind operator JWT authentication.
- Do not persist raw prompts, raw completions, access tokens, phone numbers, or provider error bodies in metrics.
- Continue using `JdbcClient` and the project's current Spring dependency BOM.
- Every implementation task starts with a failing focused test, then implementation, then focused and relevant full verification.
- Keep OpenAPI status markers accurate: an operation is `implemented` only after its controller and tests exist.

---

## Task 1: Normalize the public contract and security vocabulary

**Files:**
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`
- Modify: `ford-ai-service/docs/openapi/security.md`
- Modify: `ford-ai-service/src/main/java/com/ford/service/config/SecurityConfiguration.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/config/SecurityConfigurationTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/config/OpenApiContractTest.java`

- [ ] Add a focused security test proving that a JWT with `operator:read` and a tenant claim can access a read endpoint, a JWT without the authority receives `403`, and a token for another tenant cannot read the resource.
- [ ] Add a focused contract test that loads `frontend-api.yaml`, verifies OpenAPI 3.1, rejects duplicate operation IDs, and asserts that all target `/api/v1` operation status markers match the implementation list maintained in the test.
- [ ] Extend the JWT converter to preserve scopes as `SCOPE_*`, roles as `ROLE_*`, and expose normalized tenant IDs through a dedicated authenticated-operator helper rather than request headers.
- [ ] Add explicit authority constants for `operator:read`, `operator:write`, `handoff:manage`, `leads:read`, `leads:write`, `metrics:read`, `events:read`, `inventory:read`, `finance:quote`, `appointments:read`, `appointments:write`, `bot:manage`, and `testlab:run`.
- [ ] Update the contract security section to use bearer JWT only, remove role/dealership header security requirements, document tenant claim enforcement, add `Idempotency-Key`, `If-Match`, and `X-Correlation-Id` parameters, and use canonical API status values.
- [ ] Run `./gradlew test --tests '*SecurityConfigurationTest' --tests '*OpenApiContractTest'` and the existing security tests; commit `docs/api: normalize frontend contract and authorities`.

## Task 2: Add tenant-aware operator context and query ports

**Files:**
- Add: `ford-ai-service/src/main/java/com/ford/service/operator/application/AuthenticatedOperator.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/operator/application/OperatorAuthorization.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/operator/application/OperatorApiConfiguration.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/application/port/in/ConversationQuery.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/application/port/in/ConversationCommand.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/application/port/out/OperatorConversationRepository.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/api/ConversationView.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/api/ConversationMessageView.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/api/ConversationPage.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/api/MessagePage.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/api/ConversationFilter.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/operator/OperatorAuthorizationTest.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/conversations/OperatorConversationRepositoryTest.java`

- [ ] Write unit tests for extracting `sub`, normalized roles, authorities, and one-or-many tenant IDs from a Spring `Authentication`; reject a missing tenant for tenant-scoped operations.
- [ ] Define query records using `UUID tenantId`, cursor, page size, state, channel, search, and assigned operator filters; ensure page size is bounded and cursors are opaque to callers.
- [ ] Define stable view records for conversation, message, participant, handoff state, lead summary, version, and timestamps; map internal `BOT_ACTIVE`/`LEAD_CAPTURE`/`HUMAN_PENDING`/`HUMAN_ACTIVE`/`CLOSED` to the API vocabulary.
- [ ] Implement `JdbcOperatorConversationRepository` with tenant-scoped `JdbcClient` queries, keyset pagination, latest-message projection, and message pagination ordered by occurrence time plus ID.
- [ ] Add repository tests for tenant isolation, empty pages, cursor continuation, state/channel filtering, search parameter binding, and message ordering.
- [ ] Run the focused repository tests and database schema integration test; commit `feat(api): add tenant-scoped conversation query ports`.

## Task 3: Implement conversation and message read endpoints

**Files:**
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/in/web/ConversationController.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/in/web/ConversationApiMapper.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationControllerTest.java`
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`
- Modify: `ford-ai-service/src/main/java/com/ford/service/config/SecurityConfiguration.java`

- [ ] Add failing MVC tests for `GET /api/v1/conversations`, `GET /api/v1/conversations/{conversationId}`, and `GET /api/v1/conversations/{conversationId}/messages` covering valid JWT, missing authority, invalid UUID, not found, tenant mismatch, cursor/page size validation, and Problem Details content type.
- [ ] Implement the controller using application ports and the authenticated operator context; do not call repositories directly from the controller.
- [ ] Return `ConversationPage` and `MessagePage` with `data` and `page` metadata matching the OpenAPI schemas; include `ETag` from the conversation version.
- [ ] Add consistent `404` behavior without revealing whether a resource exists in another tenant.
- [ ] Mark the three read operations `implemented` in the OpenAPI document and update security scope documentation.
- [ ] Run MVC tests and the full unit test suite; commit `feat(api): expose tenant-scoped conversation reads`.

## Task 4: Implement safe conversation commands and outbound message acceptance

**Files:**
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/application/port/in/ConversationCommandService.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationCommandService.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/api/SendMessageRequest.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/api/CommandReceipt.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/JdbcIdempotencyRepository.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/conversations/application/port/out/IdempotencyRepository.java`
- Add: `ford-ai-service/src/main/resources/db/changelog/changes/009-operator-commands.yaml`
- Modify: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/in/web/ConversationController.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationCommandServiceTest.java`
- Add: `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/OperatorConversationApiIntegrationTest.java`

- [ ] Write service tests for missing idempotency keys, replaying the same key with the same payload, reusing a key with a different payload, stale `If-Match`, closed conversation, `HUMAN_ACTIVE` ownership conflict, and accepted async delivery.
- [ ] Add an idempotency table keyed by tenant, actor, operation, and key, storing a request hash and serialized response receipt; add a uniqueness constraint and retention timestamp.
- [ ] Implement `DefaultConversationCommandService` so it validates tenant/authority, checks idempotency before mutation, checks the expected conversation version, persists the outbound conversation message, publishes `OutboundDeliveryRequested`, and stores the receipt atomically.
- [ ] Require `Idempotency-Key` and `If-Match` on `POST /api/v1/conversations/{id}/messages`; support internal notes without provider delivery and free-form outbound messages through the existing outbox path.
- [ ] Return `202` with message ID, conversation ID, `accepted`, delivery status, and current version; return `409` with a typed conflict code for stale version, state conflict, or provider window closure.
- [ ] Add Testcontainers coverage for atomic replay, transaction rollback, and tenant isolation; run `./gradlew test integrationTest --tests '*OperatorConversationApiIntegrationTest'`; commit `feat(api): add idempotent conversation commands`.

## Task 5: Add takeover, handback, handoff listing, lead reads, and advisor reads

**Files:**
- Add: `ford-ai-service/src/main/java/com/ford/service/handoff/adapter/in/web/OperatorConversationHandoffController.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/handoff/application/OperatorHandoffCommandService.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/leads/adapter/in/web/LeadController.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/leads/adapter/out/JdbcOperatorLeadRepository.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/team/adapter/in/web/AdvisorController.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/team/application/AdvisorQueryService.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/team/api/AdvisorView.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/handoff/OperatorHandoffControllerTest.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/leads/LeadControllerTest.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/team/AdvisorControllerTest.java`
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`

- [ ] Write tests for atomic takeover ownership, handback authorization, duplicate command replay, stale version, and no cross-tenant handoff visibility.
- [ ] Adapt the existing handoff service behind application ports rather than duplicating state transitions in controllers; preserve the existing human-handoff-disabled behavior as a configurable policy.
- [ ] Implement `/api/v1/conversations/{id}/takeover`, `/handback`, `/api/v1/handoffs`, and the four handoff action routes with `handoff:manage` and tenant checks.
- [ ] Implement lead list/detail reads and advisor list reads using tenant-scoped JDBC queries; return stable DTOs even when a conversation has no lead row.
- [ ] Mark corresponding OpenAPI operations implemented and add explicit `403` scope semantics.
- [ ] Run focused MVC tests plus existing handoff tests; commit `feat(api): expose handoffs leads and advisors`.

## Task 6: Implement lead updates and durable metrics facts

**Files:**
- Add: `ford-ai-service/src/main/java/com/ford/service/leads/application/LeadCommandService.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/leads/adapter/out/JdbcLeadCommandRepository.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/domain/AiExecutionFact.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/domain/ConversationMetricFact.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/domain/LeadMetricFact.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/domain/DeliveryMetricFact.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/application/MetricFactRecorder.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/adapter/out/JdbcMetricFactRecorder.java`
- Add: `ford-ai-service/src/main/resources/db/changelog/changes/010-metric-facts.yaml`
- Modify: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `ford-ai-service/src/main/java/com/ford/service/leads/adapter/in/web/LeadController.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/metrics/MetricFactRecorderTest.java`
- Add: `ford-ai-service/src/integrationTest/java/com/ford/service/metrics/MetricFactSchemaIntegrationTest.java`

- [ ] Write tests for lead patch validation, source attribution (`AI`/`HUMAN`), idempotent lead updates, and recording facts without storing message content or PII.
- [ ] Create append-only fact tables with tenant, conversation/lead references, dimensions, numeric measures, bounded JSON metadata, event key, and `created_at`; add indexes for tenant/time and common dimensions.
- [ ] Implement an outbox/event-backed `MetricFactRecorder` that tolerates duplicate event delivery through a unique event key and never blocks the customer response path.
- [ ] Record AI execution facts from the existing `DefaultAiObservationService`/decision workflow with provider, model, latency, classification confidence, intent, tool family, outcome, fallback, handoff recommendation, token counts, and estimated cost.
- [ ] Record conversation and delivery facts from inbound persistence, outbox delivery status, handoff transitions, and lead temperature changes.
- [ ] Add a `PATCH /api/v1/leads/{leadId}` command with idempotency and optimistic version checks; publish a durable lead-updated event.
- [ ] Run schema integration tests and full unit tests; commit `feat(metrics): persist redacted AI lead conversation and delivery facts`.

## Task 7: Add metrics query ports and dashboard endpoints

**Files:**
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/api/MetricsQuery.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/api/MetricsOverview.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/api/BotMetrics.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/api/LeadMetrics.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/api/ConversationMetrics.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/api/ChannelMetrics.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/api/AdvisorMetrics.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/api/FunnelMetrics.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/api/TimeSeriesMetrics.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/application/MetricsQueryService.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/adapter/out/JdbcMetricsQueryRepository.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/metrics/adapter/in/web/MetricsController.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/metrics/MetricsControllerTest.java`
- Add: `ford-ai-service/src/integrationTest/java/com/ford/service/metrics/MetricsQueryIntegrationTest.java`
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`

- [ ] Write controller tests for all dashboard endpoints, date/timezone validation, authority checks, tenant isolation, empty periods, and consistent decimal/percentage serialization.
- [ ] Implement bounded `from`, `to`, `timezone`, `branchId`, `channel`, `advisorId`, and `groupBy` query parsing; default to a safe recent period and reject excessive ranges.
- [ ] Implement aggregate queries for overview, bot execution, lead funnel, conversation/channel delivery, advisor workload, and time series using metric facts plus authoritative CRM fields where available.
- [ ] Expose the endpoints `/api/v1/metrics/overview`, `/bot`, `/leads`, `/conversations`, `/channels`, `/advisors`, `/funnel`, and `/timeseries` with `metrics:read`.
- [ ] Add OpenAPI schemas for measures, dimensions, period metadata, data quality, and unavailable CRM-derived measures; never fabricate revenue or margin values when no authoritative source exists.
- [ ] Run focused MVC and Testcontainers tests; commit `feat(metrics): expose tenant-scoped dashboard queries`.

## Task 8: Add audit events and realtime event delivery

**Files:**
- Add: `ford-ai-service/src/main/java/com/ford/service/events/api/AuditEventView.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/events/application/AuditEventQueryService.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/events/adapter/out/JdbcAuditEventRepository.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/events/adapter/in/web/AuditEventController.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/events/adapter/in/web/RealtimeStreamController.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/events/application/RealtimeEventBroker.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/events/AuditEventControllerTest.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/events/RealtimeStreamControllerTest.java`
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`

- [ ] Write tests for tenant-scoped audit pagination, redaction, event ordering, SSE content type, keepalive, authorization, and disconnect cleanup.
- [ ] Implement audit reads over the existing `audit_event` table with stable event types, severity, actor, conversation ID, and metadata redaction.
- [ ] Implement a bounded in-process realtime broker for single-instance operation with an explicit deployment property; publish domain event projections for conversation, message, delivery, handoff, lead, advisor, and metrics updates.
- [ ] Expose `/api/v1/events` and `/api/v1/events/stream` with `events:read`; document that a multi-instance deployment must replace the broker with Redis pub/sub or another shared adapter.
- [ ] Run focused tests and verify no sensitive payloads are sent through SSE; commit `feat(api): add audit and realtime operator streams`.

## Task 9: Add Meta window/HSM, delivery, and dealership adapter boundaries

**Files:**
- Add: `ford-ai-service/src/main/java/com/ford/service/channels/application/port/out/CustomerWindowPort.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/channels/application/port/in/TemplateMessageCommand.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/web/MetaWindowController.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/dealership/application/port/out/DealershipPort.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/dealership/application/port/out/InventoryPort.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/dealership/application/port/out/FinanceQuotePort.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/dealership/application/port/out/AppointmentPort.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/dealership/application/port/out/LeadSyncPort.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/dealership/adapter/in/web/DealershipController.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/channels/MetaWindowControllerTest.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/dealership/DealershipControllerTest.java`
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`

- [ ] Write tests for open/closed 24-hour windows, free-form message rejection, approved template validation, and tenant/provider binding.
- [ ] Expose window state and HSM command through application ports; route actual transmission through the existing outbox and Meta delivery adapter.
- [ ] Define dealership ports and DTOs for inventory, vehicle details, finance quote, slots, booking, branches on duty, and CRM lead sync; keep controller contracts independent of the external API client.
- [ ] Implement only adapters that have configured upstream credentials; return a typed `upstream_unavailable` Problem Detail when a port is not configured instead of returning fake data.
- [ ] Mark contract operations implemented only for the adapters and commands covered by tests; commit `feat(api): add Meta window and dealership integration boundaries`.

## Task 10: Restrict bot configuration and test-lab operations

**Files:**
- Add: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/web/BotConfigurationController.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/testlab/adapter/in/web/TestLabController.java`
- Add: `ford-ai-service/src/main/java/com/ford/service/testlab/application/TestLabService.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/ai/BotConfigurationControllerTest.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/testlab/TestLabControllerTest.java`
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`

- [ ] Write tests proving bot configuration is readable only with `bot:manage` and mutations require `ROLE_ADMIN` plus `bot:manage`.
- [ ] Expose redacted provider/model configuration, classification/TypeSafe/Jev enablement, handoff policy, memory policy, and safe prompt metadata; never return secrets or raw provider keys.
- [ ] Implement test-lab fault settings behind a dev/test property and `testlab:run`; reject all test-lab mutations in production profiles.
- [ ] Mark bot config and test-lab operations accurately in OpenAPI; commit `feat(api): secure bot configuration and test lab`.

## Task 11: Contract, integration, and operational verification

**Files:**
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`
- Modify: `ford-ai-service/docs/openapi/security.md`
- Add: `ford-ai-service/src/integrationTest/java/com/ford/service/api/FrontendContractIntegrationTest.java`
- Add: `ford-ai-service/src/integrationTest/java/com/ford/service/api/TenantIsolationIntegrationTest.java`
- Add: `ford-ai-service/src/test/java/com/ford/service/config/ProblemDetailContractTest.java`
- Modify: `ford-ai-service/README.md`

- [ ] Add a Testcontainers vertical test covering JWT-authenticated list/read, message command replay, takeover, lead update, metric fact recording, metric query, audit query, and SSE publication.
- [ ] Add a tenant-isolation test that seeds the same provider identifiers in two tenants and proves no endpoint leaks rows across the boundary.
- [ ] Validate every implemented operation against the OpenAPI document and ensure planned operations are clearly documented as unavailable.
- [ ] Verify `401`, `403`, `404`, `409`, `422`, and upstream failure Problem Details have stable `type`, `title`, `status`, `code`, `traceId`, and `correlationId` properties.
- [ ] Document the BFF forwarding requirements, JWT claims, required headers, SSE proxy behavior, environment variables, and database reset command without exposing secrets.
- [ ] Run `./gradlew clean check`, inspect test reports, run the OpenAPI parser test, and commit `test(api): verify contract security tenancy and metrics`.

## Completion Checklist

- [ ] All `/api/v1` operations marked `implemented` have controller, application-port, repository/adapter, and automated tests.
- [ ] No browser-facing endpoint accepts role, dealership, or tenant headers as authorization input.
- [ ] All mutating commands are idempotent and version-aware.
- [ ] AI, lead, conversation, delivery, and audit facts are durable, tenant-scoped, deduplicated, and redacted.
- [ ] Dashboard responses distinguish authoritative CRM values from unavailable values.
- [ ] Meta webhook verification and signature handling remain compatible with Meta retries.
- [ ] Full verification passes before branch integration.
