# Jev classification implementation

## TypeSafe Jev activation wiring (2026-10-08)

- [x] Verify the exported workstation key and identify the app/deployment variable mismatch.
- [x] Add a no-echo adoption path from `FORD_TYPESAFE_JEV_API_KEY` to private `TYPESAFE_API_KEY`.
- [x] Propagate the canonical key through Mise inventory and Ansible to emme-1.
- [x] Add safe deployment readiness output and regression coverage for key presence/permissions.
- [x] Run an eight-flow synthetic Spanish classification smoke check and tighten option descriptions.
- [x] Reconcile deterministic specialist and scope routes against conflicting Jev tool choices.
- [ ] Confirm TypeSafe account spend limit or prepaid credits with automatic refill disabled.
- [ ] Publish and deploy to emme-1 after the account limit is confirmed.

### Results

- The API key was copied to the ignored local deployment config with mode `0600`; neither secret values nor generated Ansible credentials were printed.
- Jev 1.13 matched all eight synthetic intents and seven of eight raw intent/tool pairs. The app's deterministic route correction prevents a tool call on the one out-of-scope mismatch. Token usage was 7,689 input tokens, approximately $0.000323 at the listed rate.
- Full rollout remains gated on the TypeSafe account credit/refill setting. TypeSafe's API exposes no account billing configuration; production should wait until a $5 cap is set or a $5 credit balance is protected from automatic refill.

## Hybrid approved-knowledge retrieval

- [x] Confirm the existing knowledge vector store, metadata contract, Spring AI version, and PostgreSQL table.
- [x] Add filtered vector and PostgreSQL full-text retrieval, fused with reciprocal rank fusion and capped context.
- [x] Add deterministic fusion tests and a pgvector integration test proving lexical retrieval can recover a document outside vector top 20.
- [x] Document retrieval boundaries and verify focused tests plus build.

### Results

- Knowledge retrieval now combines filtered pgvector and PostgreSQL full-text candidates from Spring AI's default `public.vector_store` table. It returns at most eight approved, tenant/locale/type-matching chunks.
- Integration coverage checks tenant isolation, approval status, and lexical recovery outside the vector candidate set. Vehicle catalog behavior stays structured and unchanged.
- `./gradlew test --tests com.ford.service.knowledge.application.ReciprocalRankFusionTest --tests com.ford.service.ai.SimpleVectorStoreRagTest`, the focused pgvector integration test, and `./gradlew build` passed.

## Input security guardrails

- [x] Add deterministic injection and PII policy tests (`redact`, `block`, `allow`) before implementation.
- [x] Sanitize current text, memory, and journey context before classification and generation; block injection before any model/tool call.
- [x] Keep sensitive turns out of remote classification and honor existing local-provider restrictions.
- [x] Verify repeated-answer output repair regression cases and the complete build.
- [x] Document exact coverage and known limits; deploy only after local verification.

### Results

- `InputSecurityPolicy` now applies before Jev classification, lead qualification, scope evaluation, and Spring AI generation. Direct instruction-override markers are stopped before classifier/tool/model calls. Memory and journey blocks containing those markers are omitted.
- The existing PII policy is now active: `redact` is default; `block` returns a safe request to remove detected data; `allow` preserves it but sensitive turns bypass remote classification and cloud chat providers. Email, Mexican phone, valid Luhn card numbers, labeled CURP/RFC/CLABE/account values, and common credential assignments are recognized.
- The inbound transcript remains unchanged; only data passed into classification and model context is sanitized. Logs record bounded reason/flags only.
- Focused security/scope/repetition/config tests and `./gradlew build` passed. Deployed via the existing Mise/Ansible emme-1 path; no database schema change was required.
- Pattern matching is a limited defense-in-depth control, not a complete prompt-injection or PII detector. Response repetition checks remain heuristic and do not catch all paraphrases.

- [x] Add TypeSafe dependency/configuration and prove property binding.
- [x] Add typed decision vocabulary and policy.
- [x] Add TypeSafe adapter with deterministic fallback.
- [x] Propagate decisions into response generation and tool policy.
- [x] Persist normalized classifier evidence.
- [x] Wire optional TypeSafe startup behavior and diagnostics.
- [x] Add optional guardrail/self-refine configuration.
- [ ] Add end-to-end verification and operations docs.
- [ ] Run full verification and finish branch.

## Working notes

- Keep OpenRouter only for response generation.
- Keep rules fallback safe when TypeSafe is absent or fails.
- Never log or persist raw provider payloads or secrets.
- Human handoff remains controlled by `FORD_HUMAN_HANDOFF_ENABLED`.

## Ordered AI provider fallback and Oracle config

