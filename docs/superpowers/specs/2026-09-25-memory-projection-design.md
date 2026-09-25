# Deterministic Conversation Memory Projection

**Status:** Approved for implementation

The exact inbound/outbound transcript is the source of truth. After a bot turn
is accepted, the application updates the existing versioned projection with:

- a bounded summary containing the latest user request; and
- bounded facts extracted only from explicit phrases such as “me llamo…”, “vivo
  en…”, “prefiero…”, “busco…”, or their English equivalents.

New values replace older values of the same fact kind. The projector never
uses an LLM, never reads assistant text as a fact source, and never blocks
outbound delivery. The existing optimistic `memory_version` prevents stale
concurrent turns from overwriting a newer projection.

Semantic memory remains disabled until transcript and projection quality can be
measured independently.
