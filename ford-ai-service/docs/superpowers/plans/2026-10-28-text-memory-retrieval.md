# Text Conversation Memory Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Each task ends with its own test gate. Do not stage or commit in this dirty worktree unless the user explicitly authorizes it.

**Goal:** Replace last-N-only prompt selection with text-memory context built from protected facts, recent turns, relevant older turns, and a bounded token estimate; selectively expand only memory-retrieval queries using known conversation facts.

**Architecture:** Keep the durable transcript as source of truth. A pure selector will fit protected memory, recency, and recalled messages into a configurable prompt budget. `ConversationMemoryService` will use existing scoped semantic recall, adding at most one deterministic alternate query only for context-dependent or insufficient-result cases. Summary rebasing will use a bounded slice of canonical transcript messages with no prior generated summary as input; existing version checks remain the concurrency guard.

**Tech Stack:** Java 17, Spring Boot, Spring Modulith events, JUnit 5, AssertJ, Mockito, JDBC, disposable PostgreSQL Testcontainers.

## Global Constraints

- Text messages only. Existing image/audio interpretation and retention behavior remains unchanged.
- No query expansion for catalog search, no semantic caching, and no alternate conversation graph or workflow.
- No unconditional model-generated multi-query expansion. Memory retrieval uses the original message by default; optional expansion is deterministic and derived only from already-known conversation facts.
- No production/`emme-2` writes, transcript import, or live 100-turn replay during implementation.
- Keep complete transcript data as source of truth. Prompt selection must not delete transcript history.
- Test hierarchy: full unit tests must pass before any integration test. Integration tests may use disposable Testcontainers only.
- No staging, commits, resets, or changes to unrelated dirty files.

---

## File map

- `src/main/java/com/ford/service/conversations/application/ConversationMemoryPromptSelector.java` — new pure context-selection and budget component.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryQueryExpander.java` — new deterministic, memory-only alternate-query builder.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryRecallPolicy.java` — gate recall to historical/context-dependent messages and insufficient base results.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java` — orchestrate base recall, optional expansion, deduplication, selection, and transcript-backed projection.
- `src/main/java/com/ford/service/conversations/application/MemoryProperties.java` — add validated prompt-token budget, recent-turn count, and bounded rebase-message count.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryPort.java` — add bounded, identity-scoped transcript read for rebasing.
- `src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationMemoryRepository.java` — implement the new transcript query with tenant/channel/provider/participant/conversation scope.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryProjector.java` — accept canonical transcript slice and rebase without previous generated summary.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryCompactionPort.java` — add a backward-compatible default rebase method that accepts current structured facts.
- `src/main/java/com/ford/service/conversations/adapter/out/ai/ProviderRoutedConversationMemoryCompactor.java` — override rebase prompt to consume transcript and facts without prior summary.
- `src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java` — format selected context, keep exact current message separate, preserve shared facts and assistant-reply history.
- `src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java` — wire the selector using the existing `MemoryProperties` bean.
- `src/test/java/com/ford/service/conversations/ConversationMemoryPromptSelectorTest.java` — new pure selection and budget tests.
- `src/test/java/com/ford/service/conversations/ConversationMemorySelectionEvaluationTest.java` — deterministic text-only 31-turn selection evaluation.
- `src/test/java/com/ford/service/conversations/ConversationMemoryQueryExpanderTest.java` — new deterministic expansion tests.
- `src/test/java/com/ford/service/conversations/ConversationMemoryServiceTest.java` — recall gating, expansion, deduplication, and fail-open tests.
- `src/test/java/com/ford/service/conversations/ConversationMemoryProjectorTest.java` — rebase input and no-summary-of-summary tests.
- `src/test/java/com/ford/service/conversations/ProviderRoutedConversationMemoryCompactorTest.java` — fact-aware rebase prompt and source-of-truth tests.
- `src/test/java/com/ford/service/conversations/ConversationAssistantAdapterTest.java` — exact prompt ordering/current-message and compatibility tests.
- `src/integrationTest/java/com/ford/service/conversations/ConversationMemoryPostgresIntegrationTest.java` — scoped transcript-slice and stale-projection integration coverage.

---

### Task 1: Select memory context by priority and token budget

