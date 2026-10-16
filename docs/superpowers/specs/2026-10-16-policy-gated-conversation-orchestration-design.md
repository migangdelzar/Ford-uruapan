# Policy-Gated Conversation Orchestration Design

**Status:** Draft for review  
**Date:** 2026-10-16  
**Scope:** Ford Service conversation reply, tool authorization, and memory context

## Problem

The production interaction ending in 3925 had repeated generic replies. Sanitized
logs show that two turns were classified as `GENERAL` with `toolFamily=NONE` and
returned by `AssistantScopePolicy` before normal assistant generation. The
conversation memory remained loaded (versions 145 and 146, 20 recent messages),
so this was not a memory reset. A separate model call compacted two older turns;
that call was background memory work, not a customer reply.

The reply path should let the model handle ordinary supported conversation and
follow-ups while keeping policy checks in charge of domain boundaries, tools,
facts, and actions.

## Goals

- Let the LLM compose every supported customer-facing conversational reply,
  including contextual turns classified as `GENERAL`.
- Use the intent and tool family to grant the model a narrow, read-only tool set.
- Never grant tools when the tool family is `NONE` or the decision has no
  usable evidence.
- Keep exact, changing vehicle facts grounded in MCP results. A failed or empty
  MCP result must not become an invented price, specification, promotion, or
  availability claim.
- Preserve the canonical transcript and durable user facts across compaction;
  compaction must not reset or delete conversation history.
- Emit content-free reason codes at policy and response-quality boundaries.

## Non-goals

- No multi-agent architecture, new model provider, or classifier replacement.
- No changes to channel webhook deduplication, outbound delivery, or handoff
  consent semantics.
- No automatic production deployment in this work. Release remains a separate
  blue/green rollout after local verification and explicit release readiness.
- No logging of customer prompts, generated answers, or raw tool payloads.

## Recommended Architecture

Keep the current LangGraph turn lifecycle and hexagonal service boundaries. Make
the assistant path a policy-gated model flow:

```text
Inbound receipt and deduplication
  -> load canonical transcript and memory projection
  -> classify intent and determine capability policy
  -> reject only clear out-of-domain or unsafe requests
  -> LLM composes the reply with only policy-authorized tools
  -> validate grounded claims and response quality
  -> commit reply and durable outbox event
```

`GENERAL` is not by itself a reason to return the fixed Ford menu. In a supported
Ford conversation, the LLM receives the current message and bounded context and
may answer naturally. A clearly unrelated request remains blocked by the
deterministic boundary. The assistant should acknowledge uncertainty and offer
one useful next step when current catalog evidence is unavailable.

### Tool authorization

`DecisionPolicy` remains the capability allowlist. `ToolFamily.NONE`, missing
evidence, and memory-compaction requests produce an empty tool list. In
particular, the empty-evidence path must fail closed instead of exposing all
read-only tools. A decision that authorizes a family exposes only registered
tools in that family's allowlist. MCP is called only when the turn requires
current facts or the model selects an authorized relevant lookup.

### Memory

PostgreSQL remains the canonical transcript. The model context is a projection:
stable summary, durable user facts, complete recent turns within a bounded
context budget, and relevant archived turns when recall is warranted. Older
turns may be compacted into the summary, but the source transcript remains
available. Compaction is a no-tools model task and has its own safe failure
behavior; it cannot trigger customer actions or catalog lookups.

The initial implementation should preserve existing memory storage and
compaction semantics while correcting tool exposure and recording safe
compaction outcomes. Moving from message-count bounds to token-aware budgeting
is included only if the current framework/project already provides a supported
token estimator; otherwise capture it as a separate follow-up design to avoid
adding an unverified tokenizer or broadening this change.

### Diagnostics

Log stable fields only: classified intent, tool family, scope decision reason,
authorized tool names/count, memory version and message count, evidence status,
provider/attempt, and response validation reason. Never log message contents,
summary text, or tool payloads.

## Failure Behavior

- Clear out-of-domain request: short boundary response; do not call tools.
- Supported conversational turn with no volatile data need: LLM response using
  current context; no tools unless the policy allows them.
- Exact current fact required: use authorized MCP tools. If data is missing or
  MCP fails, state the limit and answer only any safe general portion.
- Invalid or repetitive model output: bounded repair and configured fallback;
  if still invalid, concise recovery response with a content-free diagnostic
  reason.
- Memory compaction failure: retain the last valid summary and canonical
  transcript; never block delivery of the current reply.
- Outbound delivery failure: preserve current durable outbox retry behavior.

## Test Strategy

- Scope tests: supported `GENERAL` follow-up reaches the LLM; explicit unrelated
  request remains blocked; fixed menu is not emitted for the supported case.
- Authorization tests: `NONE`, empty evidence, and memory compaction expose no
  tools; each supported family exposes only its allowlist.
- Assistant tests: general answer with no volatile requirement; MCP-required
  exact fact with successful, empty, and failed tool results; unsupported exact
  claims withheld; repair/fallback remains bounded.
- Memory tests: context version and durable facts survive compaction; transcript
  is retained; compaction receives no tools; compaction failure preserves the
  previous summary.
- Regression eval: synthetic, content-safe cases reproducing the scope-guard
  bypass and repeated generic response. Include unrelated and prompt-injection
  requests to ensure the broader LLM path does not widen scope.
- Run Ford Service unit tests, integration tests relevant to persistence and
  tool authorization, application build, and `git diff --check` locally before
  release.

## Rollout

Implement and verify locally first. Build and deploy a candidate to the inactive
blue/green slot using the existing GitHub Actions release flow. Verify health,
MCP behavior, response reason-code metrics, and a synthetic conversation before
promoting traffic. Keep the prior image available for rollback; do not remove a
running/previous container as part of this change.

## Acceptance Criteria

1. A supported contextual `GENERAL` turn is answered by the LLM rather than
   the fixed scope response.
2. Clear unrelated and unsafe requests still stop before model/tool execution.
3. Tool family `NONE`, missing evidence, and compaction cannot expose tools.
4. Current vehicle claims remain grounded in MCP evidence; no exact unsupported
   facts reach the customer.
5. The full canonical transcript is retained across memory compaction, and
   compaction failure does not erase prior memory or block the current reply.
6. Logs distinguish scope, model, tool, quality, and compaction outcomes without
   storing prompt or answer text.
7. All verification occurs locally before any production rollout.
