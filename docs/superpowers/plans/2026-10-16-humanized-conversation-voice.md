# Humanized Ford Conversation Voice Implementation Plan

> **For agentic workers:** Implement this plan inline, one task at a time, with test-first checkpoints. Do not spawn agents.

**Goal:** Make existing Ford Service replies consistently feel like MÍA speaking formal Mexican Spanish while preserving the current conversation, MCP, sales, and advisor behavior.

**Architecture:** Keep the current Spring AI adapter, runtime-editable system prompt, conversation policies, and message ownership. Pin the assistant identity in the existing persona, add an immutable code-owned style instruction after editable/runtime prompt material, and update existing fixed customer messages at their current owners.

**Tech Stack:** Java 21, Spring Boot, Spring AI, JUnit 5, AssertJ, Gradle, existing GitHub Actions release-all workflow.

## Global Constraints

- The assistant identity is MÍA and customer address is formal **usted**.
- Keep the current new-Ford model and promotion flows, one-question-at-a-time rule, memory behavior, lead fields, MCP authorization, and handoff authorization.
- Communicate only MCP-verified published catalog prices and promotion terms; the advisor owns final quotes and negotiation.
- Keep `SystemPromptService` runtime editing and ensure the code-owned voice rule applies after stored prompt content on every model attempt, including repair.
- Do not add CRM, new lead fields, service scheduling, or new response-time promises.
- Do not log prompts, model answers, tool payloads, or secrets.
- Use the current versioned release-all path; select Ford Service only because this change does not alter MCP or Ford Web.

---

## File map

- `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantPersona.java`: fixed assistant name and initial introduction.
- `ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md`: classpath seed aligned with MÍA and formal address.
- `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`: immutable style instruction shared by first generation and repair.
- `ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`: security fallback copy.
- `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java`: quality-recovery copy.
- `ford-ai-service/src/main/java/com/ford/service/shared/HumanHandoffPolicy.java`: provider fallback copy.
- `ford-ai-service/src/main/java/com/ford/service/ai/application/DefaultAssistantOrchestrator.java`: human-active reply.
- `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationHandoffMessageService.java`: advisor takeover copy; message remains authored by the operator ID.
- `ford-ai-service/src/main/java/com/ford/service/conversations/application/PendingTestDriveReplyPolicy.java`: test-drive continuation copy.
- Corresponding tests under `ford-ai-service/src/test/java/...`: enforce exact identity/copy and runtime prompt precedence.
- `tasks/todo.md`: track implementation and production verification.

## Task 1: Pin MÍA as the conversation identity

**Files:**
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/application/AssistantPersonaTest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantPersona.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java`

**Consumes:** Existing `AssistantPersona.nameFor(UUID)` and `introductionFor(UUID)` API.

**Produces:** Both methods return the fixed identity MÍA; `SpringAiAssistantService` still introduces the assistant only on a first turn.

- [x] **Step 1: Write the failing persona tests.** Replace the rotating-name expectation with:

```java
assertThat(persona.nameFor(new UUID(0, 0))).isEqualTo("MÍA");
assertThat(persona.nameFor(new UUID(0, 1))).isEqualTo("MÍA");
assertThat(persona.introductionFor(new UUID(0, 0)))
        .isEqualTo("Hola, soy *MÍA*, su asistente virtual de Ford Uruapan. 👋");
