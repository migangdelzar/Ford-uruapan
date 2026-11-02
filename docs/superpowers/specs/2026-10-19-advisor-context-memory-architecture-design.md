# Advisor Context and Customer Memory Architecture

**Status:** Design approved in conversation; awaiting written-spec review
**Date:** 2026-10-19
**Scope:** Ford Service customer conversation context, cross-thread shopping memory, reply continuity, and memory-reset controls

## 1. Context and production evidence

Recent production investigation found two WhatsApp conversation records for the
same participant. They had the same tenant and channel but different Meta
`phone_number_id` values. The older binding was disabled on October 7, while a
new binding was enabled around the same time. Conversation identity includes
the provider-account ID, so this split is expected and preserves correct
routing; it does not indicate duplicate webhook processing.

On October 12, an operator restart command was recorded for each thread, about
five seconds apart. The transcript rows were retained. For the active thread,
260 messages before the restart boundary remained in the transcript but were
excluded from the working context. The database does not retain the operator's
reason for either restart.

On October 19, the active thread loaded 20 recent messages and lead-journey
context. The model still produced the same reply for three different customer
messages. Production response verification was disabled, so the repeated reply
was not rejected. This shows that durable memory and model instruction alone do
not ensure advisor-like continuity.

## 2. Goals

1. Preserve distinct conversation threads, transcripts, Meta routing, and audit
history across provider-account changes.
2. Carry a small set of explicit, useful shopping facts across threads for the
same participant within the same tenant.
3. Let newer customer statements replace older preferences; preserve a dated
`DECLINED` state until the customer reopens that topic.
4. Expire active shared shopping facts 90 days after their last explicit
   confirmation/update; retain a minimal `DECLINED` suppression until the
   customer explicitly reopens or forgets it.
5. Assemble bounded, relevant context for DeepSeek and every configured
fallback provider.
6. Prevent exact or materially similar replies to different current requests
from being sent again.
7. Make thread restart, customer-fact deletion, and transcript deletion
separate, auditable operations.
8. Keep the complete transcript as the canonical record; do not send the full
transcript to the model.

## 3. Non-goals

- Merging conversation IDs or combining transcripts across Meta accounts.
- Automatically linking changed phone numbers or identities across tenants.
- Storing inferred personality, mood, creditworthiness, or sensitive financial
  identifiers as customer memory.
- Replacing Ford catalog tools with conversation memory or vector retrieval.
- Selecting a new production model before a replay-based evaluation.
- Deleting existing production conversations as a memory repair.

## 4. Architecture

Use three distinct state layers with separate owners:

| Layer | Key | Owner | Purpose |
|---|---|---|---|
| Conversation transcript and working memory | Tenant + channel + Meta provider account + participant | Conversation service | Exact thread history, rolling summary, recent turns, active conversation state |
| Shared customer shopping facts | Tenant + channel + stable participant identity | Customer-memory service | A few explicit facts that should survive a Meta account/thread change |
| Advisor journey state | Conversation ID | Existing lead-journey service | Current topic, visited/paused topics, confirmed lead facts, and pending question |

Do not use the shared customer profile as a transcript or as a second lead
journey. Keep those responsibilities separate.

### 4.1 Stable customer identity

Keep conversation identity unchanged: tenant, channel, provider account, and
participant continue to identify each operational thread. Add a separate
tenant-scoped customer identity mapping that resolves the normalized WhatsApp
participant across provider accounts.

Use a keyed fingerprint of the normalized participant for profile lookup, with
a key version so fingerprints can be rotated. Store an internal customer
identity UUID and link every matching conversation to it. The identity mapping
must include tenant and channel in every uniqueness and lookup predicate.

Do not automatically link identities across tenants or when a customer changes
their phone number. A changed number requires an explicit verified link process.
Provider-account changes do not require transcript migration: new and old
threads remain distinct while sharing only the allowed customer identity.

### 4.2 Shared fact lifecycle

Store an allowlisted, typed current fact plus an append-only change record. Each
fact has:

- customer identity and tenant;
- type and bounded value;
- state: `ACTIVE` or `DECLINED`;
- source conversation and source message;
- stated/updated timestamp and expiry policy: active facts expire 90 days after
  the latest explicit confirmation/update; a declined suppression has no
  automatic expiry and ends only when the customer explicitly reopens or
  forgets it;
- optimistic version for concurrent updates.

Allowed types are explicit shopping preferences: vehicle model/trim, stated
budget, and purchase timeframe. A customer explicitly declining a model/trim
sets that exact preference to `DECLINED`; do not recommend it again unless the
customer reopens it. A decline of a specific offer suppresses only that offer,
not future offers for the vehicle. Declined suppression has no automatic
expiry. A later explicit preference reopens the applicable fact and returns it
to `ACTIVE`. Keep prior changes for audit, but supply only the current active
fact or minimal declined suppression to the model.

Never infer a durable fact from model output. Do not store credit identifiers,
account numbers, CURP/RFC, credit score, income, or provider credentials. Store
the explicit shopping budget only as a bounded preference, not as a general
financial profile. Do not copy raw message text into the shared profile.

### 4.3 Context assembly and advisor progression

Create one provider-neutral `AdvisorContext` per turn. The context assembler
combines, in priority order:

1. The current inbound message, authoritative for this turn.
2. The current thread's bounded recent transcript and rolling summary.
3. Existing lead-journey state, including answered/visited topics and the
   pending question.
4. A small set of relevant, unexpired shared shopping facts with their age,
   source, and state.
5. Current Ford catalog evidence from authorized tools when the request needs
   volatile vehicle, price, promotion, financing, or availability data.

Keep current Ford catalog evidence separate from memory. Memory cannot be a
source for changing prices, offers, or availability. Do not duplicate the
current inbound message in the recent-history window. Apply a hard token/size
budget and preserve the current question and newest thread turns first.

DeepSeek remains the baseline initially. The same `AdvisorContext` goes to
DeepSeek and any eligible fallback; the provider must not assemble or own its
own history. The application owns topic progression and durable state. The
model writes the natural-language response and proposes typed fact updates;
only explicit customer statements and deterministic policy may commit those
updates.

### 4.4 Reply continuity guard

At the final pre-send boundary, compare the candidate response with recent
assistant replies from the same thread. Check exact normalized duplicates and
high semantic overlap. A repeated answer to a materially different customer
request is rejected even if it is polite, safe, and factually correct.

On rejection, retry generation once with the current request, prior answer, and
advisor progress made explicit. Re-run the same guard after repair. If the
reply still repeats or fails to answer the current request, do not send it;
keep the turn in the existing bounded retry/review path. Never replace a
repeated answer with a fabricated canned reply.

This guard is code-owned and enabled in production by default. A setting may
tune similarity thresholds, but production configuration must not disable the
minimum exact-repeat check. Emit only content-free reason codes and aggregate
metrics; never log prompts, replies, or customer identifiers.

## 5. Reset, deletion, and historical thread behavior

Expose separate operations with clear labels and audit records:

1. **Restart this conversation's working context**: clear its summary and
   thread-scoped projection, advance its context epoch, and preserve its
   transcript and shared customer facts.
2. **Forget shared shopping facts**: remove active profile facts for the
   selected customer identity and preserve only the minimum non-content audit
   record required by retention policy.
3. **Delete transcript data**: a separate privacy/retention workflow with its
   own authorization and deletion semantics.

Every restart records operator, timestamp, target conversation, and a required
reason. A thread restart never implicitly forgets shared facts or deletes
messages. Existing older threads whose Meta binding is disabled remain
read-only historical records; do not delete or route new messages through them.

## 6. Failure and consistency rules

| Condition | Behavior |
|---|---|
| Shared profile unavailable | Continue with current thread memory and journey state; do not broaden lookup |
| No matching or expired active shared facts | Omit them; never guess. Keep an explicit declined suppression until reopened or forgotten |
| Conflicting or stale fact | Prefer the latest explicit customer statement; otherwise omit and ask one focused question |
| Concurrent fact updates | Compare-and-set by version; reload and resolve by source-message time, not last model completion |
| Embedding/vector outage | Continue with structured facts and bounded recent thread context |
| Model fallback | Reuse the exact same assembled advisor context and tools policy |
| Repeat guard rejects twice | Do not send; preserve pending/review behavior |
| Tenant or identity mismatch | Fail closed; return no shared facts |
| Provider-account change | Create/use a distinct conversation; link only through verified tenant-scoped customer identity |

## 7. Verification and rollout

### Required tests

- Same participant and tenant through two Meta account IDs creates two distinct
  conversations linked to one customer identity.
- Different tenants never share a profile; different participants never
  collide; changed phone numbers are not silently merged.
- Explicit fact updates replace the active value and retain source/audit data.
- `DECLINED` suppresses that preference until an explicit customer reopen.
- Active facts expire 90 days after last explicit confirmation; declined
  suppression persists until explicit reopen or forget.
- Restarting one conversation preserves transcript, the other thread, and
  customer-level facts.
- DeepSeek and fallback receive the same provider-neutral context.
- The three production questions that received the same reply are replayed;
  exact/semantic repetition is rejected and the repaired result must answer the
  current turn.
- No outbound delivery occurs in replay tests; no transcript or prompt content
  appears in logs.

### Rollout sequence

1. Build a read-only snapshot of the two existing production threads and replay
   the recent turns offline with delivery disabled.
2. Add identity linking and fact storage behind a disabled-by-default write
   flag; verify account-to-identity links and tenant isolation.
3. Shadow-assemble shared facts for synthetic/internal turns and measure
   relevance, staleness, and token use without affecting live replies.
4. Enable the always-on repeat guard and validate repair/suppress behavior on
   the production conversation replay before customer rollout.
5. Enable shared facts for a reviewed pilot, inspect content-free metrics and
   operator review, then expand only after fact freshness, relevance, and
   privacy gates pass.
6. Keep old Meta-account conversations read-only. No transcript deletion or
   bulk memory reset is part of this rollout.

## 8. Acceptance criteria

- Threads remain separate and correctly routed by Meta provider account.
- A verified same-tenant participant can carry only approved explicit shopping
  facts across those threads.
- Changed and declined preferences behave as specified; stale facts expire.
- A thread restart cannot erase shared facts or transcript history.
- The active context is bounded, current-turn-first, and identical across
  providers.
- Repeated replies are blocked at the final send boundary even when a model
  ignores prior context.
- Tests prove tenant isolation, no accidental identity merge, and no outbound
  delivery from shadow replay.
