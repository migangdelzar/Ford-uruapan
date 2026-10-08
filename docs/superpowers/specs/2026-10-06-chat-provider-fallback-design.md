# Configurable Chat Provider Fallback Design

**Date:** 2026-10-06
**Status:** Approved design; awaiting document review
**Scope:** Ford AI chat provider configuration and Oracle deployment replication

## Summary

Add a reproducible, ordered set of cloud chat provider adapters for Groq, Gemini, Cloudflare Workers AI, and OpenRouter. Keep configured OpenAI as the first-choice cloud provider; use Groq, Gemini, Cloudflare Workers AI, and OpenRouter as ordered fallbacks. Use the existing Spring AI OpenAI-compatible adapter path for each provider, preserving the existing application routing and failure policy. Oracle deployment passes provider settings through Ansible-rendered app environment files; Terraform continues to manage OCI infrastructure only and never receives provider API keys.

The approved initial order is `cloudflare,groq,gemini,openrouter`. A provider becomes eligible only when its non-secret settings and API key are present and that provider/model has been enabled after the Ford evals pass. Missing optional credentials omit that provider and are surfaced by safe diagnostics. The variable-model `openrouter/free` target remains testing-only.

## Current Context

- `SpringAiConfiguration` currently builds the cloud adapter from a single auto-configured `OpenAiChatModel` and optionally adds Ollama.
- `ProviderRoutingChatCompletionPort` already owns bounded retry/fallback behavior, and `ModelProviderPolicy` filters candidates by health, capabilities, and sensitivity.
- Cloud providers must satisfy tool-calling and structured-output requirements. Confirmed sensitive conversations must continue to exclude cloud providers.
- Oracle Ansible renders `deployment/ansible/roles/app/templates/app.env.j2` to the app host with mode `0600`.
- `deployment/config/oci.env` is a private local deployment input. Terraform variables/state are not an application-secret transport.
- The current worktree contains unrelated user modifications, including the Ansible app template and Spring config. Implementation must preserve and reconcile these edits rather than overwrite them.

## Approved Design

### Provider adapter construction

Create a validated Spring-bound provider configuration for a small allowlisted set of IDs: `openai`, `groq`, `gemini`, `cloudflare`, and `openrouter`. Each entry carries an enabled flag, OpenAI-compatible base URL, model identifier, API key, and capability declaration. Never log API-key values. Build one `OpenAiChatModel` and one `SpringAiChatCompletionAdapter` for every eligible entry, using the provider ID as the routing ID and retaining the configured model identifier in the descriptor.

Use Spring AI 2.0.1's OpenAI-compatible client implementation. The official providers expose compatible chat-completions endpoints: Groq uses `https://api.groq.com/openai/v1`; Gemini uses `https://generativelanguage.googleapis.com/v1beta/openai/`; Cloudflare uses an account-specific `/ai/v1` base URL; OpenRouter uses `https://openrouter.ai/api/v1`. The implementation must verify the exact Spring AI 2.0.1 builder/API surface before coding and add adapter contract tests for base URL, model, and authorization configuration without printing credentials.

Keep existing OpenAI and Ollama behavior compatible. Treat the configured provider order as the explicit order for cloud candidates. Do not rely on Spring bean `@Order` annotations for request-time failover: the application routing policy already owns that decision. Provider IDs absent from the allowlist or invalid configuration must not become arbitrary outbound URLs.

### Configuration and Oracle deployment

Bind provider entries through Spring Boot configuration properties. Provide non-secret defaults for endpoint and model, and API-key environment-variable names documented for each provider: `OPENAI_API_KEY`, `GROQ_API_KEY`, `GEMINI_API_KEY`, `CLOUDFLARE_API_TOKEN`, and `OPENROUTER_API_KEY`; Cloudflare also needs `CLOUDFLARE_ACCOUNT_ID`. Keep new fallback providers disabled unless explicitly enabled after evaluation. Render required values through Ansible into the existing app env file at mode `0600`; preserve `no_log` on secret-bearing tasks and do not print rendered environment contents.

