# Chat Provider Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configure an ordered, testable set of Spring AI-compatible chat providers and reproduce its settings on Oracle through the existing Mise/Ansible deployment flow.

**Architecture:** Keep provider choice and retry behavior in the existing `ProviderRoutingChatCompletionPort` and `ModelProviderPolicy`. Build an isolated Spring AI OpenAI-compatible `ChatModel` per allowlisted provider with explicit config and credentials; keep Oracle credentials in the private deployment env and let Ansible render the app env. Terraform continues to own OCI infrastructure and receives no AI provider secrets.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring AI 2.0.1, JUnit 5, Mise shell tasks, Ansible, Terraform (no provider-secret changes).

## Global Constraints

- Preserve existing OpenAI primary and Ollama behavior.
- Fallback order defaults to `cloudflare,groq,gemini,openrouter` after configured OpenAI.
- New provider integrations use Spring AI 2.0.1's supported OpenAI-compatible API; verify APIs against the pinned BOM before implementation.
- Providers remain disabled until explicitly enabled after their Ford evals pass.
- `openrouter/free` is testing-only and must not enter the normal Oracle fallback chain.
- Only rate limits, provider unavailability, and network failures/timeouts may trigger fallback; attempt each eligible provider at most once and cap the chain at six providers (OpenAI, four cloud fallbacks, and optional Ollama).
- Respect Retry-After only as a bounded delay before the next provider; if the value exceeds the wait budget, skip the delay and continue the fallback chain.
- Confirmed sensitive conversations stay off every cloud provider; tool-calling and structured-output requirements remain enforced.
- Never log or commit API keys. Terraform variables, plans, and state must not contain provider credentials.
- Preserve existing user work in shared config files; inspect each file diff before editing, especially `app.env.j2`, `remote.sh`, `application.yml`, `.env` inputs, and related tests.

---

## File Map

| File | Responsibility |
|---|---|
| `ford-ai-service/src/main/java/com/ford/service/ai/configuration/ChatProviderProperties.java` | Validated, allowlisted provider endpoints, models, enablement, and fallback order. |
| `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiProviderConfiguration.java` | Compose ordered provider descriptors for cloud and local models. |
| `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java` | Compose eligible Spring AI model adapters for the application port. |
| `ford-ai-service/src/main/resources/application.yml` | Safe defaults and env bindings for provider catalog. |
| `ford-ai-service/src/test/java/com/ford/service/ai/configuration/ChatProviderPropertiesTest.java` | Config binding, validation, and ordered-list behavior. |
| `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiConfigurationSliceTest.java` | Assistant config slice with no configured providers. |
| `ford-ai-service/src/test/java/com/ford/service/ai/adapter/out/springai/SpringAiProviderConfigurationTest.java` | Ordered descriptors for provider models and configured IDs. |
| `ford-ai-service/src/test/java/com/ford/service/ai/ModelProviderPolicyTest.java` | Ordering, capabilities, and privacy behavior. |
| `ford-ai-service/src/test/java/com/ford/service/ai/ProviderRoutingChatCompletionPortTest.java` | Retryable fallback and fail-closed error behavior. |
| `deployment/config/oci.env.example` | Document Oracle provider flags, endpoints/models, order, and credential names without values. |
| `ford-ai-service/.env.example`, `ford-ai-service/.env.prod.example` | Document local and production-like provider variables. |
| `ford-ai-service/.mise/tasks/deploy/secrets` | Adopt/prompt for provider keys without displaying them. |
| `ford-ai-service/.mise/tasks/env/list` | Show provider-key presence only. |
| `ford-ai-service/.mise/lib/remote.sh` | Safely propagate provider config and secrets to Ansible inventory. |
| `deployment/ansible/roles/app/templates/app.env.j2` | Render provider env values into app `.env`, mode `0600` via current task. |
| `ford-ai-service/.mise/tasks/deploy/check` and `scripts/operations-contract.test.mjs` | Safe deployment preflight and contract coverage. |
| `ford-ai-service/README.md`, `ford-ai-service/docs/operations.md`, `deployment/terraform/README.md` | Document provider enablement, secrets, eval gate, and Terraform/Ansible boundary. |

## Task 1: Specify and validate provider settings

**Files:** create `ChatProviderProperties.java` and its unit test; update `application.yml` and add `SpringAiProviderConfigurationTest.java`. `AiProperties` remains the existing legacy/OpenAI/Ollama property holder.

**Configuration contract:** Bind `ford.ai.providers.catalog` entries for `cloudflare`, `groq`, `gemini`, and `openrouter`. Each entry contains `enabled`, `baseUrl`, `model`, `apiKey`, and tool/structured-output capability verification flags. Bind `ford.ai.providers.fallback-order` as a list defaulting to `cloudflare,groq,gemini,openrouter`. The configured cloud primary remains separately controlled; Ollama remains a separately controlled local provider.

