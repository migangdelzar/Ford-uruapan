# MÍA WhatsApp Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Process WhatsApp message bursts in order after a short per-conversation debounce, and answer safely according to dealership hours.

**Architecture:** Keep webhook verification, durable receipt, message persistence, and existing bounded worker. Add a persistent debounce scheduler keyed by conversation and a pure hours policy using an explicit timezone/calendar. Bypass debounce for opt-out, explicit human requests, and urgent complaints.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1, PostgreSQL, Liquibase 4.29.2, JUnit 5, Java Time.

## Global Constraints

- Persist every inbound Meta message before delaying it; dedupe provider retries.
- Debounce ordinary bursts 4–6 seconds, per conversation, preserving order and producing one reply opportunity.
- Use `America/Mexico_City`; Mon–Fri 09:00–19:00, Sat 09:00–14:00, Sunday closed.
- Holiday/special-hours calendar is configurable. Until supplied, holiday behavior is explicitly unresolved; do not invent holiday dates.
- First-response target remains under one minute. No specific BDC response ETA is promised.

---

### Task 1: Implement a pure dealership-hours policy

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DealershipHoursPolicy.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DealershipHours.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/application/DealershipHoursPolicyTest.java`

- [x] Add fixed-clock tests for weekday open/close boundaries, Saturday, Sunday, next opening, timezone conversion, configured holiday closure, and special hours. `DealershipHoursPolicyTest` passes.
- [x] Implement the policy with injected `Clock`, `ZoneId`, weekly schedule, and explicit date overrides; do not hard-code a holiday list.
- [x] Re-run focused tests; `./gradlew test --tests '*DealershipHoursPolicyTest' --tests '*ConversationConfiguration*' --offline --no-build-cache --no-daemon` passed.

Implementation note (2026-10-18): `DealershipHoursProperties` provides configurable weekly and date override maps. Defaults use `America/Mexico_City`, Mon–Fri 09:00–19:00, Sat 09:00–14:00, Sunday closed. No holiday dates are embedded. Property-binding examples and conversation response integration remain open under Task 3.

### Task 2: Add per-conversation durable debounce

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/application/InboundDebounceService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/channels/application/port/out/InboundDebounceRepository.java`
- Create: JDBC adapter and Liquibase changeSet under the established `channels` package/resources
- Modify: `InboundMessageAccepted` event consumer/turn enqueue boundary
- Test: debounce service, JDBC adapter, and event consumer test classes

- [x] Implement restart-safe due-time scheduling, conversation ordering, duplicate delivery handling, and bypass for urgent/human/opt-out signals.
- [x] Keep original inbound rows and provider message IDs; coalesce only the scheduled response opportunity.
- [x] Test concurrent arrivals for one conversation and independent conversations with a fixed clock.
- [x] Run focused unit/integration tests. Fresh Service integration suite passes 76 tests with zero failures/errors; three unrelated catalog snapshot tests remain env-gated.

### Task 3: Apply hours policy to response planning

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java`
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: `ford-ai-service/src/main/resources/application-prod.yml`
- Test: focused turn-processor and configuration binding tests

- [x] Test that catalog questions remain answerable after hours, accepted handoffs enter the BDC queue, no response ETA is invented, and unresolved holiday state is reported honestly.
- [x] Wire property-bound weekly/special-hour values through the Spring configuration root; default timezone is `America/Mexico_City`.
- [x] Run focused and full Service unit tests; full unit suite passes 799/799.

## Completion Gate

- [x] Durable inbound receipts survive delayed scheduling and worker restart.
- [x] Burst, retry, urgency, opt-out, hours-boundary, and explicit-date override tests pass.
- [ ] End-to-end p95 customer response under one minute still needs a provider-backed benchmark; holiday date values and calendar approval remain external inputs.
