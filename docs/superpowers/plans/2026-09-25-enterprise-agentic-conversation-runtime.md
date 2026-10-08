# Enterprise Agentic Conversation Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an ordered, durable, model-led Ford conversation runtime using Spring Modulith, Spring AI, and LangGraph4j with bounded retries, tenant-safe memory retention, and recoverable outbound delivery.

**Architecture:** Spring Modulith JDBC publications remain the durable dispatch mechanism; an identifier-only receipt event enters a Spring-owned turn boundary that leases one conversation at a time and invokes a stable per-conversation LangGraph4j thread. Spring AI performs approved model and MCP/RAG calls; PostgreSQL remains canonical for transcripts, working memory, checkpoints, and outbox, while Redis is only a fail-open 24-hour cache.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring AI 2.0.1, Spring Modulith 2.1.1, LangGraph4j already present on `feat/chat-reliability-inline`, PostgreSQL, Liquibase, Redis, Gradle, JUnit 5, Testcontainers, Micrometer.

## Global Constraints

- Design target is 100 total users with occasional bursts, one active app replica initially, and normal-load p95 response time at or below 30 seconds.
- Keep Spring Modulith JDBC publication registry as the only turn queue; do not add Kafka, RabbitMQ, or a second queue.
- `InboundMessageAccepted` publications contain identifiers only; canonical message text remains in PostgreSQL and is loaded by receipt ID.
- Enforce strict serial processing per conversation, bounded concurrency across conversations, and PostgreSQL-backed expiring leases; never hold a SQL transaction open during model/MCP calls.
- Use one stable tenant-scoped graph thread per conversation; checkpoints contain compact workflow state only and expire with 90-day working memory.
- Per processing window, make at most two model calls total: primary then one explicitly approved fallback. If both fail transiently, persist retry state and resubmit in bounded windows until the 15-minute total queue-age cap.
- Read-only MCP/RAG calls get at most two attempts total; graph execution gets at most three tool-call rounds per turn. Do not retry permanent errors or the whole graph turn.
- Human handoff stays disabled. Lead intent may be recorded but must not redirect the current bot flow.
- Retain transcripts for one year; expire working-memory projection and graph checkpoint after 90 days inactive; Redis hot-memory cache TTL remains 24 hours. Expired working memory is never rebuilt from retained transcripts.
- Use additive migrations only. Do not reset production databases, change tenant IDs, expose secrets, or deploy without a separate explicit request.
- Preserve unrelated dirty worktree files. Execute on an isolated feature worktree; do not overwrite the user’s current `main` edits.
- Write failing tests before implementation; commit each independently verified task in a focused conventional commit.

---

## Baseline and Branch Reconciliation

The current `main` worktree is at `6697033` and has unrelated user changes in `DecisionPolicy.java`, `ApiExceptionHandler.java`, `ApiExceptionHandlerTest.java`, `openrouter-chat-eval.test.mjs`, `.tgrep/`, `deployment/config/oci.env`, and `ford-ai-service/.mise/state/`. Preserve all of them.

The existing `.worktrees/chat-reliability-inline` branch includes LangGraph4j 1.9.1, a PostgreSQL saver, graph classes, and lead/handoff features. Its older plan assumes one graph thread per receipt and enabled WARM/HOT handoff; those assumptions conflict with this approved design. Adapt existing source rather than blindly replaying that plan. Before implementation, create a fresh isolated worktree from the selected implementation branch and bring in this approved design commit; do not implement directly on dirty `main`.

Observed gaps on `main` that tasks must verify and close: inbound event includes participant and text; workflow is not graph-backed; memory has a Redis 24-hour TTL but no demonstrated 90-day inactivity cutoff; workflow can invoke human handoff; model client retry settings may multiply framework retries. Reconfirm each against the chosen feature-branch HEAD before changing it.

## File Map