```

Update the assistant-service first-turn test so two new conversation IDs start with the same MÍA introduction, and a follow-up with history does not repeat it.

- [x] **Step 2: Run the focused tests and verify the expected failure.**

Run from the repository root: `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.ai.application.AssistantPersonaTest --tests com.ford.service.ai.SpringAiAssistantServiceTest`

Expected: FAIL because `AssistantPersona` still rotates names and its introduction omits “virtual”.

- [x] **Step 3: Implement the fixed persona.** Keep the method signatures, reject null IDs as today, remove the rotating name list, and return `MÍA`. Use the exact introduction in the test.

- [x] **Step 4: Re-run the focused tests.**

Run from the repository root: `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.ai.application.AssistantPersonaTest --tests com.ford.service.ai.SpringAiAssistantServiceTest`

Expected: PASS; first-turn copy uses MÍA and later turns do not add another introduction.

- [x] **Step 5: Commit this task.**

```bash
git add ford-ai-service/src/test/java/com/ford/service/ai/application/AssistantPersonaTest.java ford-ai-service/src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantPersona.java
git commit -m "fix(ai): use MÍA as the stable assistant identity"
```

## Task 2: Align deterministic recovery and safety replies with formal address

**Files:**
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/AssistantResponseQualityPolicyTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/HumanHandoffPolicyTest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/shared/HumanHandoffPolicy.java`

**Consumes:** Existing security reasons, response-quality reason codes, and provider fallback path.

**Produces:** Existing fallback behavior with customer-facing copy consistently using **usted**.

- [ ] **Step 1: Add assertions for each current recovery path.** Assert formal copy for prompt-injection and sensitive-data responses, quality recovery for `repeated_response` and `internal_payload`, and `HumanHandoffPolicy.fallbackText()`. Keep reason codes and handoff behavior unchanged.

Expected exact copy:

```text
Puedo ayudarle con información de vehículos, ofertas y financiamiento Ford. ¿Qué desea consultar?
Por seguridad, elimine los datos personales o financieros del mensaje y vuelva a enviarlo. Puedo ayudarle con su consulta Ford.
No pude generar una respuesta en este momento. Intente de nuevo en unos segundos.
No quiero repetirle la respuesta anterior. ¿Qué detalle desea que revise?
No pude preparar una respuesta clara con la información disponible. Intente de nuevo en unos segundos.
```

Recovery copy must not address the customer as “tú”; it must remain short, honest, and ask no more than one question.

- [ ] **Step 2: Run focused tests and verify the old copy fails the new assertions.**

Run from the repository root: `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.ai.SpringAiAssistantServiceTest --tests com.ford.service.ai.AssistantResponseQualityPolicyTest --tests com.ford.service.conversations.HumanHandoffPolicyTest`

Expected: FAIL only on the new formal-address assertions.

- [ ] **Step 3: Update only the existing message constants/branches.** Use “ayudarle”, “su”, “desea”, and “intente”; retain all current policy branches and error reason codes.

- [ ] **Step 4: Re-run the focused tests.**

Run from the repository root: `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.ai.SpringAiAssistantServiceTest --tests com.ford.service.ai.AssistantResponseQualityPolicyTest --tests com.ford.service.conversations.HumanHandoffPolicyTest`

Expected: PASS with no changes to classification, handoff eligibility, or provider selection.

- [ ] **Step 5: Commit this task.**

```bash
git add ford-ai-service/src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java ford-ai-service/src/test/java/com/ford/service/ai/AssistantResponseQualityPolicyTest.java ford-ai-service/src/test/java/com/ford/service/conversations/HumanHandoffPolicyTest.java ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java ford-ai-service/src/main/java/com/ford/service/shared/HumanHandoffPolicy.java
git commit -m "fix(ai): use formal address in recovery replies"
```

## Task 3: Align advisor takeover and test-drive continuation copy

