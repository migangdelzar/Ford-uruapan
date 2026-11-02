# Text Conversation Memory Retrieval Design

## Status

Proposed for user review. This design covers text-only conversational memory. It does not change image/audio handling, catalog retrieval, sales ranking, conversation graph, webhook, or production data.

## Problem

The current assistant prompt uses a bounded recent-message window (20 messages by default), durable extracted facts, and a compact summary. This is safe and simple, but relevance is mostly recency-based: an older customer constraint or decision can be omitted even when it remains important. Repeatedly compacting prior summaries can also lose detail over long conversations.

## Goals

- Select the most useful text context for each assistant turn, not merely the last N messages.
- Preserve hard constraints, corrections, selected options, unresolved questions, and decisions until they are explicitly superseded or resolved.
- Retain a short recent window for conversational continuity and retrieve relevant older turns when useful.
- Bound prompt context by a configurable token budget.
- Rebuild compact memory from the canonical transcript and structured facts, not only from a prior generated summary.
- Use query expansion selectively for memory retrieval; never alter the user's message or its hard constraints.
- Keep complete transcript data as the source of truth. Selection only controls prompt context; it does not delete transcript history.

## Non-goals and constraints

- Text messages only. Existing image/audio interpretation and retention behavior remains unchanged.
- No query expansion for catalog search, no semantic caching, and no alternate conversation graph or workflow.
- No unconditional model-generated multi-query expansion. Memory retrieval uses the original message by default; optional expansion is deterministic and derived only from already-known conversation facts.
- No production/`emme-2` writes, transcript import, or live 100-turn replay during implementation.

## Proposed behavior

Build each prompt's memory context from four ordered groups:

1. **Current turn:** include the exact current user message separately, without rewriting it.
2. **Protected active state:** include current, sourced facts and constraints (for example, intended use, budget, chosen vehicle/year/trim, corrections, decisions, and unresolved questions). A newer explicit correction supersedes an older fact of the same kind; do not infer a value from silence.
3. **Recent turns:** include a small configurable window of complete recent text turns for local conversational continuity.
4. **Relevant older turns:** retrieve older transcript turns by relevance and recency, deduplicate them against recent turns, then include the highest-ranked items that fit the remaining budget. Preserve source message IDs/timestamps for traceability.

The selector must never drop protected state to fit optional history. If protected state plus the current message exceeds budget, compact only redundant wording while preserving the original values and provenance; if still over budget, return a safe bounded context and record a diagnostic rather than silently truncating a hard constraint.

### Selective memory query expansion

- Run normal retrieval with the original current message first.
- Expand only when the message is context-dependent/underspecified (for example, a pronoun such as “the other one”) or retrieval returns too little relevant evidence.
- Use deterministic resolution from protected active state (such as the two previously compared trim names). Keep the original query and add at most one grounded alternate query; do not invent attributes, broaden explicit constraints, or replace exact names/years/budgets.
- Merge and deduplicate candidate turns, rerank against the unchanged original intent and active constraints, and keep only results that fit the memory budget.
- Skip expansion when base retrieval already supplies sufficient evidence. Record whether expansion ran and candidate counts, not raw private message text.

### Memory rebasing

- Treat the durable text transcript and sourced facts as authoritative.
- Rebuild/update the compact summary from a bounded transcript slice plus current structured facts, rather than summarizing only the previous summary.
- Apply updates with the existing optimistic memory version so stale asynchronous projections cannot overwrite newer state.
- Summary projection may remain asynchronous after a committed turn. The next turn must still receive the durable recent window and protected state if a projection is delayed or unavailable.

## Failure behavior

- Retrieval or expansion failure: use protected state plus recent turns; do not fail the customer turn.
- Summary projection failure: keep the last valid summary and durable transcript; do not erase facts.
- No relevant older turn: use current turn, protected state, and recent turns only.
- Conflicting facts: prefer the latest explicit correction; preserve provenance and do not merge incompatible values.

## Evaluation and acceptance

- Deterministic text-only conversations cover old but active budget/vehicle constraints, later explicit corrections, unresolved questions, resolved social turns, ambiguous references, and retrieval failure.
- Assert protected facts survive selection, resolved low-value turns can be omitted, recent conversational context remains, and hard model/year/trim/budget constraints are unchanged.
- Assert query expansion is skipped for clear messages, runs for a context-dependent reference only when needed, uses only known values, and does not alter catalog queries.
- Assert token-budget compliance, deduplication, provenance, stale-version rejection, and safe fallback when retrieval/projection is unavailable.
- Report selection quality separately (required facts retained, irrelevant turns excluded, retrieval latency); do not use a single LLM-judge score as the acceptance gate.

## Trade-offs

- Hybrid selection is more robust than last-N alone, but adds ranking, token-budget, and versioning complexity.
- Deterministic, memory-grounded expansion has lower drift risk than unconstrained LLM multi-query generation, but may miss some semantic paraphrases.
- Full transcript retention supports audit and rebuilding, while prompt selection controls token use; retention/privacy policy remains separate and unchanged.

## Decisions

- Default approach: hybrid priority + recency + relevance retrieval.
- Query expansion: conditional, deterministic, memory-only, at most one alternate query.
- Catalog retrieval: unchanged.
- Media processing: unchanged and out of scope.