| Area | Existing files to inspect/adapt | New or likely changed files |
|---|---|---|
| Durable intake | `channels/api/InboundMessageAccepted.java`, `channels/application/ChannelMessageService.java`, `conversations/application/ConversationWorkflow.java`, inbound JDBC adapters | identifier-only event contract tests; `ConversationTurnApplicationService` port/boundary; lease domain, repository adapter, and additive Liquibase changes |
| Event recovery | `application.yml`, Modulith JDBC registry configuration, `ConversationWorkflowTest` | publication recovery configuration/operations checks and listener idempotency tests |
| Graph | existing `conversations/application/graph/**`, `ConversationAssistantAdapter.java`, `ConversationConfiguration.java` | stable thread-key policy, checkpoint TTL/cleanup, Spring-owned graph port and graph policy tests |
| Model/tool resilience | `ai/application/port/out/ProviderRoutingChatCompletionPort.java`, `SpringAiConfiguration.java`, MCP adapters and existing AI tests | typed failure/backoff policy only where absent; config and retry-budget tests |
| Memory | `ConversationMemoryService.java`, `ConversationMemoryProjector.java`, `JdbcConversationMemoryRepository.java`, `RedisCachedConversationMemoryPort.java`, `application.yml` | inactivity-expiry policy/migration and retention integration tests; token-budgeted prompt-memory tests |
| Outbound | `ConversationRepository.java`, `JdbcConversationRepository.java`, existing outbox adapters/listeners | receipt-key idempotency and delivery-reconciliation tests where current implementation is insufficient |
| Verification/ops | existing test/eval suites, `docs/operations.md`, `mise.toml` tasks | focused concurrency, outage, retention, metrics, and load-gate coverage; operator recovery documentation |

Keep production changes narrow: do not add new architecture abstractions where current Spring APIs or existing ports already provide the required boundary.

## Execution Tasks

### Task 1: Establish a clean execution baseline and verify the graph branch

**Files:**
- Read: `docs/superpowers/specs/2026-09-25-enterprise-agentic-conversation-runtime-design.md`
- Read: `.worktrees/chat-reliability-inline/ford-ai-service/build.gradle.kts`, `gradle/libs.versions.toml`, and `conversations/application/graph/**`
- Read: feature-branch `ConversationWorkflow`, `InboundMessageAccepted`, memory adapter, and corresponding tests

**Interfaces:**
- Produces: a new isolated execution worktree based on the chosen feature branch, carrying the approved design document and none of the dirty `main` working files.
- Preserves: existing LangGraph4j API/version and Spring AI BOM unless a compatibility test proves a change necessary.

- [ ] Confirm feature worktree has no tracked modifications; leave `.tgrep/` and all main dirty paths untouched.
- [ ] Create a fresh isolated worktree for execution; do not switch the dirty main worktree or reuse it as a write target.
- [ ] Compare branch’s graph dependency and saver APIs to resolved dependency versions with `./gradlew dependencyInsight --dependency langgraph4j --configuration runtimeClasspath` from `ford-ai-service`.
- [ ] Run baseline `mise run test:unit` from `ford-ai-service`; record exact command/result in the plan execution notes.
- [ ] Commit only any required spec-branch synchronization or baseline documentation; do not copy application code between branches without tests.

### Task 2: Make accepted inbound events identifier-only

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/api/InboundMessageAccepted.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/application/ChannelMessageService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java`
- Modify: inbound event and workflow unit tests
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/e2e/MetaToOutboxVerticalFlowTest.java`

**Interfaces:**
- Event contract: `InboundMessageAccepted(UUID receiptId, UUID tenantId, UUID conversationId, Channel channel, String providerMessageId, Instant acceptedAt)`; no text, participant, credentials, or provider payload.
- Receipt lookup: use the existing tenant-scoped persisted inbound message adapter keyed by `receiptId`; add a narrow port only if no suitable existing adapter exists.

- [ ] Add a test asserting event publication contains only the six allowed properties and listener reloads exact text by receipt ID, even if a newer inbound message exists.
- [ ] Run the focused channel/workflow tests and verify they fail because text currently travels inside the event.
- [ ] Change publisher and listener to use the ID-only event and receipt lookup; preserve transactionality between message persistence and event publication.
- [ ] Add a serialized-publication compatibility test for the new event shape and document safe handling of already-pending old-shape publications; do not delete/replay production publications blindly.
- [ ] Run `mise run test:unit` and focused `mise run test:integration`; commit `refactor(channels): publish identifier-only inbound events`.

### Task 3: Add ordered leased turn execution and idempotent completion

**Files:**
- Create or adapt: `conversations/application/ConversationTurnApplicationService.java`
- Create or adapt: conversation lease domain/port and PostgreSQL adapter
- Modify: `ConversationWorkflow.java`, `ConversationRepository.java`, `JdbcConversationRepository.java`
- Add: additive Liquibase migration and unit/PostgreSQL integration tests

