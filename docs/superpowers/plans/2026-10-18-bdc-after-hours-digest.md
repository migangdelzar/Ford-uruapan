# BDC After-Hours Digest Implementation Plan

> **For agentic workers:** Execute each task in order using test-first changes; preserve unrelated worktrees and use the integrated MÍA release candidate.

**Goal:** Show a tenant-scoped after-hours handoff digest in Ford-web by 08:45 on the next configured dealership opening day.

**Architecture:** Ford Service computes the schedule window through its configured hours policy and reads matching handoff tickets through its existing JDBC repository and operator authorization. Ford-web's authenticated same-origin BFF proxies a read-only endpoint and renders a retryable inbox card; no separate notification service or digest table is added.

**Tech Stack:** Ford Service Java 25, Spring Boot 4.1.1, Spring AI 2.0.1 project BOM, Spring JDBC `JdbcClient`, Liquibase/PostgreSQL; Ford-web TypeScript/React/Vitest/Cloudflare Pages Functions.

## Global Constraints

- Use `America/Mexico_City` and existing property-bound weekday/Saturday hours and explicit date overrides.
- Do not invent holiday dates; production calendar approval remains a launch gate.
- Ford Service's existing BDC handoff queue is authoritative; the digest is read-only and repeatable.
- Tenant scope comes only from authenticated operator claims and must be checked for the requested tenant.
- Show ticket identity/status and packet completeness, not a duplicate of personal packet fields.
- No email, WhatsApp, Slack, push alert, external CRM provider, scheduler, or parity gate.
- Preserve partial handoff packet semantics and label them accurately.
- Do not claim production SLA from unit tests; use protected release and operator evidence.

---

## File map

### Ford Service (`/private/tmp/ford-service-emme-pages-cutover`)

- `ford-ai-service/src/main/java/com/ford/service/conversations/application/DealershipHoursPolicy.java`: digest date/window calculations using injected clock and configured schedule.
- `ford-ai-service/src/main/java/com/ford/service/handoff/application/port/out/HandoffRepository.java`: bounded tenant/date range read contract.
- `ford-ai-service/src/main/java/com/ford/service/handoff/adapter/out/JdbcHandoffRepository.java`: JDBC query for the digest window.
- `ford-ai-service/src/main/java/com/ford/service/handoff/application/AfterHoursDigestService.java`: authorization, schedule, inclusion, and summary counts.
- `ford-ai-service/src/main/java/com/ford/service/handoff/api/AfterHoursDigest.java`: response and item records.
- `ford-ai-service/src/main/java/com/ford/service/handoff/adapter/in/web/OperatorConversationHandoffController.java`: authenticated `GET /api/v1/handoffs/after-hours-digest?tenantId=...` endpoint.
- Focused tests for policy, application service, and controller; `docs/openapi/frontend-api.yaml`; `tasks/todo.md`. `JdbcHandoffRepositoryDigestTest` uses H2 PostgreSQL mode to verify tenant isolation, `[startInclusive, endExclusive)` boundaries, and ordering.

### Ford-web (`/private/tmp/ford-mia-phase1-web`)

- `src/shared/api/afterHoursDigestApi.ts`: typed digest API call and response validation.
- `src/widgets/thread-list/ui/AfterHoursDigestCard.tsx`: isolated digest card with loading/error/empty/partial states.
- `src/widgets/thread-list/ui/ThreadListWidget.tsx`: mount card above the existing queue without replacing CRM views.
- Component/API tests, `docs/api/openapi.yaml` or current frontend contract source, `tasks/todo.md`.

## Task 1: Compute deterministic digest windows