Add names/placeholders to `deployment/config/oci.env.example` and the local app environment examples. The private `deployment/config/oci.env` remains the operator source of truth for Oracle deployment; local `.zshrc` exports do not automatically reach Ansible or the remote app. Update Mise preflight so it can report configured provider IDs with absent keys, without exposing values. Keep Terraform independent of API credentials and do not add them to Terraform variables, plans, or state.

### Routing and provider enablement

Keep OpenAI first when configured. Initial approved fallback preference: Groq, Gemini, Cloudflare Workers AI, OpenRouter. Make fallback order configurable as a validated provider-ID list while retaining that initial default. Candidate selection must also honor an explicit provider-enabled flag and model capabilities. Cloud candidates must declare tool calling and structured-output compatibility before being eligible. Existing privacy policy excluding cloud providers for sensitive content remains unchanged.

Missing API keys disable only their optional provider and surface the missing provider ID in sanitized startup/preflight diagnostics. Invalid endpoint or model configuration for an enabled provider should fail configuration validation rather than silently route to an unintended service. Only enable a provider after its model passes the Ford eval suite. `openrouter/free` is allowed for explicit evaluation, but not in the normal Oracle production fallback order.

Fallback continues to occur only for the existing retryable failure classes: rate limits, provider unavailability, and network failures/timeouts. Authentication, permission, invalid-request, and model-capability errors must not be retried as if transient. Attempt each eligible provider at most once per request and cap the chain at six total attempts (OpenAI, four configured cloud fallbacks, and optional Ollama). Honor Retry-After only as a bounded delay before trying another provider; when it exceeds the configured wait budget, skip the delay and continue to the next provider. This adjustment is required because the previous two-attempt total could never traverse the approved provider list.

## Alternatives Considered

1. **One configured cloud endpoint:** lowest implementation effort, but cannot independently route among several providers.
2. **One OpenAI-compatible Spring AI model per configured provider (selected):** reuses the current framework adapter, supports independent keys/models, and feeds the existing application router. Trade-off: each provider/model needs capability and behavior evaluation.
3. **Separate native Spring AI integration for every provider:** could expose provider-specific APIs, but adds dependencies and configuration paths without a demonstrated need for these OpenAI-compatible chat endpoints.

## Security and Operations

- API keys exist only in developer secret stores and the private deployment input/Ansible runtime env path; never in source, Terraform state, plans, logs, or chat.
- Limit Cloudflare token permissions to Workers AI; its account ID is configuration, not a secret.
- Ansible writes app env with restrictive permissions and does not log secret-bearing task results.
- Provider health/capability diagnostics may expose provider IDs and missing/not-enabled status, never keys, prompts, request bodies, or response bodies.
- No live Oracle deployment, provider API call, or customer/advisor interaction occurs during implementation verification.

## Test and Evaluation Requirements

- Unit tests cover allowlisted provider binding, provider ordering, missing-key omission, invalid configuration, capability/privacy filtering, retryable versus nonretryable fallback, and full-chain traversal under the six-provider cap.
- Spring configuration tests verify one adapter is created per enabled-and-keyed provider and no adapter is created for a missing key.
- Ansible/Mise contract tests verify provider variables are carried into the app env template, rendered with secure permissions, and never printed.
- Ford app conversation/eval runs use mocked providers for deterministic tests; report deterministic policy results separately from any LLM/Ragas score.
- Run the existing relevant Java tests, app evals, build, and lint after implementation. Preserve raw traces and incomplete eval results.

## Acceptance Criteria

1. A developer can configure each provider locally using documented environment variables without modifying source.
2. Oracle configuration can reproduce the same provider settings through the existing private Mise/Ansible path.
3. The application constructs only explicitly enabled, keyed, allowlisted providers and orders them deterministically.
4. Fallback continues under current privacy, capability, error classification, and retry constraints.
5. Missing secrets never appear in diagnostics or rendered test output; absent optional keys skip only the corresponding provider.
6. `openrouter/free` remains out of the normal Oracle fallback chain unless deliberately enabled for evaluation.
7. Terraform plans/state contain no provider API credentials.
