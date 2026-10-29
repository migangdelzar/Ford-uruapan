# No-Handoff Memory and Precision Replay Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the synthetic 100-turn prefetched Qwen memory benchmark with zero handoffs, no inventory checks, unchanged live inventory handoff behavior, and separate catalog-grounding and memory-recall precision diagnostics.

**Architecture:** Replace the three inventory-specific synthetic prompts with explicit buyer-memory questions carrying expected buyer facts. Narrow the replay inventory-claim detector to stock/location assertions without weakening it. Add deterministic precision diagnostics from current-turn catalog evidence and buyer-only history; metrics remain diagnostic and never override replay safety failures.

**Tech Stack:** Java, Spring Boot integration tests, JUnit 5, AssertJ, Mockito, local Qwen/Ollama, disposable PostgreSQL catalog snapshot.

## Global Constraints

- All changes stay in integration-test replay code and synthetic local test data.
- No production writes, customer delivery, or hosted AI.
- Qwen writes customer-facing text for prefetched turns; prefetch supplies evidence only.
- The 100-turn prefetched replay must finish with zero handoffs.
- Actual inventory requests remain handoff-only outside this benchmark; no inventory tool or availability claim may be used to complete benchmark turns.
- Existing inventory, scope, grounding, and response safety checks remain enabled. No safety gate is weakened to make the replay pass.
- Precision metrics cannot convert a safety failure into a pass.
- Catalog and memory scores must report numerator, denominator, unscored count, and coverage. Unknown/unscored claims never count as supported.
- Raw replay remains separate and unchanged.

---

## File Map

- Modify `src/integrationTest/resources/fixtures/meta/natural-buyer-100-turns.json`: replace fixture turns 67, 69, and 70 with memory questions; preserve exactly 100 numbered turns.
- Modify `src/integrationTest/java/com/ford/service/conversations/NaturalBuyerConversationScenario.java`: add `MEMORY_RECALL` and optional expected-memory-fact labels.
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertions.java` and its test: distinguish actual stock/location claims from a missing catalog specification.
- Create `src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvaluator.java` and test: score deterministically extracted catalog and memory claims without external AI.
- Modify `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`: supply buyer-only history and current-turn evidence to the evaluator, assert zero handoffs, and fail replay on existing safety violations as before.
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java` and its test: write per-turn and aggregate precision/coverage results into the local replay report.
- Modify `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptHtmlRenderer.java` and its test only if required to mirror the Markdown precision summary.

## Data and Interfaces

Update `NaturalBuyerConversationScenario.Turn` to carry `List<String> expectedMemoryFacts`, defaulting to an empty immutable list for existing JSON records and Java call sites. Keep a three-argument constructor for compatibility with current tests. Add `MEMORY_RECALL` to `Expectation`.

Use these three memory probes and fact labels:

| Turn | Buyer prompt | Expected labels |
|---|---|---|
| 67 | `¿Qué uso te comenté para la camioneta entre semana y los fines de semana?` | `weekday_work`, `weekend_family` |
| 69 | `¿Qué cosas dije que quería llevar en la caja?` | `bicycles`, `camping_gear` |
| 70 | `¿Qué versión exacta de Ranger revisamos cuando pregunté su precio?` | `ranger_2027_xlt_gasolina_4x2` |

These labels must match earlier buyer messages, not assistant messages. Do not add inventory wording to replacement prompts.

`LocalReplayPrecisionEvaluator.evaluateCatalog(Turn turn, String answer, List<String> sameTurnEvidenceTexts)` returns immutable `PrecisionResult` containing `supportedClaims`, `unsupportedClaims`, `unscoredClaims`, and coverage. Candidate claims are deterministic entities from the reply: catalog model/year/trim names, currency amounts, dates, measurements/unit values, other numeric specification values, and URLs. Match normalized claim values against evidence text from the current turn only. An absent or ambiguous value is unsupported or unscored according to the extractor contract; never silently count it as supported.

`LocalReplayPrecisionEvaluator.evaluateMemory(Turn turn, String answer, List<String> priorBuyerMessages)` evaluates recognized memory-fact labels against both the expected labels and buyer-only history. Each explicit recognized memory claim is supported only when the corresponding fact appears in prior buyer messages. A missing expected fact is reported as a recall miss; unsupported recalled details lower precision. Assistant history is never supplied.