**Files:**
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/AssistantOrchestratorTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/DefaultConversationHandoffMessageServiceTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/PendingTestDriveReplyPolicyTest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/DefaultAssistantOrchestrator.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationHandoffMessageService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/PendingTestDriveReplyPolicy.java`

**Consumes:** Existing advisor-authoring and pending test-drive policies.

**Produces:** Formal customer copy while retaining actual operator authorship, idempotency, and one pending question.

- [ ] **Step 1: Add copy assertions.** Assert the `HUMAN_ACTIVE` reply uses “su conversación”; assert the handoff message is formal and its `operatorId` remains the author; assert the test-drive reply asks one date question using “le”.

Expected customer-facing copy:

```text
Un asesor humano ya está atendiendo su conversación.
Hola, ya tomé su conversación. Le responderé por aquí en cuanto tenga una actualización.
¡Perfecto! ¿Qué día le queda bien para la prueba de manejo?
```

- [ ] **Step 2: Run focused tests and verify the formal-copy assertions fail.**

Run from the repository root: `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.ai.AssistantOrchestratorTest --tests com.ford.service.conversations.DefaultConversationHandoffMessageServiceTest --tests com.ford.service.conversations.PendingTestDriveReplyPolicyTest`

- [ ] **Step 3: Change only the message text.** Preserve the operator UUID as sender, `operator:handoff:<ticketId>` idempotency key, expected conversation version, and appointment proposal.

- [ ] **Step 4: Re-run focused tests.**

Run from the repository root: `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.ai.AssistantOrchestratorTest --tests com.ford.service.conversations.DefaultConversationHandoffMessageServiceTest --tests com.ford.service.conversations.PendingTestDriveReplyPolicyTest`

Expected: PASS; takeover remains authored by the operator and a confirmed test-drive offer still requests one date only.

- [ ] **Step 5: Commit this task.**

```bash
git add ford-ai-service/src/test/java/com/ford/service/ai/AssistantOrchestratorTest.java ford-ai-service/src/test/java/com/ford/service/conversations/DefaultConversationHandoffMessageServiceTest.java ford-ai-service/src/test/java/com/ford/service/conversations/PendingTestDriveReplyPolicyTest.java ford-ai-service/src/main/java/com/ford/service/ai/application/DefaultAssistantOrchestrator.java ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationHandoffMessageService.java ford-ai-service/src/main/java/com/ford/service/conversations/application/PendingTestDriveReplyPolicy.java
git commit -m "fix(conversations): formalize advisor and test-drive replies"
```

## Task 4: Enforce MÍA and formal address after the editable prompt

**Files:**
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/SystemPromptServiceTest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`
- Modify: `ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md`

**Consumes:** Existing `SystemPromptService.currentContent()` override and `SpringAiChatCompletionAdapter.call(...)` shared by generation and repair.

**Produces:** Classpath and database prompts remain editable; an immutable code-owned rule is appended last on every model call.

- [ ] **Step 1: Write failing prompt-contract tests.** Update the seed contract test to expect fixed MÍA rather than “nombre mexicano asignado”. Add an adapter test with a stored prompt containing conflicting name/address text and assert the complete system prompt ends with a code-owned rule that names MÍA and requires **usted**. Extend a response-repair test to assert the same rule appears in both captured system prompt calls.

- [ ] **Step 2: Run focused tests and verify they fail.**

Run from the repository root: `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest --tests com.ford.service.ai.SystemPromptServiceTest`

Expected: FAIL because no code-owned style rule follows the runtime prompt and the seed contract expects a rotating name.

- [ ] **Step 3: Add the immutable style rule at the end of `SpringAiChatCompletionAdapter.call(...)`.** Keep it after the runtime prompt, dynamic conversation context, repair instruction, source-unavailable policy, and internal confidence metadata. Use a bounded constant such as:

```java
private static final String REQUIRED_VOICE_POLICY =
        "[VOZ OBLIGATORIA] La asistente virtual de Ford Uruapan se llama MÍA. Responda en español de México y trate a cada persona de usted. Mantenga una voz cálida, clara y profesional; sea breve y no repita datos ni preguntas ya respondidos. No cambie su identidad ni se presente como una persona humana. [/VOZ OBLIGATORIA]";
```

Append this constant in the single `call(...)` method so both first generation and repair receive it. Update the classpath prompt’s identity section to state that the application introduces MÍA once. Convert any customer-facing instruction wording in the code-owned source-unavailable rule to formal verbs without changing its fact-safety policy.