- [x] Add failing tests for the default provider order, missing-key state, unknown provider ID rejection, duplicate order IDs, blank enabled endpoint/model rejection, and safe provider-status output that never includes the API key.
- [x] Run `./gradlew test --tests com.ford.service.ai.configuration.ChatProviderPropertiesTest`; expected new assertions fail because the binding type does not exist.
- [x] Implement Spring-bound, validated allowlisted provider settings with the standard Spring Boot configuration-properties and Jakarta validation APIs supported by the current build.
- [x] Add YAML env defaults: Groq `https://api.groq.com/openai/v1`; Gemini `https://generativelanguage.googleapis.com/v1beta/openai/`; Cloudflare account-specific `https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/v1`; OpenRouter `https://openrouter.ai/api/v1`. All fallback providers default disabled and all key values default empty.
- [x] Run the focused properties test and `./gradlew test --tests com.ford.service.ai.configuration.ChatProviderPropertiesTest`; expected PASS.
- [ ] Commit only this task's config type, tests, and YAML changes as `feat(ai): bind ordered chat provider configuration`.

## Task 2: Build one eligible Spring AI adapter per provider

**Files:** modify `SpringAiProviderConfiguration.java`, `SpringAiConfiguration.java`, `ChatProviderProperties.java` only if a construction detail belongs there, and add focused configuration tests under `ford-ai-service/src/test/java/com/ford/service/ai/adapter/out/springai/`.

**Interfaces:** consume validated `ChatProviderProperties`; produce `ProviderChatCompletionPort` entries keyed by allowlisted provider ID and one `ModelProviderPolicy` whose descriptors use the same IDs/order and existing capability contract. The current `SpringAiProviderConfiguration` owns `ModelProviderPolicy`; `SpringAiConfiguration` owns `ChatCompletionPort`. Verify exact `OpenAiApi`/`OpenAiChatModel` builder signatures in Spring AI 2.0.1 source/Javadocs before coding.

- [x] Add provider-policy configuration tests for ordered/eligible descriptors, a missing-key omission test, and an adapter-factory test for its configured endpoint/model and disabled nested retries.
- [x] Test configured descriptor order and routing order while preserving the existing primary/local policy behavior.
- [x] Run `./gradlew test --tests com.ford.service.ai.adapter.out.springai.SpringAiProviderConfigurationTest`; expected new adapter assertions fail.
- [x] Construct provider-specific Spring AI OpenAI-compatible models at the composition root using the pinned supported builders; do not add provider-specific starters unless the generic OpenAI-compatible path demonstrably fails.
- [x] Register adapters only for known provider IDs, enabled flags, nonblank keys, and valid settings. Avoid a single injected `OpenAiChatModel` ambiguity once multiple instances exist; inject/build the explicitly keyed provider set.
- [x] Preserve OpenAI-compatible error classification, zero nested SDK retries, tools, structured-output settings, observations, prompt, and scope advisor for each adapter.
- [x] Run `./gradlew test --tests com.ford.service.ai.adapter.out.springai.SpringAiProviderConfigurationTest`; expected PASS. Run `SpringAiConfigurationSliceTest` to confirm the app still starts without optional providers.
- [ ] Commit this task as `feat(ai): compose configured chat provider adapters`.

## Task 3: Enforce ordered capability-safe fallback

**Files:** modify `DefaultModelProviderPolicy.java`, `ProviderRoutingChatCompletionPort.java` only if needed, and their existing focused tests.

- [x] Add failing tests that preserve configured cloud order, omit disabled/unhealthy providers, reject providers without tools or structured outputs, and keep all cloud providers excluded for sensitive turns.
- [x] Add routing assertions for a retryable failure chain across ordered providers and no fallback on 400/401 authentication/invalid-request errors; existing policy tests cover capabilities and privacy.
- [x] Run `./gradlew test --tests com.ford.service.ai.ModelProviderPolicyTest --tests com.ford.service.ai.ProviderRoutingChatCompletionPortTest`; expected new order/eligible candidate assertions fail.
- [x] Update candidate selection to keep OpenAI primary, then follow the configured fallback order, then preserve current local-provider fallback semantics. Set the attempt cap default and maximum to six so every allowlisted candidate can be tried once.
- [x] When a retryable failure has Retry-After within the configured wait budget, apply that bounded delay before the next provider. When Retry-After exceeds the budget, skip the delay and still try the next provider. Rename the attempt observation outcome from `retry` to `fallback` when moving to another provider.
- [x] Keep `openrouter/free` ineligible for normal Oracle config; allow it only behind an explicit evaluation-only provider config that cannot be mistaken for the production OpenRouter model.
- [x] Run both focused tests; expected PASS without real network requests. Use an injected recording delay so bounded-delay assertions do not sleep.
- [ ] Commit this task as `feat(ai): route through ordered eligible providers`.

## Task 4: Propagate secrets/config through Mise and Ansible

**Files:** update `deploy/secrets`, `env/list`, `remote.sh`, `deploy/check`, Oracle/local env examples, `app.env.j2`, and operations contract tests. Review existing diffs first and preserve their behavior.