**Interfaces:**
- Turn boundary consumes `(tenantId, conversationId, receiptId, providerMessageId)` and returns `ACCEPTED | ALREADY_COMPLETED | BUSY | EXPIRED`.
- Lease port claims/releases by conversation with owner and expiry; stale lease can be reclaimed; every query is tenant-scoped.
- Completion is idempotent by persisted inbound receipt ID and atomically writes assistant transcript plus existing outbox intent.

- [ ] Add tests for only one active lease per conversation, parallel leases for distinct conversations, stale lease reclamation, tenant isolation, and duplicate receipt completion producing one transcript/outbox request.
- [ ] Run those tests to capture expected failure before implementation.
- [ ] Add a versioned additive migration for lease ownership/expiry and idempotency state, following current Liquibase conventions; never reset or drop existing production data.
- [ ] Claim lease in a short transaction, release transaction before assistant/graph invocation, then complete and persist outbox in a separate short transaction; release lease in `finally` and rely on expiry after process death.
- [ ] Add concurrent PostgreSQL integration coverage using two service instances sharing the same test database; assert strict same-conversation ordering and bounded cross-conversation progress.
- [ ] Run focused unit/integration tests; commit `feat(conversations): serialize turns with expiring leases`.

### Task 4: Make the existing LangGraph4j flow the bounded conversation owner

**Files:**
- Adapt: `conversations/application/graph/ConversationGraphState.java`, `ConversationGraphConfiguration.java`, `ConversationGraphRunner.java`, and `ConversationTurnProcessor.java`
- Modify: `ConversationWorkflow.java`, `ConversationConfiguration.java`, `ConversationAssistantAdapter.java`
- Modify/create: graph tests and PostgreSQL saver integration tests

**Interfaces:**
- Spring boundary: `ConversationTurnApplicationService` calls an injected `ConversationAgent` port; Spring listeners/controllers must not depend on LangGraph4j classes.
- Stable graph thread key: deterministic tenant+conversation scope, reused across receipts; receipt ID identifies the current turn.
- State excludes raw transcript, prompt, response draft, credentials, and raw MCP/RAG payload; reload those from canonical stores at node boundaries.

- [ ] Add graph tests for repeated turns on one stable conversation thread, no cross-tenant/thread reuse, maximum three tool rounds, and terminal success/failure with no unbounded/backward routing.
- [ ] Add PostgreSQL saver reopen test proving a process restart resumes safe state on the same thread and a different conversation has an isolated thread.
- [ ] Run graph and saver tests; establish red results for missing stable-thread/round contracts.
- [ ] Adapt existing graph and saver; retain existing LangGraph4j version unless compatibility test fails; keep Spring AI as the model/MCP adapter and avoid adding another ReAct/tool loop.
- [ ] Ensure graph checkpoint writes contain only allowlisted IDs and compact safe route state; add serialized-state leak assertions.
- [ ] Run graph unit/integration tests and `mise run test:check`; commit `refactor(conversations): use stable bounded graph threads`.

### Task 5: Enforce primary-plus-approved-fallback call budget and tool retries

**Files:**
- Modify: `ai/application/port/out/ProviderRoutingChatCompletionPort.java`, `SpringAiConfiguration.java`, relevant provider properties
- Modify: Ford MCP/RAG tool adapter and existing retry configuration only where required
- Modify/create: provider-routing, configuration, and MCP retry unit tests

**Interfaces:**
- Model window policy: one primary call, then at most one call to an explicitly approved compatible fallback; maximum two model calls total per window.
- Tool policy: one initial read-only MCP/RAG call plus at most one retry for transient failures; maximum three tool rounds in a graph turn.
- Failure classification distinguishes transient network/timeout/rate-limit/service errors from permanent authentication/authorization/validation/malformed requests.

- [ ] Add counting-fake tests for exact provider/tool call budgets, retry-after handling, no retry on permanent errors, and no provider outside the allow-list receiving context.
- [ ] Run focused tests and observe failure when lower-level client retry combines with routing retry.
- [ ] Configure Spring Framework/Spring AI retry at the external-call boundary and disable or align nested client retries so effective attempts remain exact; do not retry an entire graph invocation.
- [ ] Add injected clock/delay strategy so unit tests assert bounded jitter/backoff without sleeping; clamp every scheduled wait to remaining 15-minute queue age.
- [ ] Run `mise run test:unit` and `mise run test:check`; commit `fix(ai): enforce bounded model and tool retries`.

