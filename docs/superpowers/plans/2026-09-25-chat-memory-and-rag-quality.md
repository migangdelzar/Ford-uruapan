# Chat Memory and Ford Grounding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make short follow-ups, topic changes, and promotion/catalog questions use the right conversation history and current authorized Ford evidence.

**Architecture:** Keep the exact transcript as the only conversational history source and the existing projection as bounded support. Enforce full memory scope in SQL. Reuse authorized Spring AI/MCP tools and fixture-driven evaluation; change prompt or response checks only when a scenario demonstrates a concrete gap.

**Tech Stack:** Java 25, Spring AI 2.0.1, Spring Modulith 2.1.1, PostgreSQL 16, Spring AI MCP client, JUnit 5, the existing Node OpenRouter evaluator.

## Global Constraints

- Current user message takes priority; memory is untrusted historical data, not instructions.
- Tenant, conversation, channel, provider-account, and participant boundaries apply to history and retrieval.
- Current prices, inventory, vehicle facts, and promotions require authorized current source results.
- Keep tools read-only; no new agent framework, semantic-memory store, or handoff activation.
- Preserve the current Spanish (Mexico) service voice; avoid deterministic phrase routing for ordinary chat.

---

### Task 1: Scope recent transcript reads to the full conversation identity

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationMemoryRepository.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/JdbcConversationMemoryRepositoryTest.java`
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/ConversationMemoryPostgresIntegrationTest.java`

**Interfaces:**
- Consumes: `ConversationMemoryKey(tenantId, channel, providerAccountId, participantId, conversationId)`.
- Produces: `load(MemoryQuery)` returns no transcript messages when any identity component does not match the owning conversation row.

- [ ] **Step 1: Add a failing mismatch test.** Seed one conversation and messages; query with its conversation ID but a different tenant, channel, provider account, or participant. Assert both summary/facts and recent messages are empty for each mismatch.
- [ ] **Step 2: Run focused tests.** Run `./gradlew test --tests com.ford.service.conversations.JdbcConversationMemoryRepositoryTest`. Expected: the current recent-message query still returns rows because it filters only on conversation ID.
- [ ] **Step 3: Add a scoped owner check to the transcript query.** Use an `exists` predicate against `conversation` matching `id`, `tenant_id`, `channel`, `provider_account_id`, and `participant_id`; bind values from `query.key()` with the existing `JdbcClient` style.
- [ ] **Step 4: Run unit and PostgreSQL tests.** Run `./gradlew test --tests com.ford.service.conversations.JdbcConversationMemoryRepositoryTest` and `./gradlew integrationTest --tests com.ford.service.conversations.ConversationMemoryPostgresIntegrationTest`. Expected: matching identity loads history; every mismatched identity returns none.
- [ ] **Step 5: Commit scoped reads.** Commit only the repository and tests with `fix(memory): scope transcript reads to conversation identity`.

### Task 2: Verify authorized MCP/RAG tool inventory and dispatch

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/ToolAuthorizationPolicy.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/DecisionPolicy.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiConfigurationSliceTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/DecisionPolicyTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`

**Interfaces:**
- Reuse: `ToolAuthorizationPolicy.readOnlyTools(AiExecutionContext, Set<String>)` and existing `SyncMcpToolCallbackProvider` wiring.
- Produce: promotion and vehicle requests expose only the expected read-only tool family; missing source results remain explicitly unavailable to the answer generator.

- [ ] **Step 1: Add failing tool-set tests.** Assert offers expose `search_offers`, `list_promotions`, and catalog-status tools only; vehicle requests expose the configured vehicle tools; unavailable MCP does not expose fictional tools and leaves volatile information fail-closed.
- [ ] **Step 2: Run focused tests.** Run `./gradlew test --tests com.ford.service.ai.DecisionPolicyTest --tests com.ford.service.ai.SpringAiConfigurationSliceTest --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest`. Expected: failures identify any drift between configured MCP names, decision family, and actual callbacks.
- [ ] **Step 3: Correct only demonstrated wiring gaps.** Keep the configured read-only allow-list authoritative; normalize tool names once; do not add write callbacks or duplicate local equivalents when MCP is the required volatile source.
- [ ] **Step 4: Verify tool dispatch end-to-end with deterministic callbacks.** Assert a promotion question calls the promotion callback and the final assistant request receives the fixture result; assert source failure produces no claimed price, discount, or validity date.
- [ ] **Step 5: Commit verified wiring changes.** Commit only changed wiring/tests with `fix(ai): align ford tool authorization with request family`.

### Task 3: Expand multi-turn continuity and grounding evaluation

**Files:**
- Modify: `ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/AssistantResponseQualityPolicyTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/ConversationAssistantAdapterTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/evaluation/AiRegressionEvaluationTest.java`
- Modify: `scripts/ai/openrouter-chat-eval.mjs`
- Modify: `scripts/ai/fixtures/human-friendly-conversations.json`
- Modify: `scripts/ai/openrouter-chat-eval.test.mjs`

**Interfaces:**
- Evaluator input/output stays model-agnostic; live evaluator remains opt-in via `mise run test:ai-evaluation --live`.
- Produces: deterministic fixture scenarios for a follow-up, topic change, current promotions, unavailable source, and materially repeated assistant response.

- [ ] **Step 1: Add failing fixture scenarios and assertions.** Extend `human-friendly-conversations.json` with “¿y cuánto?” after a vehicle mention, an explicit topic change, promotion lookup with valid fixture evidence, and a repeated assistant turn. Update the existing `source-failure` scenario so the mocked model answer must not repeat a catalog value when `get_vehicle` returns `source_unavailable`. Extend `openrouter-chat-eval.test.mjs` with mocked-fetch assertions for these scenarios. Assert correct tool use, no unsupported volatile claim, no internal tool text, and a response that addresses the latest turn.
- [ ] **Step 2: Run deterministic evaluation only.** Run `mise run test:ai-evaluation`. Expected: new scenarios fail before prompt/quality changes; no network request or production key is needed.
- [ ] **Step 3: Make the smallest prompt/quality correction.** Keep the current system-prompt priority order; change a prompt sentence or quality rule only when it maps to a named failing case. Do not add phrase-based routing, a second repair loop, or broad prompt rewrites without evaluator evidence.
- [ ] **Step 4: Re-run deterministic unit and evaluation tests.** Run `./gradlew test --tests com.ford.service.ai.AssistantResponseQualityPolicyTest --tests com.ford.service.ai.ConversationAssistantAdapterTest --tests com.ford.service.ai.evaluation.AiRegressionEvaluationTest` and `mise run test:ai-evaluation`. Expected: all fixtures pass and the assistant still emits only customer-facing text.
- [ ] **Step 5: Run live model comparison only when separately authorized and configured.** Run `mise run test:ai-evaluation --live --help` to inspect supported arguments, then run the evaluator with the configured model set only in a controlled environment. Never print the OpenRouter key or send real customer data.
- [ ] **Step 6: Commit evaluator-backed behavior changes.** Commit prompt, tests, and fixtures together with `fix(ai): improve evaluated chat continuity and grounding`.

## Dependencies and verification

- Task 1 is independent; Task 2 precedes Task 3 so evaluator expectations reflect the actual tool surface.
- Final gate: from `ford-ai-service`, run `./gradlew test --tests 'com.ford.service.ai.*' --tests 'com.ford.service.conversations.*'`; run the fixture evaluator with `mise run test:ai-evaluation`.
- Spring AI tool calling loops return tool results to the model before its final answer; test that full path, not only startup handshake: [Spring AI tool calling reference](https://docs.spring.io/spring-ai/reference/api/tools.html).