**Files:**
- Create `src/main/java/com/ford/service/conversations/application/ConversationMemoryPromptSelector.java`.
- Modify `src/main/java/com/ford/service/conversations/application/MemoryProperties.java`.
- Modify `src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java`.
- Modify `src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java`.
- Create `src/test/java/com/ford/service/conversations/ConversationMemoryPromptSelectorTest.java`.
- Create `src/test/java/com/ford/service/conversations/ConversationMemorySelectionEvaluationTest.java`.
- Modify `src/test/java/com/ford/service/conversations/ConversationAssistantAdapterTest.java`.

**Interfaces:**
- `ConversationMemoryPromptSelector(MemoryProperties properties)`.
- `Selection select(ConversationMemoryContext context, String currentMessage, List<CustomerShoppingFact> sharedFacts)`.
- `Selection` carries selected summary, recent messages, durable facts, recalled turns, shared facts, and estimated-token/omission counts. The current message remains separate and is never rewritten.
- Token estimate follows the repository's existing conservative approximation: `(Unicode code point count + 1) / 2`.
- Defaults: 3,000 estimated prompt-memory tokens, four recent complete user/assistant turns, and a maximum of 100 transcript messages for rebasing. Validate all values as positive.

- [x] **Step 1: Add failing selector tests**

Test `ConversationMemoryPromptSelectorTest` for protected facts, the newest four complete turns, relevance-ranked recalls, duplicate IDs, and budget behavior. `ConversationMemorySelectionEvaluationTest` separately exercises a synthetic 31-turn buyer flow: early city/family/camping constraints, later exact Ranger XL/XLT comparison and correction, resolved social acknowledgements, and turn 31's exact message. It asserts protected facts and recent comparison survive while low-value social content is omitted under budget.

```java
var selected = selector.select(context, "¿Cuál opción era más práctica?", sharedFacts);
assertThat(selected.durableFacts()).containsExactlyElementsOf(activeFacts);
assertThat(selected.recentMessages()).containsExactlyElementsOf(lastFourTurns);
assertThat(selected.recalledTurns()).doesNotContain(lowSimilarityRecall);
assertThat(selected.estimatedTokens()).isLessThanOrEqualTo(properties.promptTokenBudget());
```

- [x] **Step 2: Run the selector test and verify expected failure**

Run from `ford-ai-service`:
`env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-en_US.UTF-8}" ./gradlew test --tests com.ford.service.conversations.ConversationMemoryPromptSelectorTest`

Expected: compilation/test fails because selector and budget property do not exist.

- [x] **Step 3: Implement minimal prioritized selector**

Implement this selection order: current active facts and shared facts; summary; newest complete turns; recalled turns by descending similarity then time. Deduplicate messages by source ID. If optional items exceed budget, omit lowest-priority/lowest-similarity items first. Do not truncate fact values, explicit numbers, or model/year/trim names. Keep the existing `ConversationMemoryContext` API; selector returns a separate immutable `Selection` record.

- [x] **Step 4: Wire the selector into the prompt adapter**

Pass `MemoryProperties` into `ConversationAssistantAdapter` through `ConversationConfiguration`. Preserve existing public constructors by delegating to a default `MemoryProperties`. Build the memory prompt from the selection; do not include the current inbound message twice. Keep shared facts and recent assistant replies behavior intact.

- [x] **Step 5: Run focused selector and adapter tests**

Run:
`env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-en_US.UTF-8}" ./gradlew test --tests com.ford.service.conversations.ConversationMemoryPromptSelectorTest --tests com.ford.service.conversations.ConversationAssistantAdapterTest`

Expected: `BUILD SUCCESSFUL`; budget, priority, deduplication, chronological ordering, and constructor compatibility covered.

### Task 2: Add conditional deterministic expansion for memory recall

**Files:**
- Create `src/main/java/com/ford/service/conversations/application/ConversationMemoryQueryExpander.java`.
- Modify `src/main/java/com/ford/service/conversations/application/ConversationMemoryRecallPolicy.java`.
- Modify `src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java`.
- Create `src/test/java/com/ford/service/conversations/ConversationMemoryQueryExpanderTest.java`.
- Modify `src/test/java/com/ford/service/conversations/ConversationMemoryServiceTest.java`.

**Interfaces:**
- `Optional<String> expand(String originalQuery, List<MemoryFact> activeFacts, List<ConversationMessage> recentMessages)` returns at most one alternate query; it returns empty for clear queries or when there are no known terms to add.
- `ConversationMemoryService.load` searches the original query first. Only when the recall policy says context-dependent or base results are insufficient may it search the one alternate query.
- Merge by source message ID; sort by similarity descending, then occurrence time descending; enforce the existing maximum recall limit of three.