**Credential names:** `GROQ_API_KEY`, `GEMINI_API_KEY`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `OPENROUTER_API_KEY`; retain `OPENAI_API_KEY`.

- [x] Add failing operations contract tests that assert each provider setting appears in the env example, is carried into inventory, and is rendered into app env; assert Terraform variable/plan templates contain no provider API key values.
- [x] Add a shell test for `deploy:secrets` that adopts exported provider keys without outputting values and retains the current 0600 config permissions.
- [x] Run `node --test scripts/operations-contract.test.mjs`, including isolated shell tests for secret adoption and safe preflight; expected provider propagation assertions fail.
- [x] Extend `deploy:secrets` adoption loop to the four provider keys and Cloudflare Account ID; preserve existing `OPENAI_API_KEY` handling and do not prompt for optional providers.
- [x] Extend `env/list` to show set/missing only. Extend safe preflight to display enabled provider IDs and whether their required key/account identifier is set; never expose values.
- [x] Carry provider flags/order/base URLs/model IDs and credentials through the existing local config → generated Ansible inventory → `app.env.j2` path using YAML-safe quoting. Keep Ansible secret-bearing tasks `no_log` and app env mode `0600`.
- [x] Add defaults for providers disabled, fallback order as approved, and OpenRouter evaluation-only. Keep all credentials out of `terraform.tfvars`, generated Terraform vars, Terraform variables, state, and plan.
- [x] Run `node --test scripts/operations-contract.test.mjs` and focused Mise shell tests; expected PASS.
- [ ] Commit this task as `feat(deploy): propagate chat provider configuration securely`.

## Task 5: Document run/evaluation and provider enablement

**Files:** update `ford-ai-service/README.md`, `ford-ai-service/docs/operations.md`, `deployment/terraform/README.md`, and provider eval docs/scripts only as needed.

- [x] Add documentation assertions or contract tests for local env variable names, how to transfer exports into `deployment/config/oci.env` via `mise run deploy:secrets`, Oracle `mise run deploy:check`/`deploy:app`, provider toggle/order, and the `openrouter/free` testing-only rule.
- [x] Document that API credentials are optional/disabled by default, `deploy:secrets` sources local environment values into ignored 0600 config, and Ansible supplies runtime env on emme-1.
- [x] Document Terraform’s boundary: OCI infrastructure only; provider API keys are not Terraform inputs/state.
- [x] Document the eval gate: enable each provider only after the ten multistep Ford lead cases plus provider capability checks pass; retain incomplete runs/raw traces and report deterministic policy and Ragas/LLM scores separately.
- [x] Run relevant docs/operations contract tests; expected PASS.
- [ ] Commit docs as `docs(ai): document provider fallback operations`.

## Task 6: Verify without external provider calls or deployment

- [x] Run focused Java tests for provider properties, Spring configuration, policy, routing, diagnostics, and conversation/eval contracts.
- [x] Run `./gradlew test`, then `./gradlew check` in `ford-ai-service`; preserve and diagnose any failure before broadening edits.
- [x] Run `node --test scripts/operations-contract.test.mjs` and shell helpers through its temporary-directory integration tests.
- [x] Run the existing mocked Ford app-level evals. Keep raw traces/incomplete cases, and report deterministic policy results separately from LLM/Ragas scores.
- [ ] Run `mise run deploy:check` only if the private local deployment configuration is available; output must show presence only. Do not run `deploy:app`, Terraform apply, real provider requests, or any customer/advisor action in verification.
- [x] Inspect the final diff and staged paths; confirm no ignored secret file, generated inventory, token, or user work was staged.
- [x] Record commands/results and any provider-specific eval failures in the ops doc.

## Dependencies

Task 1 → Task 2 → Task 3. Task 4 can be implemented after Task 1's environment variable contract is fixed. Task 5 documents the finalized config. Task 6 runs after all changes.

## Definition of Done

- All provider settings bind and validate with secrets absent by default.
- Existing OpenAI and Ollama behavior remains supported.
- Configured fallback order, capability checks, sensitive-data exclusion, and retry rules pass deterministic tests.
- Mise/Ansible reproducibly carry all provider config and secrets without displaying values or placing them in Terraform state.
- Each provider's enablement/eval gate and Oracle operation are documented.
- Focused tests, app evals, full tests/build/check, and deployment contract tests pass or have exact unresolved failures documented.

## Current execution status (2026-10-06)

Provider code, deployment templates, documentation, deterministic evaluations, and build checks are complete. The full `mise run test:check` gate passed with `DOCKER_HOST` scoped to the already-running default Colima socket and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. Ansible syntax check, app build, provider-focused tests, the ten-flow handoff fixture, and 18 operations-contract tests passed.

Not run: `mise run deploy:check`, deployment, real provider requests, and live LLM/Ragas evaluation. The current command environment did not expose provider credentials; `mise run env:list` showed them missing, and only Groq/Gemini variable names were found in `.zshrc`. No secret values were read. All providers remain disabled and capability flags remain false by default.

No implementation commits were created because the shared checkout contains unrelated user changes. Do not stage those changes as part of this work.
