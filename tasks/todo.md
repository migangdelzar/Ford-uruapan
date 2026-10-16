# Jev classification implementation

## Multi-phase workflow and memory/MCP/classifier evaluation (2026-10-08)

- [x] Add structured pending-question confirmation independent of transcript/cache availability.
- [x] Fix classifier reconciliation for short Ford-context follow-ups.
- [x] Add PostgreSQL + Redis synthetic workflows covering vehicle lookup, offer lookup, appointment confirmation/decline, finance, and topic switching.
- [x] Exercise read-only MCP tool authorization/callback invocation and verify appointment decisions do not invoke tools.
- [x] Run focused regressions, deterministic offline AI evaluations, unit suite, and requested integration workflows.
- [x] Document unrelated integration-suite failures; do not claim the full integration suite is green.

### Results

- Three synthetic multi-turn workflows exercise durable lead journey, PostgreSQL message/decision evidence, Redis-cached conversation memory, decision/classifier reconciliation, read-only tool authorization, and topic changes. Provider/classifier and model reply data are deterministic fixtures; no paid LLM API or live tunnel/MCP call is made.
- Added a regression proving an affirmative uses durable `TEST_DRIVE_CONFIRMATION` state even when recent transcript memory is absent. Short follow-up “La XLT” can follow an established Ford context through Jev vehicle routing.
- Focused policy/scope/decision tests pass; `mise run test:ai-evaluation` passes (9 Node flow/eval checks plus Java regression suite); all unit tests pass.
- New workflow integration tests passed against PostgreSQL and Redis Testcontainers. The complete integration suite has three failures: two `MetaInboundWorkflowIntegrationTest` failures caused by its fixed `phone-number-1` binding conflicting across tests, and `ConversationScenarioIntegrationTest` expecting a projection-refresh event not received by the scenario. These failures reproduce in the wider integration run including the new test.
- Deployment preflight and operations contract checks passed. Deployed the ARM64 app image to emme-1 via the supported remote-build path; Ansible reported 20 successful tasks, zero failed/unreachable hosts, and verified app health, Meta route, plus PostgreSQL/Redis connectivity. emme-2 data services were not restarted and no database or conversation data was reset.
- `mise run test:check` remains red from the same three unrelated integration failures. The later live evaluation used only synthetic conversations and free OpenRouter routes; the real MCP tunnel and deployed inbound conversation flow remain untested.
- Follow-up free-model evaluation is recorded in `ford-ai-service/docs/evaluations/2026-10-08-free-model-evaluation.md`: `openrouter/free` passed 3/8 app-level synthetic scenarios and passed the Spring AI synthetic tool-call preflight. The pinned Qwen and Gemma runs were unavailable (HTTP 404/429), not quality failures. No persistent production conversation or database row was created.

## Pending-question lead flow repair (2026-10-08)

- [x] Trace the production conversation and confirm it remained in one conversation.
- [x] Identify the rules fallback, stale lead-journey pending question, and compound prompts.
- [x] Add regression coverage for affirmative test-drive continuation and multi-question replies.
- [x] Implement a deterministic reply to an unambiguous affirmative test-drive offer and persist the next pending slot.
- [x] Tighten the one-information-request-per-turn prompt and quality repair rule.
- [x] Verify focused behavior, full service build/tests, and update results below.

### Acceptance criteria

- A clear affirmative answer to a single recent test-drive offer continues appointment flow; it never returns to generic model discovery.
- Ambiguous answers or multi-option assistant offers do not auto-select an appointment.
- The continuation persists `TEST_DRIVE_APPOINTMENT` and one next pending question.
- Generated replies with multiple questions are rejected and repaired.
- Follow-up wording requests one missing piece of information at a time.

### Results

- `PendingTestDriveReplyPolicy` maps a clear affirmative to a single recent test-drive offer to `TEST_DRIVE_APPOINTMENT`, persists `TEST_DRIVE_DATE` as the next pending question, and replies with one date question without calling a chat model or Jev.
- Ambiguous/multi-option offers and unclear replies continue through the normal classifier/generator path.
- `AssistantResponseQualityPolicy` rejects multiple interrogatives and questions joined by `y`/`o`, then requests one repair; the system prompt also forbids combining details or presenting lead-flow menus.
- Focused regressions, full `./gradlew test --no-daemon`, and `./gradlew bootJar --no-daemon` passed. The code is local only; it has not been committed, pushed, or deployed.

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

# Ford-web Test Lab authentication rollback (2026-10-09)

- [x] Remove the newly added OIDC client/BFF and restore Ford-web's prior demo login flow.
- [x] Keep `/api/testlab/respond` fail-closed and forward only a caller-supplied bearer token to Ford-service.
- [x] Record the contract: Spring Security validates external JWTs; Ford-service has no login/token-issuing endpoint; the demo JWT is not valid for it.
- [x] Keep Test Lab PostgreSQL session persistence scoped by tenant + operator + stable conversation ID, with bounded context and owner isolation.
- [ ] Provide a valid access-token source to the Test Lab UI before claiming live authenticated end-to-end connectivity.