- [x] **Step 1: Add failing expander and service tests**

Cover a clear query (no expansion), “la otra versión” with known Ranger XL/XLT terms in recent text (one alternate query), no-known-facts (no expansion), base recall sufficient (no second call), empty base recall with protected facts (one expanded call), duplicate IDs, and fail-open recall errors.

```java
assertThat(expander.expand("la otra versión", facts, recent))
        .contains("la otra versión Ranger XL Ranger XLT");
assertThat(expander.expand("¿Qué promociones tiene Ranger?", facts, recent)).isEmpty();
```

- [x] **Step 2: Run focused tests and verify expected failure**

Run:
`env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-en_US.UTF-8}" ./gradlew test --tests com.ford.service.conversations.ConversationMemoryQueryExpanderTest --tests com.ford.service.conversations.ConversationMemoryServiceTest`

Expected: new type/method tests fail to compile or fail the new behavior assertions.

- [x] **Step 3: Implement explicit context-dependent gating**

Normalize Spanish text for diacritics/case in `ConversationMemoryRecallPolicy`; retain existing historical-recall phrases and add narrowly scoped anaphora such as “la otra”, “esa opción”, “el mismo”, and “those options”. Do not expand an ordinary clear query.

- [x] **Step 4: Implement one deterministic alternate memory query**

Use only explicit active facts and exact terms already in recent text. Preserve the original query unchanged; append known terms rather than replacing it. Do not call an LLM, alter a catalog query, broaden model/year/budget filters, or expand more than once. Merge/deduplicate results and cap at three.

- [x] **Step 5: Run focused recall tests**

Run the Task 2 command above.

Expected: `BUILD SUCCESSFUL`; original-query-first, selective trigger, one-expansion cap, dedupe, and fail-open behavior pass.

### Task 3: Rebase summary from bounded canonical transcript

**Files:**
- Modify `src/main/java/com/ford/service/conversations/application/ConversationMemoryPort.java`.
- Modify `src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationMemoryRepository.java`.
- Modify `src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java`.
- Modify `src/main/java/com/ford/service/conversations/application/ConversationMemoryProjector.java`.
- Modify `src/main/java/com/ford/service/conversations/application/ConversationMemoryCompactionPort.java`.
- Modify `src/main/java/com/ford/service/conversations/adapter/out/ai/ProviderRoutedConversationMemoryCompactor.java`.
- Modify `src/main/java/com/ford/service/conversations/application/MemoryProperties.java`.
- Modify `src/test/java/com/ford/service/conversations/ConversationMemoryProjectorTest.java` and `ConversationMemoryServiceTest.java`.
- Modify `src/test/java/com/ford/service/conversations/ProviderRoutedConversationMemoryCompactorTest.java`.
- Modify `src/integrationTest/java/com/ford/service/conversations/ConversationMemoryPostgresIntegrationTest.java`.

**Interfaces:**
- Add `List<ConversationMessage> loadTranscriptForRebase(ConversationMemoryKey key, Instant contextStartedAt, int keepRecentCount, int maxRebaseMessages)` to `ConversationMemoryPort`.
- The JDBC adapter returns at most `maxRebaseMessages` older transcript messages, excluding the `keepRecentCount` newest messages; scope every query by full conversation identity and context start time.
- Add a backward-compatible default `rebase(UUID tenantId, UUID conversationId, List<ConversationMessage> sourceMessages, List<MemoryFact> currentFacts, int maxChars)` to `ConversationMemoryCompactionPort`; its default delegates to the existing functional `compact` method with an empty previous summary. `ProviderRoutedConversationMemoryCompactor` overrides it and includes current sourced facts in a distinct data-only prompt section.
- Add a projector overload that accepts the canonical older slice. Rebase by calling `rebase` with source messages and current structured facts, never the prior generated summary; retain current summary on compaction failure.

- [x] **Step 1: Add failing tests for rebasing behavior**

Assert that a rebase supplies bounded canonical transcript messages to the compactor, passes an empty previous-summary string, retains active structured facts, leaves the durable transcript untouched, and retains the last valid summary when compaction fails. In PostgreSQL integration, verify identity isolation and that the newest hot-window messages are excluded.

```java
assertThat(compactorPreviousSummary.get()).isEmpty();
assertThat(compactorInput.get()).containsExactlyElementsOf(canonicalOlderSlice);
assertThat(context.recentMessages()).containsExactlyElementsOf(originalTranscript);
```