### Task 6: Persist provider-outage resubmission state with a 15-minute cap

**Files:**
- Adapt: turn lease/dispatch schema and listener from Task 3
- Modify: Modulith event publication configuration and `ConversationWorkflow`
- Add: provider-outage recovery tests and operator recovery documentation

**Interfaces:**
- Durable retry state includes first-queued timestamp, next eligible time, processing-window number, last safe failure category, and terminal expiration state; never store secrets or raw provider response bodies.
- Retry scheduler resubmits through the existing Spring Modulith JDBC publication mechanism; no second queue is introduced.

- [ ] Add tests for persisted age across process restart/redelivery, Retry-After precedence, bounded backoff with jitter, recovery before 15 minutes, expiration at/after 15 minutes, and permanent failures not resubmitted.
- [ ] Run focused unit and PostgreSQL tests; confirm they fail on restart resetting queue age or unlimited registry redelivery.
- [ ] Implement bounded windows: each window has two provider calls maximum; on transient exhaustion, persist next eligible time and republish/continue through the Modulith mechanism; ensure backoff never schedules past the absolute expiration time.
- [ ] Configure and test stale publication detection, bounded registry redelivery, operational visibility, and manual replay semantics using Spring Modulith’s supported registry APIs for the resolved dependency version.
- [ ] Verify duplicate publication cannot reset firstQueuedAt or create multiple logical turns/outbox sends.
- [ ] Run focused tests; commit `feat(conversations): bound provider outage recovery`.

### Task 7: Apply 90-day memory/checkpoint expiry without transcript rehydration

**Files:**
- Modify: `ConversationMemoryService.java`, `ConversationMemoryProjector.java`, `JdbcConversationMemoryRepository.java`, memory cache adapter/configuration
- Modify: graph checkpoint saver lifecycle/cleanup adapter
- Add: additive Liquibase migration and memory/checkpoint retention integration tests

**Interfaces:**
- Working-memory projection and checkpoint expire after 90 days since last activity; next inbound begins an empty working-memory period.
- PostgreSQL transcript remains retained for one year but is never used to recreate expired summary/facts/recent-memory context.
- Redis remains fail-open with 24-hour TTL and is invalidated/version-fenced on projection expiry.

- [ ] Add clock-controlled tests at 89 days 23:59, exactly 90 days, and after 90 days; assert expired memory is empty while transcript still exists and is not queried for reconstruction.
- [ ] Add Redis integration tests proving a cached pre-expiry version cannot resurrect expired PostgreSQL memory.
- [ ] Run tests and record expected failure from current missing inactivity boundary.
- [ ] Add `last_activity_at`/expiry metadata through additive migration as needed; implement tenant-scoped expiry and checkpoint cleanup using bounded batches; do not synchronously delete large transcript history on inbound processing.
- [ ] Preserve `FORD_MEMORY_CACHE_TTL=PT24H`, existing version-aware cache behavior, and fail-open semantics.
- [ ] Add progressive summary/token-budget tests: selected recent turns + compacted summary + provenance-bearing durable facts fit configured token budget; no volatile offers are persisted as durable facts.
- [ ] Run unit and PostgreSQL/Redis integration tests; commit `feat(memory): enforce working memory retention lifecycle`.

### Task 8: Keep human handoff disabled and prevent automatic redirects

**Files:**
- Modify: graph route/turn workflow and handoff decision policy only as needed
- Modify: `ConversationWorkflowTest`, graph tests, and conversation behavior evaluation fixtures
- Modify: runtime default/config only if current default violates disabled policy

**Interfaces:**
- Human handoff is disabled for all lead temperatures and model suggestions under this approved runtime; no handoff ticket, transfer message, or bot pause is created by the conversational graph.
- Security/tenant guardrails remain deterministic; ordinary lead qualification does not hard-route away from the bot.

- [ ] Add tests for explicit human request, hot lead signals, model-requested handoff, and disabled flag; all must keep bot answering and must not create queue/ticket/transfer side effects under the approved current policy.
- [ ] Run existing handoff and graph tests; identify stale expectations without changing deployment secrets.
- [ ] Update graph terminal policy and prompt/eval fixtures narrowly; keep any existing kill switch but ensure default and deployed value remain off according to the approved request.
- [ ] Run relevant tests and evaluation; commit `fix(conversations): keep bot flow active while handoff is disabled`.

