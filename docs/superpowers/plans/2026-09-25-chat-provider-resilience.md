# Chat Provider Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover from provider throttling and transient failures without unsafe fallbacks, excessive retries, or duplicate customer messages.

**Architecture:** Keep provider choice in `ModelProviderPolicy` and calls in `ProviderRoutingChatCompletionPort`. Expose eligible providers in policy order, classify errors once, and let a bounded routing decision select a retry, safe fallback, or customer-safe failure. Use Spring AI 2.0.1 retry configuration deliberately to avoid nested retry storms.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring AI 2.0.1, OpenRouter via `OpenAiChatModel`, Ollama, JUnit 5, Micrometer.

## Global Constraints

- Never route confirmed sensitive content to a cloud provider.
- A fallback provider must support the same required tools and request capabilities.
- Retry only classified transient failures; honor valid `Retry-After` without unbounded waiting.
- Never retry a completed outbound send because a model call failed.
- Do not add a resilience dependency if current Spring AI/provider support is sufficient.

---

### Task 1: Make provider failures typed and policy-safe

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/AiProviderFailureDiagnostics.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/ProviderFailure.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/ai/ProviderFailureTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiAssistantServiceTest.java`

**Interfaces:**
- Produce: `ProviderFailure(String category, Integer status, Duration retryAfter)` with categories `rate_limited`, `provider_unavailable`, `network`, `authentication`, `invalid_request`, and `provider_error`.
- Preserve: `AiProviderFailureDiagnostics.describe(Throwable, boolean)` as the credential-safe logging formatter.

- [ ] **Step 1: Add failing classification tests.** Cover HTTP 429 with numeric `Retry-After`, HTTP 429 with invalid/missing header, HTTP 401, HTTP 503, timeout, and an unknown runtime exception. Assert no response body, prompt, key, or token is included in the result.
- [ ] **Step 2: Run the focused test.** Run `./gradlew test --tests com.ford.service.ai.ProviderFailureTest`. Expected: FAIL because there is no typed failure classification API.
- [ ] **Step 3: Implement classification using existing exception traversal.** Extract status/header/category once from the OpenAI SDK exception already handled by `AiProviderFailureDiagnostics`; parse `Retry-After` as seconds, reject negative/overflow values, and preserve the existing safe log format.
- [ ] **Step 4: Run classifier and existing diagnostics tests.** Run `./gradlew test --tests com.ford.service.ai.ProviderFailureTest --tests com.ford.service.ai.SpringAiAssistantServiceTest`. Expected: PASS with existing provider log metadata unchanged and no secret/body leakage.
- [ ] **Step 5: Commit the typed failure model.** Commit only the listed source/tests with `feat(ai): classify provider failures for bounded recovery`.

### Task 2: Route through only eligible fallback providers

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/ModelProviderPolicy.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/DefaultModelProviderPolicy.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/ProviderRoutingChatCompletionPort.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/ModelProviderPolicyTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/ProviderRoutingChatCompletionPortTest.java`

**Interfaces:**
- Add: `List<ProviderDescriptor> candidates(AiExecutionContext context, ModelRequirements requirements)` to `ModelProviderPolicy`.
- Keep: `select(context, requirements)` as the first eligible candidate for existing consumers.
- Produce: routing that tries candidates in policy order only after a retryable completion failure.

- [ ] **Step 1: Add failing provider-policy tests.** Assert candidates are healthy, meet tool/structured-output requirements, exclude all cloud providers for confirmed sensitive content, and preserve cloud-first ordering for eligible nonsensitive calls.
- [ ] **Step 2: Add failing routing tests.** Assert a rate-limited first provider falls through to the next eligible provider; assert authentication/invalid-request failures do not fall through; assert no candidate produces a safe failure.
- [ ] **Step 3: Run focused tests.** Run `./gradlew test --tests com.ford.service.ai.ModelProviderPolicyTest --tests com.ford.service.ai.ProviderRoutingChatCompletionPortTest`. Expected: new candidate and fallback assertions FAIL.
- [ ] **Step 4: Implement policy candidates and bounded fallback.** Centralize capability/privacy filters in `DefaultModelProviderPolicy`; make `select` return `candidates(...).getFirst()`; have `ProviderRoutingChatCompletionPort` resolve each returned ID to a configured port and continue only for `rate_limited`, `provider_unavailable`, or `network` categories.
- [ ] **Step 5: Rerun focused tests.** Expected: all prior cloud/local routing rules hold and ineligible providers are never called.
- [ ] **Step 6: Commit provider routing.** Commit only these source/tests with `feat(ai): add policy-approved provider fallback`.

### Task 3: Bound retries and honor provider throttling

**Files:**
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/AiProperties.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/ProviderRoutingChatCompletionPort.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiConfigurationSliceTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/ProviderRoutingChatCompletionPortTest.java`

**Interfaces:**
- Configuration contract: total same-provider attempts default to `2`; maximum wait for `Retry-After` default to `PT3S`; delays above the request budget skip same-provider retry and proceed to an eligible fallback.
- Keep the retry delay source injectable so unit tests use a recording fake and never sleep.

- [ ] **Step 1: Add failing retry-policy tests.** Verify one transient retry maximum, `Retry-After` honored only up to `PT3S`, longer delays skip same-provider retry, 429 does not loop on `openrouter/free`, and non-retryable errors do not retry.
- [ ] **Step 2: Run focused tests.** Run `./gradlew test --tests com.ford.service.ai.ProviderRoutingChatCompletionPortTest --tests com.ford.service.ai.SpringAiConfigurationSliceTest`. Expected: FAIL on retry behavior/config binding.
- [ ] **Step 3: Configure Spring AI 2.0.1 retry defaults explicitly.** Set `spring.ai.retry.max-attempts: 1` and `spring.ai.openai.max-retries: 0` so the routing layer owns one bounded policy instead of compounding framework defaults; bind the app-level max attempts (`2`) and max delay (`PT3S`) with validated `AiProperties`.
- [ ] **Step 4: Implement deterministic delay handling.** Use the typed `ProviderFailure.retryAfter`, clamp it to the configured budget, inject a delay strategy, and then retry only if the category is retryable and attempts remain. After a 429 with no bounded retry available, continue to an eligible fallback without replaying any delivery operation.
- [ ] **Step 5: Run focused tests and configuration validation.** Expected: attempt counts never exceed 2 per provider and tests complete without real waiting.
- [ ] **Step 6: Commit bounded retry behavior.** Commit only these source/tests with `fix(ai): bound provider retries and honor retry-after`.

## Dependencies and verification

- Task 1 precedes Tasks 2–3; Task 2 precedes fallback use in Task 3.
- Final gate: `./gradlew test --tests 'com.ford.service.ai.*'` then `./gradlew check` from `ford-ai-service`.
- Use the Spring AI 2.0.1 properties supported by this repository; the official reference documents `spring.ai.retry.max-attempts`, HTTP-code handling, backoff, and OpenAI client retry settings: [Spring AI OpenAI Chat reference](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html).
- Recheck OpenRouter runtime settings after deployment; do not print or copy `.env` values into logs, tests, or docs.
