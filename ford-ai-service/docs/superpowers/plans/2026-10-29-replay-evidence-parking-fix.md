# Replay Evidence and Parking Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep catalog links relevant to the final answer and give unsupported parking-ease follow-ups a safe, context-aware reply.

**Architecture:** Filter approved catalog source links against model/trim identities actually named in the final answer. Keep the existing evidence-grounding policy; specialize its deterministic clarification only for parking-ease questions, without making vehicle-fit claims.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Gradle.

## Global Constraints

- No production, customer-delivery, database, catalog, credential, or deployment changes.
- Use only approved local catalog snapshot for any local-model replay; cloud and outbound delivery remain disabled.
- Keep 100-turn acceptance paused.
- Preserve URL approval, comparison-source scoping, deduplication, and existing link limits.
- Never infer inventory, fit, parking ease, unsupported benefits, offers, prices, or promotions.

---

## File Map

- Modify `src/main/java/com/ford/service/ai/application/CatalogFactResponseRenderer.java`: decide whether catalog-result links are relevant to the generated answer before appending.
- Test `src/test/java/com/ford/service/ai/CatalogFactResponseRendererTest.java`: prove irrelevant results add no links and named model evidence still links.
- Modify `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`: select the parking-specific safe clarification when the current user message asks about parking ease.
- Test `src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`: exercise initial answer plus rejected repair and verify deterministic safe response.
- Existing `src/main/java/com/ford/service/ai/application/EvidenceGroundedResponsePolicy.java`: no change planned; it already rejects unsupported parking-ease claims.

### Task 1: Do not append unrelated catalog links

**Files:**
- Modify: `src/test/java/com/ford/service/ai/CatalogFactResponseRendererTest.java`
- Modify: `src/main/java/com/ford/service/ai/application/CatalogFactResponseRenderer.java`

**Interface:** Keep `appendSourceLinks(String answer, Map<String, String> toolPayloads)` unchanged. It returns the original answer if no returned catalog entity is named; it appends only approved links belonging to entities named in the answer.

- [x] **Step 1: Add a failing regression for an unused search result**

Add to `CatalogFactResponseRendererTest`:

```java
@Test
void doesNotAppendSearchLinksWhenFinalAnswerDoesNotNameTheReturnedVehicle() {
    var output = renderer.appendSourceLinks(
            "Para orientarle mejor, ¿cuál es su presupuesto máximo?",
            Map.of("search_vehicles", """
                    {"choices":[{"model":"Expedition","year":2027,
                     "modelPageUrl":"https://www.forduruapan.mx/EXPEDITION/27/",
                     "priceSourceUrl":"https://www.forduruapan.mx/Todos/"}]}
                    """));

    assertThat(output).isEqualTo("Para orientarle mejor, ¿cuál es su presupuesto máximo?")
            .doesNotContain("EXPEDITION", "Todos/", "Enlaces útiles");
}
```

- [x] **Step 2: Run the single test and confirm the expected failure**

Run: `./gradlew test --tests com.ford.service.ai.CatalogFactResponseRendererTest.doesNotAppendSearchLinksWhenFinalAnswerDoesNotNameTheReturnedVehicle`

Expected: FAIL because current renderer appends the Expedition and `/Todos/` links despite the reply not naming Expedition.

- [x] **Step 3: Add a relevant-link regression**

Add a test using the same payload and an answer containing `Expedition 2027`; assert its approved model-page URL is present. Assert an unrelated result URL stays absent if payload contains a second vehicle.

- [x] **Step 4: Implement minimal answer-entity filtering**

Before collecting candidate links, require the candidate's model or trim to occur as a whole entity in `answer`. Keep current URL validation, common-model-page handling, comparison filtering, URL deduplication, and two-link limit unchanged. For search result objects without an identified model/trim, add no link.

- [x] **Step 5: Run renderer tests**

Run: `./gradlew test --tests com.ford.service.ai.CatalogFactResponseRendererTest`

Expected: PASS, including existing comparison and exact-variant source-link tests.

### Task 2: Preserve parking context in safe clarification

**Files:**
- Modify: `src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`
- Modify: `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`

**Interface:** Keep `complete(AssistantRequest, ToolContext)` unchanged. Add a deterministic Spanish parking-ease clarification in the existing safe-fallback path; leave all other fallback strings unchanged.

- [x] **Step 1: Add a failing adapter regression**

Mock the provider to return an unsupported parking claim on both initial and quality-repair calls. Build the adapter with `repairEnabled=true` and an `EvidenceGroundedResponsePolicy`, then call it with `¿Pero sí se puede estacionar fácilmente?`.

Assert the final response contains `el catálogo no confirma`, `facilidad para estacionar`, `dimensiones publicadas`, `familia` and `ciudad`; assert it does not claim a named vehicle is easy to park or fits a space. Verify exactly two provider calls. Use this wording so the existing repeated-failure routing does not mistake a safe catalog caveat for a failed answer.

- [x] **Step 2: Run the single adapter test and confirm the expected failure**

Run: `./gradlew test --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest.parkingFollowUpUsesSafeContextualClarificationAfterRejectedRepair`

Expected: FAIL because current fallback is generic and drops the parking/family/city context.

- [x] **Step 3: Implement the parking-only clarification branch**

In `safeClarification(AssistantRequest)`, first detect a parking-ease question in `request.message()`. Return a fixed, fact-free message such as:

```java
"El catálogo no confirma la facilidad para estacionar. "
        + "Puedo comparar dimensiones publicadas tomando en cuenta sus necesidades de familia y ciudad."
```

Do not name or recommend a model, claim fit, state dimensions, or promise an advisor action. Preserve existing reply-deduplication behavior for all other clarification candidates.

- [x] **Step 4: Run the adapter regression and existing clarification tests**

Run: `./gradlew test --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest.parkingFollowUpUsesSafeContextualClarificationAfterRejectedRepair --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest`

Expected: PASS; rejected generated claims never reach the final response.

### Task 3: Verify complete local change

**Files:**
- Verify only; do not alter catalog data or runtime configuration.

- [x] **Step 1: Run all unit tests**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`, zero failures.

- [x] **Step 2: Run integration tests**

Run: `./gradlew integrationTest`

Expected: `BUILD SUCCESSFUL`, zero failures. Do not enable opt-in Qwen replay unless approved snapshot path is set and outbound/cloud remain disabled.

- [x] **Step 3: Check patch whitespace and scope**

Run: `git diff --check`

Expected: no output and exit code 0. Confirm only the planned source/test files changed; keep design/plan and pre-existing uncommitted files intact.