- [x] Add allowlisted Spring AI provider settings, disabled-by-default readiness gates, and per-provider OpenAI-compatible clients.
- [x] Keep primary OpenAI/Ollama routing; try eligible fallbacks in configured order, at most once each, with a six-call ceiling and bounded retry delays.
- [x] Carry provider models, endpoints, settings, and keys through Mise's ignored deployment config and Ansible's mode-0600 app environment; keep keys out of Terraform.
- [x] Add safe provider readiness output, secret adoption coverage, and Oracle/local environment documentation.
- [x] Verify provider config/router tests, deterministic AI evals, ten-flow handoff fixture, operations contracts, Gradle check/build.
- [x] Copy provider keys into ignored Oracle config from a shell where they are exported; run `mise run deploy:check` and deploy after provider-specific live evals.

### Results

- Provider-specific keys were adopted into the ignored Oracle config through Mise without printing key values. Four fallback entries are enabled in Ansible runtime configuration.
- Deterministic ten-flow authorization/payload checks passed. Historical LLM/Ragas results remain in `docs/ford-specialist-handoff.md`; no live model or Ragas evaluation was run here.
- `mise run test:check` initially could not find Docker because Testcontainers ignored its configured socket. It passed after scoping `DOCKER_HOST` to the already-running default Colima socket; no Docker/Colima settings were changed.
- Implementation remains uncommitted because this shared checkout contains unrelated user edits.

## Verify and repair all live AI providers

- [x] Add an opt-in Spring AI preflight that requires a synthetic tool call and checks final app response conversion.
- [x] Disable Gradle up-to-date reuse for live preflight so provider/model changes always execute a real request.
- [x] Verify OpenRouter `openai/gpt-oss-120b` through the app adapter; record only safe capability flags.
- [x] Diagnose Cloudflare, Groq, and Gemini failures using provider-specific model/API contracts.
- [x] Implement the smallest compatible request/response strategy with tests before changing runtime behavior.
- [x] Re-run all four live preflights, then deploy through the existing Ansible/Mise pipeline and verify emme-1.

### Acceptance criteria

- All configured provider entries can complete the same synthetic tool-call plus `AssistantGeneration` response path.
- Live preflight always makes fresh requests and emits no secret, prompt, or provider response body.
- Only a verified provider/model is eligible for production routing.

### Provider verification results (2026-10-06)

- Cloudflare `@cf/meta/llama-3.3-70b-instruct-fp8-fast`, Groq `openai/gpt-oss-20b`, and OpenRouter `openai/gpt-oss-120b` passed the synthetic tool-call and app response-conversion preflight through Spring AI.
- Gemini `gemini-3.8-flash` passed the same preflight once; a later run returned provider HTTP 503 with a temporary high-demand message after the synthetic tool call. The app fallback router is configured to continue to OpenRouter on retryable provider failures.
- Full Gradle tests and bootJar passed. The Mise operation-contract tests, deployment flag tests, and deployment readiness/inventory checks passed.
- Deployed via `mise run deploy:app` with the supported emme-1 ARM64 remote build. Ansible reported zero failed/unreachable hosts; app health, Meta webhook route, PostgreSQL, and Redis checks passed. Container logs show `FordServiceApplication` started; inspection confirms all four fallback providers are enabled in configured order.

## Ford specialist handoff and MCP runtime recovery

- [x] Map the main-app turn path, prompt source/override, Ford information MCP, handoff queue, and MCP/Cloudflare startup configuration.
- [x] Add failing tests for consent authorization from actual recent turns, decline/duplicate handling, typed payload validation and omissions, queue success/failure wording, and app-level conversation behavior.
- [x] Implement a narrowly scoped typed specialist-handoff service over the existing internal queue; keep the Ford information MCP read-only and do not claim an external advisor transfer.
- [x] Enforce direct-request authorization and offer-then-later-affirmative authorization in application code; preserve declines and gate offers to qualifying turns.
- [x] Update the classpath system prompt and runtime configuration guidance; preserve existing tool-routing, grounding, financing, and truthfulness rules.
- [x] Adapt the ten Ford-scraping multistep cases to deterministic main-app policy checks; preserve partial runs and traces, and report LLM/Ragas scores separately.
- [x] Diagnose and fix the MCP startup failure without weakening read-only tool requirements; create a dedicated Cloudflare route for the workstation MCP.
- [x] Run requested unit/integration tests, build, MCP lint, and deterministic eval; document exact results and missing live-integration details.
- [x] Run the Ford information MCP on this Mac, expose it through the dedicated Cloudflare tunnel, and point emme-1's existing MCP client config at that tunnel; keep the app's existing tunnel route unchanged.
- [x] Verify the locally hosted MCP and remote app connection without creating CRM records or contacting customers. Document the workstation-online dependency.

