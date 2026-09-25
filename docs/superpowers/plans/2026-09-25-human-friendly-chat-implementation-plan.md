# Human-Friendly, Model-Agnostic Ford Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Ford chat model-first, memory-aware, resistant to repetition/tool leakage, and verifiable through a cheap direct OpenRouter multi-turn evaluator.

**Architecture:** Keep deterministic code as a safety and read-only authorization boundary. Remove the lexical normal-conversation gate, add a bounded response-quality/repair layer around provider output, and add an opt-in Node evaluator that simulates Ford tool results while exercising real OpenRouter conversations.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring AI 2.0.1, JUnit 5, AssertJ/Mockito, Node.js built-in `fetch`, JSONL fixtures, Mise.

## Global Constraints

- Do not expose or print API keys, MCP tokens, deployment secrets, phone numbers, or customer identifiers.
- Preserve unrelated edits in `ApiExceptionHandler.java` and `ApiExceptionHandlerTest.java`.
- Only read-only Ford tools may be exposed to the model.
- Repair attempts are bounded to one per response.
- The offline Gradle suite must not require OpenRouter credentials.
- The evaluator must use fixture tool data and must not send customer-facing outbound messages.

### Task 1: Remove lexical blocking from normal conversation

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantScopePolicy.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/application/AssistantScopePolicyTest.java`

**Interfaces:** `SpringAiAssistantService.respond(AiExecutionContext, String)` remains the public entry point. The response must reach `AssistantOrchestrator` for any non-blank message unless a separate safety policy rejects it.

- [ ] **Step 1: Write the failing tests.** Add tests proving a normal conversational message such as `"¿Me puedes explicar eso mejor?"` with Ford memory calls the orchestrator, and a neutral message without automotive keywords also reaches the model for clarification. Keep explicit blank-input validation unchanged.
- [ ] **Step 2: Run the focused tests and verify the expected failure.** Run `./gradlew test --tests com.ford.service.ai.SpringAiAssistantServiceTest --tests com.ford.service.ai.application.AssistantScopePolicyTest --no-daemon --no-configuration-cache`; the new tests must fail because the lexical gate returns `scope-guard`.
- [ ] **Step 3: Implement the smallest runtime change.** Replace the pre-orchestrator lexical allow-list branch with a safety-only check that rejects only blank input (already validated) and explicitly prohibited credential/secret requests; otherwise call the orchestrator. Keep `scopeResponse()` only for the explicit safety response or remove it if no caller remains.
- [ ] **Step 4: Run focused and existing policy tests.** Confirm the new contextual tests pass and unrelated-scope regression tests still reject clearly unrelated requests only if the safety policy, rather than automotive keyword absence, is the reason.
- [ ] **Step 5: Commit.** Use `git add` only for the scope implementation and its tests, then commit `fix(ai): let the model handle normal conversation`.

### Task 2: Add a bounded response-quality contract

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/AssistantResponseQualityPolicyTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`

**Interfaces:** Create `AssistantResponseQualityPolicy.evaluate(String currentMessage, String memory, String response)` returning a value object with `accepted()` and `reason()`. Add `repairInstruction(String reason)` and `recoveryMessage(String reason)`. The adapter calls the provider once, repairs at most once, and then returns the recovery message if the second result is invalid.

- [ ] **Step 1: Write failing quality-policy tests.** Cover blank output, raw JSON/tool arguments, internal tool-name leakage, exact duplicate assistant output from memory, accepted natural Spanish, and a response that answers the current question. Assert the exact reason code for each rejected case.
- [ ] **Step 2: Run the policy tests and verify failure.** Run `./gradlew test --tests com.ford.service.ai.AssistantResponseQualityPolicyTest --no-daemon --no-configuration-cache`; expect compilation failure because the policy does not exist.
- [ ] **Step 3: Implement the pure policy.** Normalize whitespace/case, extract `- asistente:` lines from memory, compare exact normalized answers plus token-overlap threshold, reject fenced/raw JSON and known internal tool/provider markers, and accept concise ordinary text. Do not parse or redact arbitrary customer content.
- [ ] **Step 4: Run policy tests and verify green.** Re-run the focused test until all cases pass.
- [ ] **Step 5: Write the adapter repair test.** Configure the mocked `ChatClient` to return a leaked JSON response first and a natural response second; assert `call()` is invoked twice and the natural response is returned. Add a second test returning two invalid responses and assert one recovery response plus exactly two calls.
- [ ] **Step 6: Implement the bounded adapter loop.** Keep the existing tool and memory construction. After each call, run the policy; on the first rejection create one repair request with the same current user message, system prompt, memory, and read-only tools, adding only the repair instruction. Return `recoveryMessage` after the second rejection. Never recurse.
- [ ] **Step 7: Run adapter and full AI tests.** Run the focused adapter tests, then `./gradlew test --no-daemon --no-configuration-cache`.
- [ ] **Step 8: Commit.** Commit `feat(ai): enforce human-friendly response contract`.