Aggregate each class independently: `precision = supported / (supported + unsupported)`; `coverage = scored / (scored + unscored)`. If a denominator is zero, serialize the score as `N/A`, not `100%`. Report recall misses separately from precision so omitted memories are not mislabeled as correct.

---

### Task 1: Replace Inventory Prompts With Memory Probes

**Files:**
- Modify: `src/integrationTest/resources/fixtures/meta/natural-buyer-100-turns.json`
- Modify: `src/integrationTest/java/com/ford/service/conversations/NaturalBuyerConversationScenario.java`
- Test: existing scenario-loading tests, or create `NaturalBuyerConversationScenarioTest.java` if no focused test exists.

- [ ] **Step 1: Add failing scenario tests**

Assert exactly 100 ordered turns; turn numbers 67, 69, and 70 have expectation `MEMORY_RECALL`, the exact approved prompts above, and the expected fact labels in the table. Assert the other 97 messages and expectations remain unchanged.

- [ ] **Step 2: Verify RED**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.NaturalBuyerConversationScenarioTest`

Expected: compilation or assertions fail because the expectation and fields are absent.

- [ ] **Step 3: Update fixture and scenario record**

Add `MEMORY_RECALL`; add immutable `expectedMemoryFacts` with null-to-empty default; retain the three-argument constructor. Change only fixture turns numbered 67, 69, and 70. Use `weekday_work`, `weekend_family`, `bicycles`, `camping_gear`, and `ranger_2027_xlt_gasolina_4x2` as expected labels.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.NaturalBuyerConversationScenarioTest`

Expected: `BUILD SUCCESSFUL`, still exactly 100 turns, and all labels load from JSON.

### Task 2: Narrow Inventory Claim Detection Without Weakening It

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertions.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplaySafetyAssertionsTest.java`

- [ ] **Step 1: Add failing detector cases**

Assert `¿Hay Ranger XLT disponible en la sucursal?`, `No tenemos unidades en stock`, and `La disponibilidad está confirmada` produce `UNAPPROVED_INVENTORY_CLAIM` unless explicitly approved. Assert `La ficha técnica completa no está disponible en este catálogo` does not produce that violation. Keep all existing true-positive inventory tests.

- [ ] **Step 2: Verify RED**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest`

Expected: missing-specification sentence incorrectly triggers the inventory violation.

- [ ] **Step 3: Narrow replay-only inventory matcher**

Require stock/location language in the same assertion before matching availability terms. Do not remove the stock, units, branch, color-availability, or confirmation cases. Do not modify production handoff or inventory policy.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplaySafetyAssertionsTest`

Expected: true inventory claims remain blocked; absent catalog specifications do not trigger the inventory detector.

### Task 3: Add Precision Evaluator Tests and Implementation

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvaluator.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvaluatorTest.java`

- [ ] **Step 1: Write failing catalog-precision tests**

Use same-turn evidence containing `RANGER 2027 XLT GASOLINA 4X2`, `$907,000 MXN`, `185 hp`, and `https://ford.test/xlt`. Verify exact model/version, amount, numeric specification, and URL claims score supported. Verify an answer claiming `$999,000 MXN` is unsupported. Verify a claim not recognized by the deterministic extractor increments `unscoredClaims`, not `supportedClaims`. Verify precision and coverage denominators, including zero-denominator `N/A`.

- [ ] **Step 2: Write failing memory-precision tests**

For each of the three fixture labels, verify a matching response and matching prior buyer message is supported; an assistant-only prior message does not support it; a contradictory/unsupported recognized recall is unsupported; an omitted expected label is a recall miss. Verify all returned result collections are immutable.

- [ ] **Step 3: Verify RED**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayPrecisionEvaluatorTest`

Expected: compile failure because evaluator and result records are absent.

- [ ] **Step 4: Implement deterministic, local-only extraction**

Extract only the documented candidate entities. Normalize case, whitespace, accents, and equivalent currency separators before evidence comparison. Never call a hosted or local judge model. For memory labels, use a bounded explicit synonym map for the approved prompts; compare only against buyer-only history. Track supported, unsupported, unscored, and expected-fact recall-miss counts.

- [ ] **Step 5: Verify evaluator tests**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayPrecisionEvaluatorTest`

Expected: `BUILD SUCCESSFUL`; every count and denominator matches test fixtures.

### Task 4: Add Precision Reporting and Zero-Handoff Assertion

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`
- Modify/test: `LocalQwenTranscriptHtmlRenderer.java` and its test only if HTML output is part of current required replay artifacts.

- [ ] **Step 1: Add failing writer/report tests**

Assert Markdown reports per-turn catalog precision and memory precision counts, aggregate numerators/denominators, coverage, recall misses, and `N/A` for empty denominators. Assert incomplete/failed replay reports are marked incomplete and do not say 100-turn success.

- [ ] **Step 2: Add failing no-handoff replay assertion**

In the synthetic replay harness, assert each committed turn uses a bot reply and never enters human-active/handoff state. Preserve all existing grounded-answer checks. Add a focused test proving a handoff stops the no-handoff benchmark rather than being counted as a successful turn.

- [ ] **Step 3: Verify RED**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest`

Expected: compilation/assertions fail because precision summary and no-handoff assertion are absent. Do not enable the slow local 100-turn environment flags for this red run.

- [ ] **Step 4: Wire current-turn metrics**

For each response, pass the exact current-turn catalog evidence text associated with positive current-turn evidence events to `evaluateCatalog`. For `MEMORY_RECALL`, pass only prior buyer messages and the turn's expected labels to `evaluateMemory`. Store metrics as local test-report metadata; do not add claim content to telemetry. Keep the existing safety gate authoritative.

- [ ] **Step 5: Write report results and assert no handoffs**

Append per-turn and aggregate metrics to the Markdown transcript. Keep raw and prefetched artifacts separate. Add a no-handoff assertion to the opt-in prefetched 100-turn loop; do not change raw mode or production handoff behavior.

- [ ] **Step 6: Verify GREEN**

Run: `./gradlew integrationTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest --tests com.ford.service.conversations.LocalReplayPrecisionEvaluatorTest --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest`

Expected: focused tests pass; no production source or hosted AI is used.

### Task 5: Full Verification and Replay

**Files:**
- Verify: modified synthetic fixture, replay evaluator, safety assertions, and transcript artifacts only.
- Verify: raw replay configuration and raw evidence-origin tests remain unchanged.

- [ ] **Step 1: Run unit tests**

Run: `./gradlew test --rerun-tasks`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run focused integration tests**

Run the scenario, safety, precision, assistant routing, configuration, and transcript test classes.

Expected: `BUILD SUCCESSFUL`; actual inventory claims remain blocked; false-positive spec-absence wording passes; no-handoff semantics are covered.

- [ ] **Step 3: Run full prefetched local Qwen replay without a timeout**

Run from `ford-ai-service`:

```bash
FORD_LOCAL_CATALOG_EXPORT_DIR="$HOME/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate" \
FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate \
FORD_QWEN_REPLAY_ENABLED=true \
FORD_QWEN_100_REPLAY_ENABLED=true \
FORD_AI_CATALOG_NAME_INDEX_ENABLED=true \
FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true \
./gradlew integrationTest --rerun-tasks --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

Expected: `100/100 turns complete`, zero handoffs, all original safety checks passed, and both precision metrics include numerator, denominator, coverage, and per-turn results. If any turn fails, report the exact turn and reason; do not loosen gates or claim success.

- [ ] **Step 4: Verify raw/prefetched separation**

Run focused configuration and evidence-origin tests. Confirm raw mode has no replay-prefetch bean and no `REPLAY_PREFETCH` evidence. Do not run the raw 100-turn replay as a success requirement.

## Self-Review

- Fixture edits preserve 100 turns and replace only inventory turns 67, 69, 70.
- Real inventory requests still require human handoff outside the synthetic benchmark.
- Turn 13 missing-spec language is not confused with stock availability; true stock claims remain blocked.
- Both precision scores report denominators and coverage; unknown claims are not counted as true.
- Existing evidence, comparison scope, choice, and response safety gates remain authoritative.
- Raw replay remains separate and unchanged.
