# Gradual Chat Capacity and Lead Routing Design

**Status:** Draft for review
**Date:** 2026-10-08
**Related:** [Current architecture baseline](../../architecture/ford-service-enterprise-architecture.md), [Jev classification design](2026-09-22-jev-classification-design.md), [runtime design](2026-09-25-enterprise-agentic-conversation-runtime-design.md)

## Goal

Simplify the active conversation path and increase supported concurrent chats in measured stages: one, then two, then five. Route through approved cloud providers first and use workstation Ollama as the last fallback. Classify lead intent and readiness so the bot can offer specialist help at the right time, while requiring customer consent before creating a handoff request.

This design targets the current Spring Modulith/Spring AI application. It does not claim a 100-user capacity target or an external advisor integration.

## Verified starting point

- Inbound message acceptance already publishes an identifier-only `InboundMessageAccepted` event. The JDBC-backed Spring Modulith publication registry is the durable internal dispatch mechanism, and outstanding publications are configured for restart republication.
- A PostgreSQL lease serializes turns for the same conversation. No separate global conversation-worker limit is currently configured.
- The model provider candidate policy currently orders local Ollama before cloud providers. The configured cloud fallback list is Cloudflare, Groq, Gemini, and OpenRouter. The provider-attempt limit defaults to six.
- Jev/TypeSafe classification code already exists. It uses one `SystemOne` request for the enabled classification questions and falls back to deterministic rules. The TypeSafe client is conditional on a configured API key; the last Emme-1 inspection found no key in the running app, so the effective classifier was rules. The handoff classification question is disabled by default.
- Deterministic lead qualification currently runs after classification and can replace Jev lead stage/temperature. Handoff queue creation is guarded by direct customer authorization or an affirmative answer after a persisted specialist offer. A HOT lead alone currently does not trigger an offer.
- When human handoff is enabled, the current generation-failure fallback text says the customer will be connected to an advisor even when no queue request was accepted. The fallback must be corrected before enabling handoff.
- The LangGraph4j path is enabled in the current deployment configuration and contains one turn-processing node. The legacy workflow remains as a second selectable execution path. The checkpoint thread ID includes the inbound receipt ID, so graph checkpoints are per turn, not a continuous cross-turn memory.
- Memory currently includes a bounded recent-message window and a rolling summary that appends older summary text and recent requests before truncating at 4,000 characters. This can truncate newer information.

## Decisions

### 1. One durable queue and staged concurrency

Keep Spring Modulith's JDBC publication registry as the only durable queue. Do not add Kafka, RabbitMQ, a second database queue, or multi-agent fan-out for this phase. Keep the per-conversation lease so a customer never has two turns processed concurrently.

Add a named `conversationTurnExecutor` for the inbound conversation listener, with fixed worker concurrency initially set to one and a bounded pending-task queue. Raise worker concurrency to two and then five only after each stage meets the evaluation gates below. Keep unrelated Modulith listeners on their existing executor. A rejected task must remain represented by an incomplete durable publication and become eligible for retry when capacity returns; verify this lifecycle with the resolved framework versions before rollout. Expose active-worker count plus publication wait age/depth. Do not claim strict FIFO fairness unless the selected implementation proves it.

`@ApplicationModuleListener` is asynchronous and transactional; the publication registry records listener work and leaves failed publications incomplete. Use Spring's named `@Async` executor selection for the conversation listener, verifying method-level qualification on the composed annotation. Restart republication and failure resubmission are distinct operations. [Spring Modulith event reference](https://docs.spring.io/spring-modulith/reference/events.html), [Spring executor qualification](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)

### 2. Cloud-first provider routing

Use one authoritative execution order:

1. Cloudflare
2. Groq
3. Gemini
4. OpenRouter
5. Local Ollama (`qwen3.5:4b`)

Only enabled providers with verified tool-calling and structured-output capability are candidates. Keep OpenAI disabled unless separately requested. Change the effective provider policy order, not just the adapter construction order. Set the maximum provider attempts to the number of eligible providers in the configured chain, with five as the initial ceiling. Permanent request/authentication failures do not continue through the chain; transient provider failures do. If all candidates fail, return the existing safe retry-later response and complete the turn without an unbounded provider retry loop.

Ollama is an availability fallback. Its workstation/tunnel health is monitored, but the cloud chain must remain usable while that workstation is asleep or disconnected.

### 3. Jev lead and handoff classification

Enable Jev/TypeSafe for intent, lead stage, lead temperature, tool family, and handoff recommendation in one classification request. Do not ask Jev to classify sensitive data in this phase. Activation requires a valid `TYPESAFE_API_KEY`; configuration without the key continues to use deterministic rules and must report that effective mode clearly.

Set `FORD_AI_CLASSIFICATION_ENABLED=true`, `FORD_AI_CLASSIFICATION_MODE=active`, `FORD_AI_CLASSIFIER_HANDOFF_ENABLED=true`, and `FORD_AI_CLASSIFIER_SENSITIVE_DATA_ENABLED=false` for the staged rollout. Enable `FORD_HUMAN_HANDOFF_ENABLED` so authorized requests can enter the existing internal queue. Keep `TYPESAFE_API_KEY` in the existing private deployment configuration; never place it in the spec, repository, or logs.

Jev supplies a normalized recommendation, not authority to mutate state. Use the existing confidence threshold and rules fallback. Preserve deterministic rules for explicit human requests, scope, tool authorization, and consent. Avoid applying a second deterministic lead score over a confident Jev result; use deterministic lead scoring when Jev is unavailable, malformed, or below confidence threshold.

Handoff behavior:

- A direct request to speak with a person may enter the internal specialist queue through the existing authorization policy.
- A HOT lead with no direct request gets a clear offer to connect with a Ford specialist.
- Create the internal handoff request only after the customer gives a clear affirmative to that offer. A decline prevents handoff.
- A declined offer suppresses further proactive offers for that conversation. A later direct request for a person is still honored.
- A HOT classification alone never creates a handoff request.
- Tell the customer only that the internal request was accepted. Do not claim an advisor was contacted, or that a call or appointment was booked.
- A provider/classifier failure uses neutral retry-later wording. Handoff-enabled mode must never turn a generation failure into a false transfer promise.

The handoff queue is internal; external CRM/advisor integration is out of scope.

### 4. Bounded recent window and compact summary

Keep the latest eight messages (four user/assistant exchanges where available) verbatim. Replace rolling concatenation with a compact summary of older turns containing only:

- the customer's current goal and vehicle interest;
- stated preferences, budget, and timing that remain relevant;
- decisions already made;
- whether specialist help was offered/accepted/declined;
- unresolved questions or next steps.

Refresh the summary only when the recent window rolls over, not on every turn. Use the existing model provider routing policy for compaction. This adds an occasional model call; record its latency and provider usage. Bound summary size and compaction attempts. Store updates with the existing memory-version check. If compaction fails, keep the last valid summary and retain enough recent transcript for the next turn; never drop un-compacted context to satisfy a size limit. Continue to treat PostgreSQL as source of truth and Redis as a fail-open cache.

Do not add semantic memory, a second transcript store, or summary generation on every message in this phase.

### 5. One active execution path

Use the currently deployed graph-enabled turn processor as the canonical path for the first release, because it is the runtime in production. Do not add graph branches or specialist agents yet. Before removing the legacy branch, compare behavior and idempotency for intake, memory, handoff, and outbox delivery; then remove the unused branch in a separate cleanup if the parity checks pass. This avoids changing production flow and deleting code in the same step.

## Staged rollout and gates

| Stage | Concurrent conversation turns | Gate before increasing |
|---|---:|---|
| 1 | 1 | Complete real conversation flows; provider order verified; Jev/rules decisions visible; consent rules hold; recent-window and summary preserve key context; no duplicate outbound delivery. |
| 2 | 2 | Stage 1 passes; two independent conversations progress without cross-talk; same-conversation turns remain ordered; queue wait and provider latency are measured. |
| 3 | 5 | Stage 2 passes; five independent conversations progress; no lost/duplicate turns; handoff precision and consent behavior remain within agreed eval thresholds. |

At each stage record publication wait age/depth, end-to-end p50/p95, provider/model attempts and latency, Jev confidence/fallback rate, lead-temperature quality, offer/accept/decline outcomes, memory continuity, and delivery idempotency. Establish the p95 objective from measured stage-1 behavior before promising a numeric service level.

## Evaluation flows

Use representative, non-sensitive customer conversations, including:

1. Greeting and simple vehicle question: no handoff.
2. Exploratory vehicle interest: continue bot conversation and preserve preferences.
3. High-intent purchase/appointment signals: classify HOT, offer specialist help, wait for answer.
4. Customer accepts the offer: create exactly one internal queue request and state its actual status.
5. Customer declines: do not create a queue request and continue bot conversation.
6. Direct request for a person: create the internal request once.
7. Jev timeout/invalid result: use deterministic fallback and complete the turn safely.
8. Cloud providers unavailable: reach Ollama last; if all providers fail, return neutral retry-later text even when handoff is enabled.
9. Two and then five separate conversations: no memory or decision leakage across chats.
10. Two inbound messages in one conversation: preserve per-conversation ordering and use the correct turn context.

Report Jev classification accuracy and handoff offer/accept precision separately from generated-answer quality. Do not use model self-confidence as a calibrated probability.

## Alternatives considered

1. **Keep current effective Ollama-first order:** rejected for this phase because Ollama has shown long response times and depends on a workstation/tunnel being available.
2. **Add a separate broker or multi-agent worker system:** defer; Modulith already provides durable dispatch and current scale is a measured 1→2→5 rollout.
3. **Use Jev as both classifier and handoff authority:** rejected; Jev classifies, while application rules enforce explicit user consent and queue idempotency.
4. **Update the rolling summary on every message:** rejected; it adds unnecessary model work and latency. Compact only when the bounded recent window rolls over.

## Out of scope

- A 100-user capacity claim or large-burst SLO.
- Kafka/RabbitMQ or a second durable queue.
- Parallel specialist agents or a multi-node graph.
- Automatic staff handoff based only on HOT classification.
- External CRM, advisor messaging, calls, or appointment booking.
- Sensitive-data classification as a product feature.
- Broad cleanup of historical design documents or unrelated dirty working-tree files.

## Self-review

- **Consistency:** cloud-first routing, Ollama-last fallback, bounded retries, and local provider ordering are stated once and consistently.
- **Consent:** Jev never enqueues directly; direct customer request or affirmative after an offer is required.
- **Scope:** first release is limited to 1→2→5 staged capacity, lead classification, handoff offer/consent, and bounded conversation memory.
- **Failure behavior:** classifier failure uses rules; provider-chain failure returns a safe response; compaction failure preserves prior context; executor rejection must leave work recoverable.
- **Implementation verification gate:** prove executor rejection leaves the Modulith publication recoverable, then prove it is resubmitted after a worker slot opens. If the standard executor hook cannot preserve that contract, do not add a second queue; use the current unbounded async listener temporarily at concurrency one and add an operational alert until a supported recovery hook is confirmed.