### Task 3: Rewrite the prompt around current-turn behavior

**Files:**
- Modify: `ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SystemPromptServiceTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`

**Interfaces:** Preserve `SystemPromptService.currentContent()` and the memory/decision delimiters.

- [ ] **Step 1: Add prompt regression assertions.** Require explicit latest-message priority, no generic scope response for contextual Ford questions, no repeated answer, no raw tool payload, one useful question maximum, and transparent source-failure behavior.
- [ ] **Step 2: Run the prompt tests and verify the new assertions fail.** Run the two focused test classes.
- [ ] **Step 3: Rewrite the prompt sections.** Keep factual agency details and tool source-of-truth rules, but reorganize into priority order: current turn, memory, source lookup, answer composition, tone, safety, and fallback. Remove wording that encourages restarting discovery after an established context.
- [ ] **Step 4: Run prompt and full tests.** Confirm all prompt assertions and the entire Gradle suite pass.
- [ ] **Step 5: Commit.** Commit `prompt(ai): prioritize natural contextual replies`.

### Task 4: Add the direct OpenRouter multi-turn evaluator

**Files:**
- Create: `scripts/ai/openrouter-chat-eval.mjs`
- Create: `scripts/ai/fixtures/human-friendly-conversations.json`
- Create: `scripts/ai/openrouter-chat-eval.test.mjs`
- Modify: `ford-ai-service/.mise/tasks/test/ai-evaluation`

**Interfaces:** The evaluator exports `runConversation({ fetchImpl, baseUrl, apiKey, model, scenario })` and `evaluateTranscript(transcript, scenario)`. The CLI accepts `--models`, `--fixture`, `--output`, and `--max-tool-rounds`; it reads `OPENROUTER_API_KEY` and never prints it.

- [ ] **Step 1: Write evaluator unit tests.** Use a fake fetch implementation to prove tool calls are appended correctly, fixture tools return data, the conversation stops after a final text response, the maximum tool round is enforced, duplicate answers fail, and raw tool payloads fail.
- [ ] **Step 2: Run Node tests and verify failure.** Run `node --test scripts/ai/openrouter-chat-eval.test.mjs`; expect missing-module/undefined-export failures.
- [ ] **Step 3: Implement the fixture dispatcher and conversation loop.** Send OpenAI-compatible `messages`, tool schemas, model, low temperature, and bounded token count to `/chat/completions`. Append assistant tool-call messages and `tool` results, dispatch only fixture read-only tools, and stop after the model returns text or the configured round limit.
- [ ] **Step 4: Add fixture scenarios and rubric.** Include multi-turn Spanish cases for greeting, Raptor fuel economy, promotions, comparison, contextual follow-up, out-of-scope, source failure, repeated output, and leaked JSON. Each expected assertion must be a concrete substring/negative marker, not a vague quality claim.
- [ ] **Step 5: Implement JSON/Markdown reporting.** Record model, scenario, turn, tool names, response, latency, and pass/fail without request headers or secrets. Exit nonzero only when credentials exist and a scenario fails; otherwise print a clear credentials-skipped result.
- [ ] **Step 6: Add a Mise task wrapper.** Keep the existing deterministic Gradle evaluation task intact and add an opt-in direct mode such as `mise run test:ai-evaluation -- --live --models openrouter/free`; do not make CI depend on network credentials.
- [ ] **Step 7: Run static and unit checks.** Run `node --test scripts/ai/openrouter-chat-eval.test.mjs scripts/mise-contract.test.mjs` and `git diff --check`.
- [ ] **Step 8: Commit.** Commit `test(ai): add cheap OpenRouter conversation evaluation`.

### Task 5: Run the real model loop and verify the deployed path

**Files:**
- Modify only the ignored local deployment config when selecting the already requested image tag; never commit secrets.
- Output: `build/reports/openrouter-chat-eval/` (ignored/generated)

- [ ] **Step 1: Run the offline test gates.** Run the full Gradle suite and Node evaluator unit tests.
- [ ] **Step 2: Run direct OpenRouter evaluation.** Use `OPENROUTER_API_KEY` from the existing ignored environment, run `openrouter/free` plus two available tool-capable OpenRouter models, and inspect every failed transcript before changing code.
- [ ] **Step 3: Fix only evidenced failures with a new red test.** For each failure, add a regression test to the quality policy, prompt contract, or evaluator before changing production code; rerun the narrow test, then the full suite.
- [ ] **Step 4: Build and publish through Mise.** Publish a new ARM64 GHCR tag using `mise run release:publish`, update only ignored `deployment/config/oci.env`, and deploy with `mise run deploy:app`.
- [ ] **Step 5: Verify live runtime.** Run health, deployment status, MCP handshake/log checks, Meta verification, and a controlled assistant smoke request that does not trigger an uncontrolled outbound delivery.
- [ ] **Step 6: Commit final tracked changes and report evidence.** Preserve unrelated worktree changes and include the image tag, test commands, model results, and remaining provider-specific limitations.
