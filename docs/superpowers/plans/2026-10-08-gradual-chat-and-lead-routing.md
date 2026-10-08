# Gradual Chat Capacity and Lead Routing Implementation Plan

**Status:** Implemented and verified
**Date:** 2026-10-08
**Spec:** [Gradual Chat Capacity and Lead Routing Design](../specs/2026-10-08-gradual-chat-and-lead-routing-design.md)

## Guardrails

- Preserve all pre-existing modified and untracked files; do not stage, commit, push, or deploy.
- Keep Spring Modulith JDBC publication registry as the only durable work queue.
- Keep the current graph-enabled path canonical; preserve legacy behavior until parity is demonstrated.
- Add tests before implementation and run focused verification after each coherent change.
- Keep initial configured conversation worker count at 1; document explicit 2 then 5 gates.
- Never emit secrets, customer payloads, or unredacted conversation contents in logs/evaluation output.

## Work items

| Status | Work | Tests / evidence |
|---|---|---|
| ✅ | Confirm current provider policy and implement Cloudflare → Groq → Gemini → OpenRouter → Ollama order, with max five attempts and permanent-error stop behavior | `ModelProviderPolicyTest`, `ProviderRoutingChatCompletionPortTest` |
| ✅ | Add bounded named conversation executor and retry eligible old inbound publications through Modulith public API | `ConversationAsyncConfigurationTest`, `ConversationPublicationRecoveryTest`; full context verification pending |
| ✅ | Add worker and publication backlog observability without changing unrelated listener executor behavior | Worker, queue, incomplete count, and oldest wait gauges defined |
| ✅ | Enable Jev configuration for lead/handoff fields, disable sensitive-data classification, preserve Java authorization, and prevent confident Jev lead results being overwritten | Classification, adapter, authorization, and assistant tests |
| ✅ | Add HOT-lead proactive offer and remembered decline suppression; retain direct-request behavior and truthful failure wording | Handoff policy and synthetic flow tests |
| ✅ | Replace rolling summary concatenation/truncation with bounded compaction at window rollover, filtering and version checks | Projector and compactor tests |
| ✅ | Keep graph processor canonical and document parity condition for any legacy removal | Graph defaults enabled; legacy path retained |
| ✅ | Add non-sensitive deterministic fixtures for greeting, exploratory, HOT offer/accept/decline, direct handoff, and independent conversations | `ChatLeadRoutingEvaluationTest`; provider fallback covered in routing tests |
| ✅ | Update runtime env examples, Ansible templates, and operations docs for provider order, Jev prerequisites, worker stages, and metrics | Config templates/docs updated |
| ✅ | Run focused test suites, compile, static checks available in project, and inspect final diff for secret/unrelated-file leakage | `./gradlew test`, `./gradlew bootJar`, Modulith architecture/app startup tests, and `git diff --check` passed; Docker-backed integration tests unavailable |

## Acceptance criteria

- Provider order and retry semantics match the approved design; Ollama is last.
- Effective first-release executor setting is one, with a bounded admission policy and durable recovery evidence; stage changes require recorded gates.
- Jev output is advisory; missing key or invalid/low-confidence response uses rules, and deterministic authorization remains authoritative.
- HOT leads receive an offer; only direct request or clear acceptance creates one internal queue item; declined offers are suppressed until direct request.
- Error fallbacks never promise an unaccepted handoff.
- Summary refresh cannot drop source context when compaction fails and runs only when the eight-message window rolls over.
- Graph path remains canonical; no deployment performed.
- Focused tests and build pass, or any blocker is precisely documented.

## Results

Implementation and verification completed.

- Focused AI and conversation suite passed.
- Full `./gradlew test --no-daemon` passed (510 tests); this includes the Modulith architecture test and app startup tests.
- `./gradlew bootJar --no-daemon` passed.
- Docker is unavailable in this workstation session, so Docker-backed `integrationTest` was not run.
- No external provider calls, remote deployment, commit, or push were performed. The local environment does not provide a Jev API key; until one is privately configured, effective classification remains the deterministic rules provider, as logged at startup.
- `git diff --check` passed after task-log whitespace cleanup. Existing unrelated dirty files and private ignored deployment material were left intact.