- [x] **Step 2: Run projector tests and verify expected failure**

Run:
`env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-en_US.UTF-8}" ./gradlew test --tests com.ford.service.conversations.ConversationMemoryProjectorTest --tests com.ford.service.conversations.ConversationMemoryServiceTest`

Expected: new canonical-slice/rebase tests fail before implementation.

- [x] **Step 3: Add the scoped transcript query**

Implement a deterministic `received_at, id` ordering and bounded query in `JdbcConversationMemoryRepository`. Validate `keepRecentCount >= 0` and `maxRebaseMessages >= 1`. Do not delete or rewrite transcript rows.

- [x] **Step 4: Wire transcript-backed rebase and preserve optimistic versions**

Have `ConversationMemoryService.project` fetch the bounded older transcript slice and pass it to the projector. Rebuild summary from that slice and current facts, never from the prior generated summary. Keep the existing abstract `compact` method so lambda/test adapters remain source-compatible; only the production provider compactor overrides `rebase`. Keep `MemoryProjectionUpdate` and expected-version update semantics. On read or compaction failure, retain the last valid summary and do not fail the customer turn.

- [x] **Step 5: Run the unit gate**

Run:
`env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-en_US.UTF-8}" ./gradlew test`

Expected: full unit suite passes before any integration test.

- [x] **Step 6: Run the focused disposable-PostgreSQL integration gate**

Run from `ford-ai-service`:
`env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-en_US.UTF-8}" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew integrationTest --tests com.ford.service.conversations.ConversationMemoryPostgresIntegrationTest`

Expected: `BUILD SUCCESSFUL`; only the local Testcontainers PostgreSQL fixture is used.

### Task 4: Verify deterministic text-memory acceptance evaluation

**Files:**
- Reuse `src/test/java/com/ford/service/conversations/ConversationMemorySelectionEvaluationTest.java` created in Task 1; extend only for an uncovered integrated behavior.
- Modify `src/test/java/com/ford/service/conversations/ConversationMemoryServiceTest.java` only for missing integration assertions.
- Modify `docs/superpowers/specs/2026-10-28-text-memory-retrieval-design.md` only if implementation details change the approved behavior.

**Interfaces:**
- Evaluation is deterministic and offline. It uses a synthetic 31-turn text history shaped like the existing buyer flow; it does not invoke Qwen, replay the webhook, access production/`emme-2`, or import a catalog.

- [x] **Step 1: Run the 31-turn fixture through the final wired path**

The fixture includes early use constraints (city/family/camping), later exact Ranger XL/XLT comparison and corrections, resolved social acknowledgements, and turn 31’s text: “La caja de la Ranger XLT me atrae más, pero la XL quizá sea más práctica en ciudad.” Assert selected context retains the city/family/camping needs, exact XL/XLT identity, latest correction, and recent comparison context, while omitting resolved social acknowledgements when budget requires. Record the RED proof in Task 1; here verify integrated service, selector, recall, and projection behavior.

- [x] **Step 2: Implement deterministic fallback and privacy-safe diagnostics**

If semantic recall, expansion, or projection is unavailable, keep protected facts plus the recent window. Emit counts, estimated tokens, expansion-used flag, and omission counts only; never log raw customer text or retrieved message content.

- [x] **Step 3: Run final verification in order**

Run focused memory unit tests, then full `./gradlew test`, then only `ConversationMemoryPostgresIntegrationTest` under the clean disposable Testcontainers environment above. Do not run the full integration suite or fresh all-live 100-turn acceptance as part of this plan.

Expected: all unit tests pass; focused integration passes; evaluation assertions show retained protected facts, omitted low-value turns, budget compliance, expansion count, and no catalog-query changes.

---

## Self-review

- Spec coverage: priority selection and token budget (Task 1); conditional deterministic expansion and unchanged catalog search (Task 2); canonical transcript rebasing and version safety (Task 3); failures, provenance, privacy, and deterministic evaluation (Task 4).
- Scope: one text-memory subsystem. Media, catalog search, graph, webhook, and deployment are excluded.
- No live LLM calls are required for unit/evaluation tests. The projector’s existing provider compactor can be stubbed deterministically; disposable PostgreSQL is limited to its focused adapter integration.
- Worktree is broadly dirty. Plan contains no staging, commit, reset, catalog import, production access, or all-live replay step.
