# Conversation Memory Repair Design

**Status:** Approved for implementation

## Observed failure

The workflow records the inbound message and loads memory before the model call,
but after a successful assistant turn it only invalidates the memory cache. The
`ConversationMemoryPort.recordOutbound` capability is never called by the
production workflow. Summary/fact projection methods also have no active caller.
As a result, the next turn sees an incomplete transcript containing user turns
without assistant turns, which explains repeated answers and poor follow-up
continuity.

## Chosen repair

Persist the assistant response only after the conversation repository accepts
the optimistic bot-turn update. Use a deterministic provider message ID scoped
to the conversation and inbound provider message ID, so duplicate event
processing cannot create a second assistant row. Keep the write fail-open and
publish the existing outbound delivery event after the memory write attempt.

Frame the loaded context as historical data, not instructions, and state that
the current user message is authoritative. Keep semantic retrieval and
model-generated summaries disabled until the corrected transcript is measured.

## Non-goals

- No schema migration.
- No new model call for summarization.
- No change to MCP tool authorization.
- No change to tenant IDs, webhook contracts, or outbound delivery semantics.