# Ford CRM backend auth and Pages integration (2026-10-09)

- [x] Confirm Spring Authorization Server in Ford-service issues operator JWTs; passkeys primary; email one-time-token recovery.
- [x] Create feature branches in Ford-service and Ford-web while preserving existing dirty changes.
- [x] Add contract drift tests, BFF OpenAPI overlay, and generated contract synchronization.
- [x] Align canonical OpenAPI auth metadata and endpoint routes with Spring's passkey/OTT flow.
- [ ] Add persistent invited operator identities and claims.
- [ ] Configure persistent Spring Security passkey and one-time-token login.
- [ ] Add Spring Authorization Server and JWT resource-server chains.
- [ ] Add Ford-web BFF sessions and same-origin Pages routing.
- [ ] Connect UI login and Test Lab; migrate only implemented CRM API families.
- [ ] Verify temporary-origin deployment, then present production cutover preflight.

### Working notes

- Use Spring Security's JDBC WebAuthn repositories and JDBC one-time-token service; persist browser auth sessions in Redis.
- Preserve unrelated existing edits in both repos. Never stage `deployment/config/oci.env*`, `.tgrep/`, or other prior user work for this task.
- Production DNS/Tunnel cutover remains gated on a concrete preflight and approval.

# Spring UI proxy and BFF consolidation (2026-10-10)

- [x] Approve the Spring-owned UI proxy + Redis-backed browser-session design; keep bearer JWT clients and the OpenTelemetry Collector.
- [x] Write and review the implementation plan in `ford-ai-service/docs/superpowers/plans/2026-10-10-bff-consolidation.md`.
- [ ] Make Spring session auth work for protected APIs with active identity refresh and CSRF; preserve stateless bearer JWT.
- [ ] Switch Ford-web login/session calls to same-origin Spring auth endpoints.
- [ ] Deploy compatible versions while BFF stays available; verify all login/API/webhook flows.
- [ ] Cut `ford.emme.business` over to emme-1; requires Cloudflare Zone DNS Edit permission.
- [ ] Remove BFF container and `ford-origin` route only after public hostname verification; retain Collector.
# Deterministic MCP price and promotion response templates (2026-10-11)

Goal: Spring fills separate Markdown templates from returned MCP price/promotion facts; the LLM does not rewrite factual values.

- [x] Inspect tool result shapes and current completion seam.
- [x] Add renderer tests for confirmed prices/promotions, source links, and missing evidence.
- [x] Implement MCP evidence parsing and separate Markdown resource rendering.
- [x] Capture only relevant bounded payloads and override generated text for matching questions.
- [x] Run focused tests and check diff.

### Results

- Added independent price, promotion, combined, and no-evidence Markdown templates under `ford-ai-service/src/main/resources/response-templates/`.
- Spring fills separate templates with MCP-confirmed published prices, benefits, validity dates/status, conditions, and Ford source links. It never computes a net price or treats unknown validity as active; the LLM cannot rewrite these factual replies.
- MCP evidence capture is limited to `get_vehicle`, `search_vehicles`, and `list_promotions`, with a 64 KB per-payload cap. Source links are restricted to Ford Uruapan HTTPS hosts and query parameters are stripped.
- The first reply gets a stable Mexican name derived from the conversation ID; later replies in that conversation skip the introduction. The greeting does not claim the assistant is human, and the prompt requires an honest answer if asked.
- Scope policy rejects unrelated questions even if a classifier labels them Ford-related; explicit vehicle shopping requests and genuine in-context follow-ups remain supported.
- Focused persona, scope, renderer, adapter, and assistant-service tests passed; full `./gradlew test --no-daemon` passed (565 tests), including app-context startup. Changes are local; not committed, pushed, or deployed.

# Safe DeepSeek responses and advisor handoff (2026-10-12)

- [x] Keep one-detail assistant replies, log only rejection reasons, bound DeepSeek repair plus OpenRouter fallback, and return safe replies when MCP is unavailable.
- [x] Enable the verified OpenRouter production fallback and add config/quality/MCP regressions.
- [x] Send a customer handoff notice after an advisor claims the conversation; persist sender type/identity and expose it through the API/UI.
- [x] Verify app startup, Modulith listener packaging, DB migration, handoff transcript/outbox, and frontend sender rendering.
- [x] Run a read-only live MCP vehicle search and confirm catalog source URLs are returned.
- [ ] Recheck production logs after rollout; current app image still logged one `ClassNotFoundException` for `ConversationTurnRealtimeListener` in the last 100 lines.

### Results