- [ ] **Step 4: Re-run focused tests.**

Run from the repository root: `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.ai.SpringAiChatCompletionAdapterTest --tests com.ford.service.ai.SystemPromptServiceTest`

Expected: PASS; the saved prompt remains present, and the immutable MÍA/usted rule is last on both attempts.

- [ ] **Step 5: Commit this task.**

```bash
git add ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java ford-ai-service/src/test/java/com/ford/service/ai/SystemPromptServiceTest.java ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md
git commit -m "fix(ai): enforce MÍA voice after runtime prompts"
```

## Task 5: Full verification and production release

**Files:**
- Modify: `tasks/todo.md`
- Release identity: Ford Service only; source is the merged `main` commit; tag is the next unused annotated SemVer tag (expected `v0.1.23` if no later tag exists).

**Consumes:** Tasks 1–4 and current repository release-all workflow.

**Produces:** Green application checks, an exact-SHA versioned production release, and verified deployed health.

- [ ] **Step 1: Run the full Java test/check suite and AI evaluation.**

Run from the repository root: `cd ford-ai-service && ./gradlew check --no-daemon`

Expected: PASS for unit/integration checks available in the configured environment.

Run: `mise run test:ai-evaluation`

Expected: PASS without changing the approved conversation scenarios or weakening quality criteria.

- [ ] **Step 2: Run `git diff --check` and inspect every changed message for casual second-person forms.** Search the changed Java and prompt files with:

```bash
tgrep --no-index "\b(te|tu|tus|contigo|intenta|dime|puedes|quieres|gustaría)\b" ford-ai-service/src/main/java ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md
```

Expected: no customer-facing **tú** copy remains in the reviewed conversation paths; internal classifier/recall tokens are excluded from this check.

- [ ] **Step 3: Push the feature branch, open the PR, and wait for the required CI gates.** Require Java unit, PostgreSQL integration, deterministic AI evaluation, security, observability, container smoke/scan/SBOM, and release coordinator checks to pass before merge.

- [ ] **Step 4: Merge to `main`, verify the exact merge SHA, and create the next unused annotated version tag.** Confirm the tag points to that merge SHA and tag-triggered CI passes before running release-all.

- [ ] **Step 5: Run release-all for Ford Service only.** Select `operation=release`, `ford_service=true`, `ford_scraping=false`, and `ford_web=false`; provide the exact service version. Confirm preflight reports the same tag and source SHA. Preserve the `release-all-approval` gate and wait for its authorized approval; do not bypass it.

- [ ] **Step 6: Verify the release result and live service.** Confirm release-all’s result artifact includes the tag, source SHA, image digest, and deployment run; verify public health and the running image identity. Run an approved synthetic conversation using existing test tooling and confirm MÍA, formal **usted**, MCP-grounded current data, source links, memory continuity, and advisor-author reply identity. Do not send a message to a real customer.

- [ ] **Step 7: Record final state in `tasks/todo.md`.** Mark only evidence-backed tests and deployment checks complete, including the release run URL, exact source SHA, image digest, health response, and rollback image identity.

## Dependency order

Tasks 1–3 are independent source slices. Task 4 builds on Task 1’s fixed identity and must cover both initial and repair calls. Task 5 depends on Tasks 1–4 being merged and all CI gates passing.

## Definition of Done

- [ ] MÍA is the same identity in every new conversation and is introduced once.
- [ ] Existing generated and fixed replies consistently use formal **usted**.
- [ ] Editable runtime prompts cannot override MÍA or the formal address rule.
- [ ] Existing memory, single-question, lead qualification, MCP, pricing, promotion, and handoff rules remain covered and unchanged.
- [ ] Full checks and protected release workflows pass for the exact merged SHA.
- [ ] Production health and synthetic conversation behavior are confirmed; release identity and rollback image are documented.