### Acceptance criteria

- Information-provider tools remain read-only and unchanged.
- A model handoff claim cannot create a queue item without a current direct request or a later affirmative following an eligible offer in persisted conversation history.
- Payload values are strict, typed, grounded in customer text, and omit unknown optionals.
- Queue success and failure responses match the actual internal queue result and never promise contact, appointment, or follow-up.
- All ten supplied app fixture cases have deterministic policy results; the separate live-model evaluation remains 6/10 passing with four failures and two incomplete traces.
- Existing unrelated workspace changes are preserved.

## Enterprise architecture baseline and assistant scope guardrails

- [x] Inspect existing EA/runtime designs, current request path, scope/intent/tool policies, prompt, tests, and production observability/MCP topology.
- [x] Agree on the boundary: supported Ford assistance, greetings, and clear Ford-context follow-ups; redirect unrelated and unsupported requests before model calls.
- [x] Write the architecture baseline with C4 context/container and sequence views, data/trust boundaries, current deployment facts, and known gaps.
- [x] Add failing unit/app tests for general knowledge, prompt injection, mixed requests, unsupported service/complaint requests, contextual follow-ups, and optional approved knowledge search.
- [x] Enforce deterministic scope before generation; a model decision cannot widen it; service support is allowed only when `approved_knowledge_search` is registered.
- [x] Align system prompt and mark the earlier runtime design as historical where its status claims are stale.
- [x] Run focused and full regression tests, bootJar, operations contracts, deployment preflight, and diff review.

### Results

- The production `AssistantScopePolicy` previously let unclassified `GENERAL` requests reach model generation and allowed any Ford keyword in memory to make an unrelated question appear in-scope. Regression tests reproduced these cases.
- The target scope now allows only supported deterministic Ford intents, pure greetings, and specific contextual follow-ups with Ford memory. Explicit out-of-scope markers veto the entire mixed request. Unsupported service requests pass only when the approved knowledge-search tool is registered; complaint-status requests remain blocked.
- The app-level service invokes no orchestrator for blocked requests. The classifier cannot upgrade a deterministic general/out-of-scope request.
- Production architecture evidence is split between configuration and observed rollout. The old scrape 401 and disabled-trace state predated the telemetry rollout. The corrected production Collector template (OTLP metrics only) is pending deployment; Grafana signal arrival and ongoing workstation MCP availability remain unverified.

### Verification results (2026-10-07)

- Focused scope/provider/handoff regression tests passed.
- Full `./gradlew test --no-daemon --rerun-tasks` passed with all test tasks executed; `./gradlew bootJar --no-daemon` passed.
- All 24 `node scripts/operations-contract.test.mjs` checks pass after removing the stale production Collector scrape receiver and its unused Collector env credentials.
- `mise run deploy:check` passed and confirmed emme-1/emme-2 inventory. Corrected Collector config has not yet been deployed; live backend signal and alert notification checks remain unverified.
- `git diff --check` passed. Docker-backed integration tests were not run in this pass.
# Workstation Ollama remote access

- [x] Inspect existing Cloudflare tunnel/DNS, MCP HTTP authentication, local Ollama, Ansible inventory, and Spring AI client configuration.
- [x] Present and receive approval for the design: reuse `ford-mcp-workstation`; add Access-protected Ollama and MCP hostnames; use dedicated localhost Ansible plus emme-1 app Ansible.
- [x] Write and self-review design spec and implementation plan.
- [ ] Implement scoped Spring Access headers and tests.
- [ ] Implement localhost Ansible LaunchAgents and deployment config.
- [ ] Provision/read back tunnel routes, Access apps/policies, service tokens; store secrets privately.
- [ ] Deploy/reconcile both ends and verify authorized and denied requests.

### Working notes

- Existing tunnel is healthy; current MCP route is `ford-mcp.emme.business` → localhost:3001.
- No Access applications currently exist for the account.
- Host Ollama is 127.0.0.1:11434; Docker Ollama 127.0.0.1:11435 is embedding-only.
- Keep the extensive pre-existing dirty changes intact; do not commit.

# Gradual chat capacity and lead routing (2026-10-08)

- [x] Implement cloud-first model fallback with Ollama last, bounded Modulith conversation workers, and durable publication recovery.
- [x] Enable Jev lead/handoff classification settings while keeping sensitive-data classification off and authorization deterministic.
- [x] Add HOT-lead offers with remembered decline state, compact-summary rollover, and synthetic conversation routing fixtures.
- [x] Run focused and full test suites; record actual results in `docs/superpowers/plans/2026-10-08-gradual-chat-and-lead-routing.md`.
- [x] Reconcile operations and README docs against effective configuration; inspect diff for unrelated and sensitive-file leakage.
