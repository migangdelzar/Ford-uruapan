# No Repeated Assistant Greetings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow the assistant to greet on the first response, but prevent generated follow-up responses from starting with another greeting.

**Architecture:** Add an explicit opening-greeting check to the existing response repeat policy, which already receives recent assistant replies. On follow-up turns return fixed reason `repeated_greeting`; the existing one-repair path receives a specific no-greeting instruction. If the repaired answer repeats a greeting, preserve the existing fail-closed review behavior. Do not strip text from customer-facing answers.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Mockito, Gradle.

## Global Constraints

- First assistant response may greet and include the fixed assistant introduction.
- Follow-up assistant responses must not start with a repeated greeting; normal acknowledgements remain allowed.
- Preserve the one-repair limit and existing safe review path on persistent failure.
- Do not change conversation history, assistant identity, catalog facts, or delivery behavior.
- Run unit tests first; no Qwen replay or external delivery is needed.

---

### Task 1: Detect repeated opening greetings

**Files:**
- Modify: `src/main/java/com/ford/service/shared/AdvisorResponseRepeatPolicy.java`
- Test: `src/test/java/com/ford/service/shared/AdvisorResponseRepeatPolicyTest.java`

**Interface:** `AdvisorResponseRepeatPolicy.evaluate(...)` returns `Verdict(true, "repeated_greeting")` when the candidate starts with an allowlisted greeting and at least one prior assistant reply exists. It returns the normal non-repeated verdict for a first-turn greeting or a non-greeting acknowledgement.

- [ ] **Step 1: Write failing tests**

Add tests that follow-up candidates beginning with `¡Hola!`, `Hola,`, `Buenas tardes`, and `Buenos días` are rejected when prior assistant replies exist; a first-turn `¡Hola!` is accepted; `Entiendo, gracias por aclararlo.` is not rejected as an opening greeting. The policy result must contain only fixed reason codes.

- [ ] **Step 2: Run the focused test and observe RED**

```bash
./gradlew test --tests com.ford.service.shared.AdvisorResponseRepeatPolicyTest
```

Expected: new follow-up greeting assertions fail; existing tests remain otherwise unchanged.

- [ ] **Step 3: Implement bounded greeting detection**

Normalize accents/case and punctuation, match only the start of the candidate against a fixed Spanish greeting pattern, and require a non-empty prior assistant-reply list. Run this check before exact/near-repeat token comparison and return `repeated_greeting`. Do not add greeting words back into the generic content-token overlap set.

- [ ] **Step 4: Run focused tests**

```bash
./gradlew test --tests com.ford.service.shared.AdvisorResponseRepeatPolicyTest
```

Expected: `BUILD SUCCESSFUL`.

### Task 2: Repair once without a greeting; fail closed if repeated

**Files:**
- Modify: `src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`
- Test: `src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java`

- [ ] **Step 1: Add failing service behavior tests**

Use the existing mocked orchestrator setup. With prior assistant history and a first candidate beginning `¡Hola!`, assert the orchestrator is called once more with a repair instruction that explicitly says not to greet or introduce the assistant. If the repaired response also begins with a greeting, assert the existing `AssistantTurnUnavailableException` review disposition is returned. With no prior assistant history, assert a greeting candidate is accepted without repair.

- [ ] **Step 2: Run focused service tests and observe RED**

```bash
./gradlew test --tests com.ford.service.ai.SpringAiAssistantServiceTest
```

Expected: the specific repair-instruction assertion fails; repeated repair remains fail-closed.

- [ ] **Step 3: Add reason-specific repair instruction**

When the repeat verdict reason is `repeated_greeting`, use a fixed repair string instructing the model to answer the current message directly in Mexican Spanish, without “Hola”, greeting, or self-introduction. Keep the existing repair string for `exact_repeat`, `near_repeat`, and other reasons. Keep the single retry and existing review exception behavior.

- [ ] **Step 4: Run focused and full unit tests**

```bash
./gradlew test --tests com.ford.service.shared.AdvisorResponseRepeatPolicyTest --tests com.ford.service.ai.SpringAiAssistantServiceTest
./gradlew test
```

Expected: both commands report `BUILD SUCCESSFUL`.

### Task 3: Record the bounded behavior contract

**Files:**
- Modify: `docs/superpowers/specs/2026-10-29-catalog-llm-ready-read-model-design.md`

- [ ] **Step 1: Document verification**

Record that only the first response may greet, follow-up greeting candidates trigger one repair, and a repeated greeting after repair uses the existing fail-closed review behavior. Do not claim Qwen replay validation unless a separate bounded local replay is approved and run.

- [ ] **Step 2: Verify docs and diff**

```bash
git diff --check
```

Expected: no whitespace errors; no production or delivery configuration changes.

## Self-Review

- First-turn greetings remain accepted (Task 1 and Task 2).
- Follow-up greetings are caught deterministically, not just discouraged by prompt (Task 1).
- Repair is bounded to one call; persistent violation remains a review outcome (Task 2).
- No catalog/DB dependency; this plan can be implemented and tested independently of the catalog read model.