### Task 9: Make outbound commit and delivery reconciliation idempotent

**Files:**
- Modify: `ConversationRepository` and JDBC implementation only if receipt idempotency is absent
- Modify: existing outbox event, sender adapter, and delivery status reconciliation tests
- Modify: `MetaToOutboxVerticalFlowTest`

**Interfaces:**
- One accepted inbound receipt maps to no more than one completed assistant transcript row and one logical outbound request.
- Provider delivery identifiers/status are persisted for reconciliation; sending occurs only after transcript/outbox commit.

- [ ] Add replay tests asserting duplicate Modulith delivery cannot duplicate assistant reply or outbound send.
- [ ] Add crash-window tests for commit-before-send and provider-accepted-before-status-update; assert recovery reconciles using idempotency/provider IDs without blindly duplicating a logical turn.
- [ ] Run focused tests, then implement only gaps using existing outbox patterns and additive migrations.
- [ ] Run `mise run test:integration`; commit `fix(channels): reconcile outbound delivery idempotently`.

### Task 10: Add observability and 100-user burst/fault verification

**Files:**
- Modify: conversation, provider, MCP, memory, and outbox observations/metrics
- Modify/create: test fixtures/load gate and operations documentation

**Interfaces:**
- Metrics use bounded-cardinality tags only; never tag tenant/user/message/conversation IDs or log raw prompt/text/token/secret.
- Required measurements: publication age/depth, worker wait/lease recovery, turn p95, provider attempts/fallback/throttle/failure class, MCP latency/retry/tool rounds, Redis hit/miss/failures, memory expiry, outbox delivery state.

- [ ] Add observation tests for correlation from receipt through graph/provider/tool/memory/outbox while excluding message text and credentials.
- [ ] Add load/fault test profile for 100 simulated users with bursts, provider rate limiting, MCP outage, Redis outage, app restart, and database latency; record concurrency, queue age, p95, and duplicate-send checks.
- [ ] Assert normal-load p95 ≤30 seconds and bounded queue/worker behavior under configured concurrency; report if the environment cannot provide a representative load run rather than treating a smaller unit test as evidence.
- [ ] Run `mise run test:unit`, `mise run test:integration`, `mise run test:check`, and `mise run load:gate` (or exact current task equivalent verified from `mise tasks`).
- [ ] Update operations docs with alert signals, publication replay, expired turn handling, retry budgets, retention behavior, and rollback steps; commit `test(conversations): verify runtime reliability objectives`.

## Dependency Order

```text
Task 1 baseline/branch reconciliation
  → Task 2 ID-only event
  → Task 3 lease + idempotent turn boundary
  → Task 4 stable LangGraph thread
  → Task 5 call budgets
  → Task 6 durable outage retry windows

Task 3 → Task 7 memory/checkpoint expiry
Task 4 + Task 7 → Task 8 handoff-off behavior
Task 3 → Task 9 outbound reconciliation
Tasks 2–9 → Task 10 observability/load/fault verification
```

## Definition of Done

- [ ] All tasks use test-first Red → Green → Refactor and focused conventional commits.
- [ ] Same-conversation turns remain ordered across duplicate events, restarts, and lease expiry; independent conversations make bounded concurrent progress.
- [ ] Event publications contain only identifiers and all tenant-scoped canonical content is loaded from PostgreSQL.
- [ ] Stable LangGraph thread resumes safely; graph has at most three tool rounds and does not own canonical business data.
- [ ] Provider budget is two total calls per processing window (primary + one approved fallback); tool retry budget is two attempts total; queue age never exceeds 15 minutes without terminal operational status.
- [ ] Handoff remains disabled and all tested lead signals continue through the bot without transfer side effects.
- [ ] Redis TTL is 24 hours; PostgreSQL working memory/checkpoint expires after 90 inactive days; transcript retention is one year; expired memory is not rehydrated from transcript.
- [ ] Transcript/outbox completion is idempotent; no provider/MCP call occurs inside a SQL transaction.
- [ ] Unit, PostgreSQL/Redis integration, and fault/load suites pass; the p95 target is evidenced by a representative 100-user burst test.
- [ ] No production DB reset, secret mutation, merge, push, or deployment is part of this plan without separate authorization.
