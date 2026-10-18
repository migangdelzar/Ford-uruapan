# MÍA Shadow Evaluation and Rollout Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let authorized BDC operators review unsent MÍA drafts, score the approved behavior offline, and switch safely back to human handling.

**Architecture:** Extend the existing inbox as a narrow shadow-review surface backed by the current Service API. Shadow drafts are persisted separately from customer messages and are never delivered. Add deterministic safety/latency/handoff evaluation and a server-side kill switch.

**Tech Stack:** React 19, TypeScript 7, Vitest 5, Playwright, Java 25, Spring Boot 4.1.1, Spring Security, PostgreSQL/Liquibase.

## Global Constraints

- Shadow drafts are never sent to customers; require operator authorization and audit every review action.
- Keep customer prompts, drafts, PII, provider payloads, and credentials out of logs/tracing.
- A working kill switch routes new conversations to humans and prevents bot sends without deleting transcript or journey data.
- Live after-hours rollout is blocked until external evidence and all safety/latency/handoff/privacy/dry-run gates are confirmed.
- External CRM, BDC digest, privacy URL, real-chat corpus, advisor examples, Meta timing data, and holiday calendar are not fabricated or added in this phase.

---

### Task 1: Persist shadow drafts without sending them

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java`
- Create: shadow draft domain/application/repository/API types under `com/ford/service/conversations`
- Create: additive Liquibase changeSet under `src/main/resources/db/changelog/changes`
- Test: application service and controller tests

- [x] Add tests proving shadow mode persists a bounded draft, writes no channel outbox record, cannot call delivery, and respects tenant/operator authorization.
- [x] Implement a separately persisted, expiring shadow draft linked to conversation/turn IDs; keep content out of logs and observations.
- [x] Run focused tests and Service unit/integration suites. Fresh results: unit 799/799; integration 76, zero failures/errors, three env-gated snapshot cases skipped.

### Task 2: Add the operator review surface

**Files:**
- Modify: `src/pages/inbox/ui/InboxPage.tsx`
- Modify/create: inbox review widget and API client under `src/widgets/conversation/` and `src/shared/api/`
- Test: Vitest page/widget tests and Playwright inbox shadow flow

- [x] Add authenticated tenant-scoped listing and accept/reject annotation to the existing Service API; verify that review is scoped by tenant, conversation, and draft ID and cannot trigger delivery.
- [x] Add the API operations and schemas to the canonical OpenAPI contract. The checked-out Ford Service repository has no React frontend, so a frontend widget is not part of this backend change; the existing operator client can consume this API.
- [x] Run the focused controller/service/repository/OpenAPI tests as part of the 799-test unit suite.

### Task 3: Add evaluation suite and launch report

**Files:**
- Create: deterministic JSONL cases under `ford-ai-service/src/test/resources/ai-evaluation/mia-phase-one/`
- Modify: `AiRegressionEvaluationTest.java` and evaluation reporting types
- Create: `docs/operations/mia-phase-one-rollout.md`

- [x] Cover 234 synthetic cases in nine categories including prices, discounts, financing, availability, promises, prompt injection, tool/provider failures, interruptions, and handoff.
- [x] Assert zero never-promise violations/detector misses and disclosure completeness over 80% on the synthetic golden subset. The local p95 is policy-evaluation runtime, not provider-to-customer response latency; retain end-to-end latency as a launch gate.
- [x] Document evidence checklist, operator dry-run, kill switch, after-hours rollout, and rollback in `docs/operations/shadow-review-rollout.md`; external evidence remains pending.
- [x] Run the evaluation suite; machine-generated output is under `ford-ai-service/build/reports/ai-evaluation/shadow-review/`.

## Completion Gate

- [x] Shadow drafts are never customer-visible and cannot enter the send outbox.
- [x] Kill switch is tested to stop new bot sends while preserving data.
- [x] Synthetic safety and disclosure thresholds pass locally; end-to-end latency has not been measured.
- [x] Live launch is explicitly marked blocked pending external evidence and operator sign-off; this plan does not imply deployment.
