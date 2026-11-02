# Yearless Trim Ambiguity and Replay Precision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent cross-variant facts/URLs for ambiguous yearless trims and make replay precision report only what its captured evidence supports.

**Architecture:** Keep catalog resolution fail-closed at `CatalogTools`: multiple year/trim candidates become an identity-only ambiguity result, and the existing assistant adapter asks the buyer to choose. Add a defensive source-link guard so multi-candidate payloads never yield an arbitrary variant URL. Scope precision scoring to captured replay-prefetch evidence and mark turns with uncaptured model-tool results unscored.

**Tech Stack:** Java 25, Spring Boot, Spring AI tool callbacks, JUnit 5, AssertJ, Gradle, local Qwen/Testcontainers integration harness.

## Global Constraints

- No production/`emme-2` writes, catalog import, transcript seeding, or 100-turn acceptance.
- Use only the approved local snapshot and disposable PostgreSQL for live replay.
- Diagnostic live replay remains capped at 32 turns.
- Keep no-handoff, safety, evidence, retry, and tool-authorization checks enabled.
- Do not write raw tool arguments, complete evidence payloads, credentials, or production data into replay reports.
- Preserve exact-year lookup and yearless model-only behavior.

---

### Task 1: Return identity-only ambiguity for yearless multi-variant trims

**Files:**
- Modify: `src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java`
- Modify: `src/main/java/com/ford/service/ai/application/ToolResultTracker.java`
- Modify: `src/main/java/com/ford/service/ai/application/AmbiguousVehicleTrimResult.java`
- Modify: `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`
- Test: `src/test/java/com/ford/service/ai/CatalogToolsTest.java`
- Test: `src/test/java/com/ford/service/ai/ToolResultTrackerTest.java`
- Test: `src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`

**Interfaces:**
- Keep `CatalogTools.getVehicle(String model, Integer year, String trim)` signature unchanged.
- Keep the existing serialized ambiguity shape: `model`, nullable `modelYear`, and `choices` containing only `VehicleTrimCandidate(model, year, trim)` identities.
- Extend `AmbiguousVehicleTrimResult` to support a null year; choices must always share the model and must share the year only when the result year is non-null.
- Keep exact-year ambiguity clarification wording unchanged. For yearless ambiguity, include each candidate's year and canonical trim in the deterministic clarification.

- [ ] **Step 1: Add failing unit tests**

In `CatalogToolsTest`, create two 2027 Ranger XLT canonical entries (`XLT Gasolina 4x2`, `XLT Diésel 4x4`) and exact catalog evidence with distinct price/comparison URLs. Call `getVehicle("Ranger", null, "XLT")`. Assert the result is `AmbiguousVehicleTrim`, `modelYear()` is null, and `choices()` contains both canonical identities. Assert the result contains no price, facts, or source URLs. Keep the existing unknown-alias, yearless model-only, and exact-year alias tests unchanged.

Add a one-candidate yearless alias case with one canonical name-index entry and assert the exact canonical evidence is returned. This proves identity-only clarification occurs only when there are multiple candidates.

In `ToolResultTrackerTest`, serialize a yearless ambiguity result and assert `ambiguousVehicleTrim()` returns both choices with their own candidate years; test invalid or single-choice payloads remain unrecognized.

In `SpringAiChatCompletionAdapterTest`, feed a yearless ambiguous tracker result and assert the adapter returns a deterministic `CLARIFICATION` naming both year/version choices, without price/fact text or URLs. Keep the exact-year clarification test passing unchanged.

- [ ] **Step 2: Run focused tests and observe RED**

From `ford-ai-service`, run:

```bash
./gradlew test --tests com.ford.service.ai.CatalogToolsTest --tests com.ford.service.ai.ToolResultTrackerTest --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest
```

Expected: new yearless ambiguity assertions fail because the current tool returns detailed `ModelYearOptions`, and ambiguity tracking requires one non-null model year.

- [ ] **Step 3: Implement yearless ambiguity and deterministic clarification**

Refactor the yearless trim path to collect resolved canonical candidates before fetching facts. If there are more than one candidate, return `new AmbiguousVehicleTrim(model, null, candidates)` without calling `catalog.find`. If there is one candidate, fetch only its exact canonical identity and return the existing yearless options result. If no candidates or no fetched evidence exist, preserve `no_model_year_options_found` with an empty list.

Relax `AmbiguousVehicleTrim` and `AmbiguousVehicleTrimResult` year validation only for null-year results; continue validating exact-year choices. Update `ToolResultTracker.ambiguousVehicleTrim()` to parse choices when `modelYear` is null and preserve each choice's own year. Update `SpringAiChatCompletionAdapter.ambiguousTrimClarification()` so exact-year results retain current wording and yearless results list canonical `year + trim` choices. Do not change tool routing, retries, or safety policy.

