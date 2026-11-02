# Qwen Conversation Memory Investigation Plan

> **For agentic workers:** This is an investigation plan, not permission to change production behavior. Follow the diagnostic gates in order. Do not start the fix phase without user approval.

**Goal:** Find the first point in the real conversation harness where Qwen's follow-up loses, misuses, or ignores context, then propose a narrowly scoped fix backed by evidence.

**Architecture:** Trace the active inbound path from synthetic webhook acceptance through event publication, turn lease/ledger, graph/processor, durable and cached memory, AI request assembly, provider routing, Qwen generation, response policies, retry state, reply commit, and outbox. Reproduce with local PostgreSQL and Ollama only; use synthetic canaries and test-only/redacted observations to compare each boundary. After diagnosis, present the root cause and fix proposal for approval; implement and verify only after approval.

**Tech Stack:** Spring Boot, Spring Modulith, LangGraph4j, Spring AI, Ollama (`qwen2.5:3b`), PostgreSQL, Redis, Gradle.

## Global Constraints

- Local Ollama and local database only; no remote deployment.
- Synthetic webhooks only; never send real WhatsApp messages.
- Use temporary runtime overrides; leave source configuration and `.env` unchanged during diagnosis.
- Do not inspect or expose production customer message text. Use synthetic fixtures and redacted evidence.
- Preserve existing user work and uncommitted changes.
- Do not weaken response quality, evidence, tenant-isolation, or memory privacy controls to make the replay pass.
- Do not run the 101-turn flow until the two-turn reproduction passes end to end.
- Any code, prompt, guard, tool, schema, or runtime behavior fix requires a separate approval after the root-cause report.

---

## Investigation File Map

Read these as the initial path map; verify actual runtime wiring rather than assuming similarly named code is active:

- `src/main/java/com/ford/service/channels/adapter/in/meta/MetaWebhookController.java` — synthetic Meta webhook ingress and acknowledgment.
- `src/main/java/com/ford/service/channels/application/ChannelMessageService.java` and the inbound repositories/events — durable acceptance, dedupe, and event publication.
- `src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java` — async event listener, debounce, lease, retries, and graph invocation.
- `src/main/java/com/ford/service/conversations/application/graph/ConversationGraphRunner.java` and graph configuration/state — graph execution and checkpoint behavior.
- `src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java` — context loading, assistant call, policy decisions, and turn outcome.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java`, `adapter/out/JdbcConversationMemoryRepository.java`, and `adapter/out/RedisCachedConversationMemoryPort.java` — durable memory, cache behavior, projection, and recall.
- `src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java` — memory/context formatting and `AiExecutionContext` construction.
- `src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`, `DefaultAssistantOrchestrator.java`, and `application/port/out/ProviderRoutingChatCompletionPort.java` — request policy and provider routing.
- `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java` — final prompt/history assembly, Qwen call, response assessment, and repair.
- `src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java` and adjacent evidence/repeat policies — rejection and recovery behavior.
- Turn ledger, commit, delivery/outbox adapters and migrations — persisted retry/terminal state and reply intent.
- Relevant tests under `src/test/java/com/ford/service/{channels,conversations,ai}` — establish expected contracts and identify safe test seams.

No source file is to be modified during the diagnosis unless the user separately approves diagnostic instrumentation. Prefer existing logs, debugger/test seams, and temporary local test harnesses. If a probe is needed, keep it test-only or untracked, synthetic-data-only, redacted, and remove it before reporting.

## Task 1: Establish the active architecture and contracts

- [ ] Trace the configured bean/event path from webhook controller to the processor that actually runs; identify any legacy/parallel processor and confirm whether it participates in this local run.
- [ ] Trace ordering and state ownership: receipt, debounce, lease, turn ledger, graph checkpoint, retry scheduler, commit, and outbox. Mark each transaction boundary and the durable source of truth.
- [ ] Trace memory writes and reads: inbound/outbound message persistence, memory projection/version, cache key/invalidation, summary/facts/recall, and which history window enters `ConversationMemoryContext`.
- [ ] Trace transformation from `ConversationTurnRequest` through `ConversationAssistantAdapter` to `AssistantRequest` and final provider messages. Record which fields are system context, current user turn, prior assistant replies, and repair instructions.
- [ ] Trace all response checks and retry layers. Distinguish provider transport retries, model repair, quality/evidence/repeat rejection, workflow retries, and eventual ledger states.
- [ ] Record exact source locations and current test coverage in a compact flow diagram/table. Do not infer model context from a `recentMessages` count alone.

**Deliverable:** Evidence-based sequence diagram or ordered flow table, including active bean wiring, durable state transitions, all memory/prompt transformations, policy boundaries, and retry counts.

**Gate:** The flow map must identify where each of the following is observed: accepted inbound, current turn, loaded memory, assembled provider input, raw model result, policy verdict, retry state, committed reply, and outbox intent.

## Task 2: Confirm local test preconditions and safe fixture

- [ ] Confirm Ollama serves the installed `qwen2.5:3b` model and local PostgreSQL/Redis health without changing `.env` or source configuration.
- [ ] Confirm the temporary test port is free and existing app health remains unaffected. Use a unique synthetic tenant/sender/provider-message prefix for this run.
- [ ] Confirm synthetic webhook authentication and delivery-off settings. Ensure the test cannot call the real Meta delivery adapter.
- [ ] Build a two-turn fixture: turn 1 establishes a concrete canary (for example, “Ford Ranger” and one explicit preference); turn 2 asks a related follow-up without restating that canary. Keep fixture text fixed across replay variants.
- [ ] Capture starting row counts and prompt/config markers. Use guarded cleanup that refuses deletion unless tenant, sender, IDs, and temporary markers exactly match the fixture.

**Deliverable:** A reproducible local test recipe and clean baseline, with no customer data and no real sends.

**Gate:** Do not start the app if delivery-off cannot be verified or if fixture isolation/cleanup scope is ambiguous.

## Task 3: Reproduce and inspect the two-turn failure

- [ ] Start one temporary app instance with runtime-only overrides selecting Ollama/Qwen and the synthetic signature; keep the existing app on its own port untouched.
- [ ] Send turn 1 through the real synthetic webhook and wait for durable inbound, model completion, policy acceptance, reply commit, and outbound intent before sending turn 2.
- [ ] Send turn 2 through the same webhook/conversation. Record each provider message ID, receipt ID, conversation ID, memory version, and timestamp in the private test notes.
- [ ] Query local PostgreSQL for ordered inbound/outbound rows, turn-ledger state/attempts/failure category, and outbox state. Read only this run's synthetic records.
- [ ] Correlate app logs by synthetic provider message/receipt/conversation IDs. Record provider/model, tool family and tool count, prompt token/character metadata if already available, raw answer only in local test notes, quality/evidence verdict, repair invocation/result, retries, and commit status.
- [ ] Stop the temporary app and remove only this run's verified synthetic rows and temporary prompt/runtime records. Confirm no test listener remains and the pre-existing local app is still healthy.

**Deliverable:** Exact, timestamped evidence for both turns from webhook acceptance to commit or failure, with no unsupported claim that memory worked based only on counts.

**Gate:** If this run does not reproduce, do not claim the previous issue is fixed. Repeat only after comparing model/config/prompt/fixture differences and record them.

## Task 4: Locate the first context divergence

- [ ] For turn 2, compare the canary across: PostgreSQL message history → Redis/cache result → `ConversationMemoryContext` → formatted adapter memory block → `AssistantRequest` → final Spring AI/Ollama message list.
- [ ] At each stage, verify tenant/conversation key, memory version, ordering, truncation/token limits, role assignment, escaping/sanitization, and whether the canary is present and marked as trusted context rather than instructions.
- [ ] Compare the actual current user message at each boundary. Confirm no debounce, replay, stale checkpoint, or retry path substitutes turn 1/current turn or drops the latest inbound.
- [ ] Compare the first generated answer and repair answer with the exact model-visible history. Determine whether the repeated generic question comes from absent context, contradictory/oversized context, prompt hierarchy, request construction, model behavior, or a downstream policy classification.
- [ ] Verify the quality policy is judging the correct current message and response, and trace how one rejected completion becomes the observed persisted retry state. Separate content rejection from transport/provider fallback behavior.
- [ ] If final provider input is not observable, add no persistent logging. Use a test seam/debugger or a temporary local-only synthetic probe after approval; record only canary presence/count/roles/lengths where possible.

**Deliverable:** A boundary-by-boundary table showing the first divergence, with a direct observation or a reproducible test for every claim.

**Gate:** Root cause is not established until the first differing boundary is identified or controlled comparison rules out context loss and narrows the cause to model/policy interaction.

## Task 5: Run controlled comparisons and report root cause

- [ ] Replay the same turn 2 in a fresh conversation with no history, then with the established turn-1 history, changing no other setting. Use the same Qwen tag, system prompt, tool permissions, and response policies.
- [ ] Where safe and available, compare the application’s exact assembled request with a direct local Ollama request to isolate app prompt assembly from model capability. Label direct-call results as diagnostic only, not end-to-end proof.
- [ ] If output differs across repeated calls, run a small bounded repeat set with recorded settings; do not launch the 101-turn test as a substitute for controlled diagnosis.
- [ ] Produce a root-cause report: observed facts, first divergence, evidence, competing hypotheses ruled in/out, impact on retries/turn visibility, and smallest candidate fix.
- [ ] Present the candidate fix separately and request approval before changing prompts, memory policy, quality/evidence guards, provider routing, retry behavior, or application code.

**Deliverable:** Root-cause report and an approval-gated fix proposal. No behavior change in this task.

**Gate:** User reviews the evidence and explicitly approves a specific fix before implementation begins.

## Diagnosis Findings — 2026-10-21

### Proven first divergence

| Boundary | Observation | Evidence |
|---|---|---|
| Durable transcript / memory | Synthetic turn 2 was stored; memory version was `1`, and the processor loaded two recent messages. The history contained the Ford Ranger canary. | Local PostgreSQL rows and `DefaultConversationTurnProcessor` log for conversation `b758bd66-41b5-31da-a8d9-c68cdfbde4c8`; synthetic rows were removed after the run. |
| Application scope check | Turn 2 classified `GENERAL`; application scope checking receives the formatted memory and allows a general follow-up when that memory contains Ford context. | `SpringAiAssistantService.java` calls `scopePolicy.reason(..., securedInput.memoryContext())` at lines 169–175; `AssistantScopePolicy.java` lines 90–95. |
| ChatClient scope advisor | The advisor checks only current user text: `scopePolicy.allows(userText, null)`. It discards the history context that passed the application check and returns the fixed generic scope response when the follow-up omits Ford/model words. | `FordScopeAdvisor.java` lines 37–45; registered as the default advisor in `SpringAiConfiguration.java` lines 330–336. |
| Provider boundary | The temporary loopback Ollama capture proxy received zero application requests for turn 2. The 119-character result matches the length of the advisor's deterministic `scopeResponse`; despite adapter log wording, this was not a Qwen generation. Repair invoked the same advisor and returned the same response. | Empty app capture set (separate smoke-test capture removed), paired with `SpringAiChatCompletionAdapter.java` lines 444–477 and its quality-repair path at lines 249–267. Runtime logs show `generic_scope_response` on original and repair. |
| Policy / retry | `AssistantResponseQualityPolicy` correctly rejects the generic response for a non-greeting current message. The invalid response does not cause provider fallback; the workflow records `RETRY_WAIT` and repeats the same pre-provider path. | `AssistantResponseQualityPolicy.java` lines 32–47; `ProviderRoutingChatCompletionPort` logged `fallback=false`; synthetic turn ledger reached `RETRY_WAIT` with five attempts. |

### Root cause and scope

The failure is a context mismatch between two scope guards, not demonstrated loss in PostgreSQL, Redis, or memory formatting, and not evidence that Qwen ignored the history. The application-level guard uses memory; the ChatClient defense-in-depth advisor does not. Thus a context-dependent follow-up passes one guard and is replaced by a canned response at the next boundary before Ollama. The quality checker then rejects that canned response, and workflow retry repeats it.

No provider-visible message list or raw Qwen answer exists for this failing turn; the local capture proxy observed no app request. Do not attribute the rejection to model behavior. A no-history comparison also stopped at the application scope guard, consistent with context determining whether this follow-up reaches ChatClient.

### Smallest candidate fix — approval required

Make the ChatClient scope advisor evaluate the same trusted, sanitized conversation context used by `SpringAiAssistantService`, or remove this duplicate contextual decision if the defense-in-depth contract cannot safely receive context. Preserve unrelated-request blocking and the existing quality/evidence policies. Add a focused test proving a Ford-contextual follow-up passes while the same message without Ford context and an unrelated request remain blocked. Do not implement until the user approves this specific change.

### Verification and cleanup

- `./gradlew test --tests com.ford.service.ai.application.AssistantScopePolicyTest --no-daemon` — **PASS**.
- Temporary app on `18082` and capture proxy on `11435` stopped; existing app on `18080` remained healthy.
- Removed this run's exact `qwenexact` webhook/message/turn/memory/cache rows and probe captures. Preserved the deterministic conversation row because three older, differently named ledger entries still reference it; did not delete those unrelated rows.
- No source behavior or `.env` changes were made. The 101-turn replay remains gated on approval, a fix, and a passing two-turn run.

## Task 6: Approval-gated fix and verification (not authorized yet)

Do not execute this task until the user approves the proposed fix.

- [ ] Convert the approved root cause into the smallest test-first change. Add a focused unit test for the exact failing boundary before implementation.
- [ ] Run focused unit tests, then the service unit suite. Run integration tests using local/disposable PostgreSQL and Redis where the changed boundary requires real wiring. Do not proceed to E2E unless lower-cost gates pass.
- [ ] Re-run the exact two-turn synthetic Qwen flow. Require both replies to be non-empty, pass existing safety/evidence policies, persist successfully, and create the expected outbox state with delivery disabled.
- [ ] Review diffs for accidental changes to `.env`, source runtime configuration, customer data, unrelated worktree edits, policy weakening, or test artifacts. Remove temporary probes and verify cleanup.
- [ ] Report test evidence and ask for approval before expanding to the 101-turn sequential run.

**Deliverable:** Approved minimal fix, regression test, verified two-turn flow, and no unintended workspace/data changes.

## Task 7: Conditional 101-turn acceptance replay (separate approval gate)

- [ ] Only after the two-turn flow passes and the user approves the longer replay, run 101 sequential synthetic turns through the same local webhook and Qwen app, awaiting each committed reply before sending the next turn.
- [ ] Require exactly 101 accepted inbound messages and 101 non-empty, policy-valid, persisted assistant replies. Track per-turn ledger, provider, quality verdict, and outbox state.
- [ ] Stop on the first failed or invalid turn; preserve redacted evidence, do not skip ahead, and do not turn retries into artificial success.
- [ ] Clean only the verified synthetic fixture rows and temporary prompt marker; verify no real delivery, no temporary listener, and no effects on the existing app.

**Deliverable:** Full-flow acceptance report with exact counts and first-failure evidence if the run does not complete.

## Final review checklist

- [ ] Every asserted architecture edge is backed by source wiring or runtime evidence.
- [ ] Diagnosis identifies a first divergence, not only the final quality-guard error.
- [ ] No production customer content was read or logged.
- [ ] No real channel send, deployment, or broad database cleanup occurred.
- [ ] Existing unrelated changes remain untouched.
- [ ] Fix and 101-turn replay remain approval-gated.