- Branch: `fix/advisor-handoff-message-senders` in Ford-uruapan and Ford-web, based on each repo's current `origin/main`.
- On successful advisor claim, the service appends one outbound advisor-authored notice: “Hola, ya tomé tu conversación. Te responderé por aquí en cuanto pueda.” Message sender type/name now survive DB/API/UI mapping; existing messages receive a safe sender backfill.
- DeepSeek remains primary; the verified OpenRouter model is the only fallback. Primary gets at most one repair; fallback gets one request. Rejections log only a reason, not prompts or generated text. MCP failures/empty results withhold exact volatile facts; confirmed MCP prices/promotions render deterministic source-linked answers.
- Verification passed: full backend unit suite, focused backend suite, Ford-web sender Vitest, `bun run lint`, API contract check (47 paths), app context/Liquibase/Modulith tests, handoff/conversation integration tests, end-to-end Meta-to-outbox flow, and `bootJar` packaging check.
- Live read-only MCP `search_vehicles` returned three Ford Ranger results and Ford Uruapan source URLs. No deployment was performed. Production log check found no follow-up-service or classifier-failure signatures, but did find one realtime listener `ClassNotFoundException`; recheck after the feature branch is deployed.

# Credential storage hardening (2026-10-12)

- [x] Add OCI Vault-backed Spring secret loading using Compute instance principals, enabled only by explicit provider selection.
- [x] Prevent generated Ansible inventory from storing secret values; sanitize the existing generated inventory without printing its contents.
- [x] Add no-echo OCI Vault create/rotate tooling and save only secret OCIDs in a mode-0600 ignored mapping file.
- [x] Import configured current values into test-only Vault secrets, keeping the production mapping and runtime mode unchanged.
- [x] Disable the legacy task that persisted secret values in `deployment/config/oci.env`; add a macOS Keychain wrapper for local commands.
- [x] Document credential ownership, rotation, and runtime migration requirements.
- [x] Verify Python helper tests, Spring tests/build, Ansible syntax, Jinja templates, shell syntax, and whitespace.
- [ ] Rotate issuer-managed credentials, provision replacement values in OCI Vault, migrate data/MCP/collector runtime retrieval, deploy, and verify production.

### Results

- Added tests for secret request encoding, OCI-shaped OCIDs, test-only current-value import, OCID mapping safety, generated-inventory scrubbing, Keychain handoff, and hidden Vault prompts. `python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v` passed (16 tests).
- `./gradlew test bootJar` passed. Ansible `site.yml` and `mcp.yml` syntax checks passed; four Ansible Jinja templates parsed; changed shell scripts passed `bash -n`; Python scripts parsed; `git diff --check` and cached diff check passed.
- The local generated inventory had 18 credential fields removed while retaining unrelated settings and restrictive permissions. No credential values were printed. At the user's request, 20 current configured values were imported into active `ford-test-*` Vault secrets; the ignored test mapping contains only OCIDs and the production mapping is untouched. Two unconfigured Meta tokens were skipped.
- Production remains on environment mode. OCI browser authentication is active; read-only verification confirmed all 20 test secrets are `ACTIVE`, the test mapping is mode `0600`, and all mapped OCIDs match Vault resource IDs. Issuer-approved production replacements are still needed. Data, MCP, and collector still need direct Vault retrieval before production can switch safely. No deploy, commit, or push was performed.
# Meta reply delivery and public HTTPS login redirects (2026-10-16)

- [x] Add failing contracts for Meta runtime settings through the shared OCI action and Ford Service release workflow.
- [x] Pass delivery/outbox config and Meta Vault secret OCIDs to Ansible; snapshot the protected app `.env` and restore it on failed rollout.
- [x] Validate the Meta endpoint, phone-number ID, flags, and Vault references before production deployment.
- [x] Configure nonsecret GitHub production variables from the existing local deployment config without exposing values.
- [x] Merge the shared action and Ford Service changes into `main`, publish Ford Service `v0.1.15`, and complete the protected production deployment.
- [ ] Fix the still-HTTP login failure redirect in the effective Ford Service Nginx template, add a regression contract, publish a follow-up version, and redeploy through the protected workflow.
- [ ] Verify the public login redirect stays HTTPS and the pending Meta reply is delivered; inspect only redacted delivery status.

### Progress

- Local config passes safety checks; four GitHub production variables were set and three existing Vault OCID variables validated.
- Shared action and Ford Service release contract tests passed. Ford Service `v0.1.15` was tagged, image build/scanning/provenance passed, and the protected production deployment completed successfully.
- A safe invalid-credential probe exposed the remaining login bug: the live app returned `302 Location: http://ford.emme.business/login?error`. The running proxy config still had `X-Forwarded-Proto $scheme`; its source is the Ford Service repo template used by the shared action, not the similarly named template in the action repo.
- The correction branch now tests and sets fixed HTTPS forwarding, clears untrusted `Forwarded`, and sets the forwarded host/port explicitly. Production fix and Meta outbox status verification remain pending.