The yearless branch must collect candidates before evidence fetch:

```java
var candidates = new ArrayList<com.ford.service.ai.application.VehicleTrimCandidate>();
var nameEntries = catalog.nameIndexEntries();
for (var candidateYear : modelYears) {
    var resolution = vehicleResolver.resolve(model, candidateYear, trim, nameEntries);
    switch (resolution.status()) {
        case EXACT, UNIQUE_CANONICAL_MATCH -> candidates.add(resolution.exact());
        case AMBIGUOUS -> candidates.addAll(resolution.choices());
        case MISSING, NOT_REQUIRED -> { }
    }
}
if (candidates.size() > 1) {
    return new AmbiguousVehicleTrim(model, null, candidates);
}
var options = candidates.stream()
        .map(candidate -> catalog.find(new VehicleLookup(candidate.model(), candidate.year(), candidate.trim()))
                .orElse(null))
        .filter(Objects::nonNull)
        .map(this::enrich)
        .toList();
return new ModelYearOptions(model, options.isEmpty()
        ? "no_model_year_options_found" : "available_model_year_options", options);
```

For the deterministic clarification, format yearless choices from their own candidate year:

```java
var options = ambiguous.choices().stream()
        .map(choice -> choice.year() + " " + choice.trim())
        .collect(java.util.stream.Collectors.joining(" o "));
var content = "Para revisar la versión exacta de " + ambiguous.model()
        + ", ¿cuál busca: " + options + "?";
```

- [ ] **Step 4: Rerun focused unit tests**

Run the command from Step 2. Expected: all selected test classes pass.

- [ ] **Step 5: Commit ambiguity handling**

Run `git diff --check`, review the task diff, then commit:

```bash
git add src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java src/main/java/com/ford/service/ai/application/ToolResultTracker.java src/main/java/com/ford/service/ai/application/AmbiguousVehicleTrimResult.java src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java src/test/java/com/ford/service/ai/CatalogToolsTest.java src/test/java/com/ford/service/ai/ToolResultTrackerTest.java src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java
git commit -m "fix: clarify ambiguous yearless vehicle trims"
```

---

### Task 2: Bind source links to one exact vehicle candidate

**Files:**
- Modify: `src/main/java/com/ford/service/ai/application/CatalogFactResponseRenderer.java`
- Test: `src/test/java/com/ford/service/ai/CatalogFactResponseRendererTest.java`

**Interfaces:**
- Keep `appendSourceLinks(String answer, Map<String, String> toolPayloads)` signature unchanged.
- For an ambiguous/multi-vehicle payload, do not append trim-specific price or comparison URLs. A model-page URL may be appended only if it is common to all candidates.
- For a single exact vehicle payload, preserve existing source-link behavior.

- [ ] **Step 1: Add failing source-link tests**

Add a payload with two Ranger 2027 choices: Gasolina has `.../XLT-GASOLINA-4X2/`; Diésel has `.../XLT-DIESEL-4X4/`. For an answer naming XLT Gasolina with no URL, assert `appendSourceLinks` does not append the Diésel URL or any arbitrary trim-specific source. Add a single-candidate control asserting its exact price URL is still appended. Add a shared model-page URL case asserting the common model URL may be appended.

- [ ] **Step 2: Run renderer test and observe RED**

Run:

```bash
./gradlew test --tests com.ford.service.ai.CatalogFactResponseRendererTest
```

Expected: the multi-candidate test fails because current recursion flattens all candidate links and selects the first usable links.

- [ ] **Step 3: Add fail-closed multi-candidate link selection**

Before collecting source links, detect whether a `get_vehicle` payload has multiple vehicle candidates in `choices` or `options`. In that case, collect only model-page URLs, require one distinct URL shared by every candidate, and skip `priceSourceUrl`, `comparisonUrl`, and technical-sheet links. Keep existing collector behavior for a single exact vehicle and other tool types.

Use a separate branch before recursive URL collection. For a multi-candidate `get_vehicle` payload, extract all objects in `choices` or `options`; append one `modelPageUrl` only if every candidate has the same approved nonblank value. Otherwise append no URL from that payload. Never inspect candidate order to choose a URL.

The call site should make the multi-candidate branch explicit:

```java
parsePayload(entry.getValue()).ifPresent(root -> {
    if ("get_vehicle".equals(entry.getKey()) && hasMultipleVehicleCandidates(root)) {
        addOnlyCommonModelPageUrl(root, links);
    } else collectLinks(root, entry.getKey(), links);
});
```

- [ ] **Step 4: Rerun renderer tests and full unit suite**

Run:

```bash
./gradlew test --tests com.ford.service.ai.CatalogFactResponseRendererTest
./gradlew test
```

Expected: both commands finish with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit source-link guard**

Run `git diff --check`, review the task diff, then commit:

```bash
git add src/main/java/com/ford/service/ai/application/CatalogFactResponseRenderer.java src/test/java/com/ford/service/ai/CatalogFactResponseRendererTest.java
git commit -m "fix: avoid unbound links for ambiguous vehicles"
```

---

### Task 3: Scope precision scoring to complete captured evidence

**Files:**
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvaluator.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvaluatorTest.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java`

**Interfaces:**
- Add an `evidenceComplete` input to catalog precision evaluation and an `evidenceComplete` field to `PrecisionResult`. When false, detected catalog claims are unscored, never unsupported.
- Treat a turn as incomplete when its model tool traces include a catalog fact tool whose returned payload is not stored in `LocalReplayPrecisionEvidence`.
- Rename report heading to `Replay-prefetch-only catalog claim precision` and report excluded/incomplete turn count.
- Keep current diagnostics content-free: do not persist tool payloads or raw claims.

- [ ] **Step 1: Add failing precision and report tests**

In `LocalReplayPrecisionEvaluatorTest`, verify a claim absent from prefetch text but evaluated with `evidenceComplete=false` appears in `unscoredClaims`, not `unsupportedClaims`. Verify complete captured evidence still produces supported/unsupported results normally.

In `LocalQwenTranscriptWriterTest`, record one complete and one incomplete precision result. Assert the report uses `Replay-prefetch-only catalog claim precision`, reports incomplete-turn count, and does not present uncaptured-tool claims as unsupported.

- [ ] **Step 2: Run focused integration-test classes and observe RED**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayPrecisionEvaluatorTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest
```

Expected: compilation/assertion failure because catalog precision has no evidence-completeness input and reports use the broad label.

- [ ] **Step 3: Implement evidence-completeness-aware reporting**

Pass a boolean to `evaluateCatalog`. If false, place every detected claim in `unscoredClaims`; otherwise retain existing exact-evidence matching. In the replay loop, set completeness false when a model-origin catalog fact tool call appears in `toolEvents`; prefetched-only turns remain scoreable. Update per-turn and aggregate report labels and count results excluded due to incomplete evidence. Do not expose raw evidence or answer claims in telemetry.

The evaluator's incomplete-evidence branch must not turn missing capture into a negative result. Set `PrecisionResult.evidenceComplete` to the input value so the writer can count excluded turns:

```java
if (!evidenceComplete) {
    unscored.addAll(claims);
} else {
    for (var claim : claims) {
        if (evidence.stream().anyMatch(text -> containsExactClaim(text, normalize(claim)))) {
            supported.add(claim);
        } else {
            unsupported.add(claim);
        }
    }
}
```

- [ ] **Step 4: Rerun focused integration-test classes**

Run the command from Step 2. Expected: both classes pass.

- [ ] **Step 5: Commit precision reporting**

Run `git diff --check`, review the task diff, then commit:

```bash
git add src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvaluator.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriter.java src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java src/integrationTest/java/com/ford/service/conversations/LocalReplayPrecisionEvaluatorTest.java src/integrationTest/java/com/ford/service/conversations/LocalQwenTranscriptWriterTest.java
git commit -m "test: scope replay precision to captured evidence"
```

---

### Task 4: Verify gates and rerun bounded local diagnostic

**Files:**
- Create: `src/integrationTest/java/com/ford/service/conversations/LocalReplayYearlessAmbiguityAssertions.java`
- Test: `src/integrationTest/java/com/ford/service/conversations/LocalReplayYearlessAmbiguityAssertionsTest.java`
- Modify: `src/integrationTest/java/com/ford/service/conversations/QwenTwoTurnWebhookReplayIntegrationTest.java`
- Update: `docs/superpowers/specs/2026-10-29-yearless-trim-ambiguity-and-replay-precision-design.md`
- Update: `docs/superpowers/plans/2026-10-29-yearless-trim-ambiguity-and-replay-precision.md`

- [x] **Step 1: Run full unit suite**

From `ford-ai-service`, run:

```bash
./gradlew test
```

Verification: `BUILD SUCCESSFUL` (2026-10-29).

- [x] **Step 2: Run focused non-live integration gates**