The policy method returns `AfterHoursDigestWindow(businessDate, timeZone, availableAt, windowStart, windowEnd, available)`. Select today's digest while today has a configured opening and the current local time is before closing; otherwise select the next configured opening date. `availableAt` is 08:45 local. `windowStart` is the close time of the nearest prior date with configured opening hours. Before `availableAt`, set `available=false` and `windowEnd=availableAt`; from 08:45 until opening, cap `windowEnd` at the current instant; at/after opening, cap it at that opening instant. Fail if a prior/next open interval cannot be found within the existing 14-day policy horizon.

- [x] Add fixed-clock tests for current/next digest date, 08:45 availability, previous configured opening close, weekday/weekend/holiday overrides, and time-zone conversion.
- [x] Run focused test and confirm the expected missing window behavior fails.
- [x] Add typed digest-window methods to `DealershipHoursPolicy`; preserve existing `current()` and `at()` behavior.
- [x] Rerun focused policy tests.

## Task 2: Add tenant-bounded read contract and query

- [x] Add service tests proving unauthenticated/non-operator/unauthorized-tenant requests fail, pre-08:45 responses expose no tickets, after-hours tickets appear, in-hours tickets do not, and partial packet counts are accurate.
- [x] Add a bounded JDBC repository read; endpoint integration test exercises the scoped digest response. A dedicated PostgreSQL boundary test for the new range query remains a follow-up verification gap.
- [x] Add `HandoffRepository.listCreatedBetween(tenantId, startInclusive, endExclusive)` and implement it using existing `JdbcClient`; no writes or broad fetch-all filtering.
- [x] Implement the typed digest response/service using `DealershipHoursPolicy` and injected `Clock`; validate operator and tenant before reading.
- [x] Rerun focused unit tests and authenticated digest HTTP integration test.

## Task 3: Expose and document the Service endpoint

- [x] Add controller tests for authorization, required tenant selection, response availability metadata, and hidden ticket details before 08:45.
- [x] Add the read-only GET endpoint under existing operator authorization and tenant checks.
- [x] Update frontend OpenAPI contract with request/response schemas and `403` behavior.
- [x] Rerun controller/API contract tests and OpenAPI validation (50 paths).

## Task 4: Add the Ford-web CRM digest card

- [x] Add API tests for URL construction, malformed response rejection, service error propagation, and same-origin credentials.
- [x] Implement the typed API client and digest card with loading, retryable error, no-items, item, and partial-packet states.
- [x] Add component tests for date/08:45 label, counts, partial marker, conversation link, retry, and tenant changes.
- [x] Mount the card in the inbox and verify the existing inbox remains usable when the digest call fails.
- [x] Run Ford-web tests, lint, build, API contract check, and Pages function compile.

## Task 5: Combined review and release gates

- [x] Run fresh Service unit/build/integration compilation and the authenticated digest HTTP integration test.
- [x] Run Ford-web full tests/lint/build/contracts. Review final `git diff --check` before branch publication.
- [x] Verify tenant authorization, read-only behavior, off-hours filter, and PII minimization by implementation/tests; add a dedicated real PostgreSQL bounded-query test before release., mutate tickets, include in-hours tickets, expose packet PII, or claim notification delivery.
- [x] Update both task ledgers with command output and open operations gates.
- [x] Keep production rollout blocked until dealership calendar/holiday inputs and operator dry-run are approved; then release only via protected coordinated Actions and verify actual 08:45 behavior.

## Dependencies and rollback

Tasks 1–3 are Service-side and precede Task 4's Web integration. Rollback is a code release reverting the read-only endpoint and card; existing handoff tickets and customer messaging remain untouched. No migration is required.

## Local verification results (2026-10-18)

- Ford Service: full `test bootJar compileIntegrationTestJava` passed (808 unit tests, 0 failures/errors/skips); focused authenticated digest controller HTTP test and H2 JDBC range test passed.
- Ford-web: full suite 35 files / 142 tests passed; lint, production build, 50-path API check, and Pages function build passed.
- Remaining verification: dedicated PostgreSQL test of the bounded JDBC query; approved calendar and operator dry-run; protected release and production 08:45 evidence.
