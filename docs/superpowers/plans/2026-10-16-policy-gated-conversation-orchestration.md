# Policy-Gated Conversation Orchestration Implementation Plan

> **For agentic workers:** Execute this plan task-by-task with TDD and review the diff before each production boundary.

**Goal:** Make supported Ford conversation turns consistently LLM-composed while policy strictly limits tools, facts, and actions.

**Architecture:** Keep the existing LangGraph turn lifecycle, classifier, provider routing, PostgreSQL transcript, and durable outbox. Change the assistant policy so contextual `GENERAL` turns reach the model, authorize tools only when evidence and a supported family permit them, and keep memory compaction tool-free.

**Tech Stack:** Java 21, Spring Boot, Spring AI, LangGraph4j, JUnit 5, AssertJ, Gradle.

## Global Constraints

- Keep customer text, generated answers, summaries, and raw tool payloads out of logs.
- Preserve the full PostgreSQL transcript; memory compaction may summarize but may not reset or delete transcript rows.
- `ToolFamily.NONE` or a decision with no evidence grants zero tools.
- Exact current vehicle facts require MCP evidence; no invented price, specification, promotion, or availability.
- No production deployment before all local checks pass; use existing GitHub Actions blue/green release and approval gates.
- Preserve unrelated dirty files in `/Development/Ford-uruapan`; work only in `/private/tmp/ford-reply-investigation`.

---

## Plan Review Notes

- The production trace confirms the generic answer was returned by the scope guard for `GENERAL` turns before the reply model ran.
- The same trace shows compaction requests with `ToolFamily.NONE` were nevertheless given nine read-only tools because the empty-evidence path passed `null` as the allowlist. This is included as a least-privilege fix.
- Keep the current count-based memory window in this change. A token-budgeted assembler needs a supported tokenizer decision and should not be guessed as part of the reply routing fix.

## Task 1: Let contextual general conversation reach the LLM

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantScopePolicy.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/application/AssistantScopePolicyTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java`

**Behavior:** Preserve hard rejection of explicit out-of-scope classifications, deterministic out-of-scope terms, and clearly unrelated requests. When the classifier returns `GENERAL` and memory contains Ford context, treat the turn as conversationally supported and invoke the assistant. Keep greetings and explicit supported Ford intents working as before. Keep content-free scope reason logging.

- [ ] Add a failing policy test: `GENERAL` plus Ford memory returns `allowed`; a clearly unrelated request with the same memory remains rejected.
- [ ] Run `./gradlew test --tests com.ford.service.ai.application.AssistantScopePolicyTest` and confirm the new general-context assertion fails before implementation.
- [ ] Change `AssistantScopePolicy.reason` so a `GENERAL` decision with Ford context returns `allowed` after explicit out-of-scope checks; do not broaden `OUT_OF_SCOPE` decisions.
- [ ] Add a failing service test proving a contextual `GENERAL` decision calls `AssistantOrchestrator` and returns its response instead of `scopeResponse()`.
- [ ] Run `./gradlew test --tests com.ford.service.ai.SpringAiAssistantServiceTest` and confirm the test fails for the expected scope-guard short circuit.
- [ ] Update the minimal scope check if required and run both focused test classes; all existing unrelated-request and prompt-injection tests must remain green.

## Task 2: Fail closed on tool authorization, including compaction

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/ProviderRoutedConversationMemoryCompactorTest.java`

**Behavior:** A decision without evidence gets zero tool callbacks. A decision with `ToolFamily.NONE` gets zero tool callbacks. A supported family with evidence gets only the registered names from `DecisionPolicy.allowedToolNames`. The memory compactor's default decision is `NONE`, therefore compaction receives no callbacks.

- [ ] Add adapter tests proving an empty-evidence request and a `NONE` family request expose zero callbacks, even when read-only callbacks are configured.
- [ ] Run the new adapter tests and confirm the empty-evidence case fails against the current null-allowlist behavior.
- [ ] Change `authorizedTools` to return `List.of()` when decision evidence is empty or the tool family is `NONE`; otherwise apply the existing family allowlist.
- [ ] Extend the existing compactor test to capture its `AssistantRequest` and verify its default decision is `NONE`; verify at the completion adapter boundary that zero model callbacks are supplied.
- [ ] Run the focused adapter and compactor tests and existing `DecisionPolicyTest`.

## Task 3: Validate bounded grounded replies and safe diagnostics

**Files:**
- Modify: only if required in `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java`, `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`, or `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/ai/ProviderRoutedConversationMemoryCompactor.java`
- Test: relevant existing assistant quality, provider routing, and memory tests
- Docs: `docs/superpowers/specs/2026-10-16-policy-gated-conversation-orchestration-design.md`

- [ ] Add or extend tests for a supported general reply with no volatile-data requirement, successful MCP grounding, empty/failed MCP results, and withholding unsupported exact facts.
- [ ] Assert quality repair and provider fallback budgets remain bounded and diagnostics contain reason codes only.
- [ ] Assert compaction failure retains the last valid summary and does not prevent reply commit; assert the transcript repository remains unchanged by compaction.
- [ ] Implement only changes required by failing tests; preserve existing outbox and memory projection behavior.
- [ ] Update the design acceptance notes with actual behavior and known limits.

## Task 4: Local verification and release readiness

**Files:**
- Any implementation files above.
- No production host edits; no secrets/config edits.

- [ ] Run focused tests for scope policy, assistant service, tool authorization, quality, provider routing, and memory compaction.
- [ ] Run `./gradlew test` in `ford-ai-service` and record test counts.
- [ ] Run `./gradlew bootJar` in `ford-ai-service`.
- [ ] Run `git diff --check` and inspect the complete diff for accidental customer text, credentials, or unrelated changes.
- [ ] Commit implementation changes on `fix/reply-memory-diagnostics`, push, and open a PR to `main` with production-trace evidence and verification results.
- [ ] Wait for required CI checks, merge through the repository's approved process, then run the production release workflow for the merged `main` version.
- [ ] If the production environment requests reviewer approval, leave the workflow waiting and ask the user to approve it; never bypass protection.
- [ ] After deployment, verify candidate health, app health, model/MCP behavior using a synthetic read-only question, no-tools behavior for a general turn/compaction, and active blue/green slot. Promote only after checks pass; preserve the previous image for rollback.

## Acceptance Criteria

1. A contextual `GENERAL` turn reaches DeepSeek and is not replaced by the fixed scope menu.
2. Explicit unrelated and unsafe turns remain blocked.
3. No-evidence and `ToolFamily.NONE` requests, including memory compaction, expose zero tools.
4. MCP-backed exact facts remain grounded; MCP failure/empty results cannot produce unsupported exact facts.
5. Transcript retention, memory summary fallback, and durable outbound delivery remain intact.
6. Local tests and build pass before PR merge or deployment.
7. Production rollout is confirmed from actual workflow and health evidence; no claim is made before confirmation.