Run:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayPrecisionEvaluatorTest --tests com.ford.service.conversations.LocalQwenTranscriptWriterTest --tests com.ford.service.conversations.LocalQwenReplayExecutionModeTest --tests com.ford.service.conversations.LocalQwenReplayWindowTest --tests com.ford.service.conversations.LocalQwenTurnTelemetryTest
```

Verification: `BUILD SUCCESSFUL` (2026-10-29); these tests did not call Qwen or send webhooks.

- [x] **Step 3: Add a deterministic turn-31 bounded-replay assertion**

Create `LocalReplayYearlessAmbiguityAssertions` and its unit test. Write a failing test first: a clarification naming `2027 XLT Gasolina 4x2` and `2027 XLT Diésel 4x4` passes, while a Gasolina statement containing the Diésel comparison URL fails. Run the focused integration-test class and observe RED, implement the assertion helper, then rerun it. In `runNaturalBuyerReplay`, call the helper only for bounded diagnostic turn 31 after the reply is committed. Keep the normal no-handoff and `LocalReplaySafetyAssertions` checks in place.

Run the helper test from `ford-ai-service`:

```bash
./gradlew integrationTest --tests com.ford.service.conversations.LocalReplayYearlessAmbiguityAssertionsTest
```

Verification: test first failed to compile because the helper class did not exist; after implementation, `BUILD SUCCESSFUL`.

The helper API should stay small and content-safe:

```java
static void assertYearlessTrimClarification(String answer) {
    assertThat(answer).contains("¿cuál busca:", "2027 XLT Gasolina 4x2", "2027 XLT Diésel 4x4");
    assertThat(answer).doesNotContain("/Comparativo/");
}
```

- [x] **Step 4: Run the bounded live replay with the approved local inputs (diagnostic failed safely)**

Run only after Steps 1–3 pass. Use a clean environment with the approved Oct 25 snapshot, local Ollama `qwen3.5:4b`, local BGE preflight, disposable Testcontainers PostgreSQL, explicit `BOUNDED_LIVE_DIAGNOSTIC`, and end turn `32`. Leave cloud credentials, seed/checkpoint settings, and `FORD_QWEN_100_REPLAY_ENABLED` unset.

Run from `ford-ai-service`:

```bash
env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-en_US.UTF-8}" \
  TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  FORD_LOCAL_CATALOG_EXPORT_DIR="$HOME/.local/share/opencode/sandbox/ford-ai-service-retrieval-20261025/build/reports/local-qwen/source-export-oct25-candidate" \
  FORD_CATALOG_SNAPSHOT_LABEL=oct25-candidate \
  FORD_AI_CATALOG_NAME_INDEX_ENABLED=true \
  FORD_AI_CLOUD_ENABLED=false \
  FORD_AI_LOCAL_ENABLED=true \
  FORD_AI_LOCAL_PROVIDER=ollama \
  FORD_AI_LOCAL_BASE_URL=http://localhost:11434 \
  FORD_AI_LOCAL_MODEL_VERSION=qwen3.5:4b \
  FORD_QWEN_REPLAY_MODEL=qwen3.5:4b \
  FORD_QWEN_REPLAY_ENABLED=true \
  FORD_QWEN_COMPARISON_PREFETCH_ENABLED=true \
  FORD_QWEN_REPLAY_MODE=BOUNDED_LIVE_DIAGNOSTIC \
  FORD_QWEN_DIAGNOSTIC_END_TURN=32 \
  ./gradlew integrationTest --tests com.ford.service.conversations.QwenTwoTurnWebhookReplayIntegrationTest
```

**Verification outcome (not a pass):** Run `7d4daebccbdd4cf1b005e88aa421f188` used the approved snapshot and local Qwen with disposable Testcontainers PostgreSQL. Turns 1–30 committed; turn 31 entered `NEEDS_OPERATOR` (`inbound_or_turn_invariant`) before a reply was committed. Turn 32 was not sent, so the new clarification assertion did not execute. Handoff count was not recorded. The partial report correctly labeled precision as prefetch-only and counted 5 incomplete-evidence turns. Artifact paths are recorded in the spec. No 100-turn lane was enabled or called.

- [x] **Step 5: Record findings and commit verification**

Updated the spec with the run ID, Markdown/HTML/JSONL artifact paths, turn-31 outcome, turn-32 not-sent status, unavailable handoff count, partial precision counts, and unresolved historical-price discrepancy. The 100-turn gate remains paused. Run `git diff --check`, inspect the documentation diff, then commit:

```bash
git add docs/superpowers/specs/2026-10-29-yearless-trim-ambiguity-and-replay-precision-design.md docs/superpowers/plans/2026-10-29-yearless-trim-ambiguity-and-replay-precision.md
git commit -m "docs: record bounded trim diagnostic findings"
```

## Self-Review

- Ambiguous yearless behavior, identity-only candidate data, deterministic clarification, and exact-year compatibility are covered in Task 1.
- Arbitrary variant-link selection and single-candidate control are covered in Task 2.
- Incomplete model-tool evidence cannot inflate the unsupported count; report scope and incomplete counts are covered in Task 3.
- Unit/integration gates and capped local replay were executed; replay stopped safely at turn 31 before the no-handoff completion assertion. Safety checks remained enabled. Artifacts and the failed diagnostic outcome are documented, and 100-turn acceptance remains paused.
- No production writes, catalog imports, transcript seeds, raw payload dumps, or remote-provider credentials are part of the plan.
