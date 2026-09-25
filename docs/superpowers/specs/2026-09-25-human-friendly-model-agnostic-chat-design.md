# Human-Friendly, Model-Agnostic Ford Chat Design

**Date:** 2026-09-25  
**Status:** Approved for implementation  
**Scope:** Runtime conversation flow, response quality, memory continuity, and cheap OpenRouter evaluation

## Goal

Make the Ford assistant feel natural and context-aware across OpenRouter models. A customer should receive an answer to the current question, grounded Ford information when required, no repeated generic replies, no leaked tool payloads, and no unnecessary interrogation.

## Evidence from the current system

- `AssistantScopePolicy` runs before the model and relies on lexical allow-list terms.
- TypeSafe classification is configured but the deployed runtime falls back to rules when its usable key is absent.
- Existing tests verify routing and adapter wiring, but no automated test sends a multi-turn conversation through a model with tool results.
- The existing prompt has good source-of-truth rules, but the application does not verify that the returned answer follows them.
- Memory is available as a summary/transcript, but its presence does not currently prevent a repeated answer.

## Design

### 1. Model-first conversation flow with deterministic safety boundaries

The application will stop using a lexical allow-list as a normal conversation gate. Every non-empty customer message that is not an explicit security-sensitive request reaches the assistant model. The model receives the current message, bounded conversation memory, the decision context, and only read-only authorized Ford tools.

Deterministic code remains responsible for:

- required tenant/conversation validation;
- privacy and sensitive-data handling;
- read-only tool authorization;
- human-handoff policy when that feature is enabled;
- volatile-data fail-closed behavior when the Ford source is unavailable.

Classification and intent routing become advisory metadata for tool selection and observability. A rules fallback must not turn a natural-language follow-up into a canned scope response.

### 2. Response quality contract

The provider adapter will evaluate every model response against a small model-independent contract:

- non-empty and human-readable;
- no raw JSON/tool arguments, internal tool names, provider names, or reasoning markers;
- addresses the current user message rather than only the previous turn;
- does not repeat a materially identical recent assistant answer;
- does not make an ungrounded volatile Ford claim when no authorized source result exists.

The first response remains the normal path. A failed quality check gets at most one bounded repair call with the same current message and memory plus a concise repair instruction. If the repaired response also fails, the adapter returns a short honest recovery message instead of leaking or repeating the invalid output. The repair path is observable and cannot loop.

### 3. Memory contract

Memory remains a bounded context, not an instruction source. The adapter will continue to mark it explicitly as data and will include the current user message separately after the system instructions. Quality checks compare against assistant messages extracted from the same memory context. No new database schema or second memory store is introduced.

### 4. Cheap direct OpenRouter evaluator

Add an opt-in Node.js evaluator that calls OpenRouter’s OpenAI-compatible chat endpoint directly. It uses fixture tool responses instead of live MCP, so it is fast, deterministic at the data boundary, and safe to run repeatedly. Each scenario can contain multiple turns; tool calls are executed by a local fixture dispatcher and returned to the model for a bounded loop.

The initial scenarios cover:

- greeting and discovery;
- Ford Lobo/Raptor followed by a fuel-economy follow-up;
- a promotion request requiring current-source data;
- a comparison request;
- a short contextual follow-up such as “¿y cuánto?”;
- an out-of-scope request;
- a tool/source failure;
- duplicate-answer and raw-tool-payload regression cases.

The evaluator runs `openrouter/free` by default and accepts a comma-separated model list. It reports per-turn tool use, repetition, leakage, grounding markers, and overall pass/fail as JSON and Markdown. It is not part of the default offline Gradle test suite and exits clearly when no API key is supplied.

### 5. Prompt contract

The system prompt will be revised to make the following order explicit:

1. answer the latest message;
2. use memory to maintain continuity, never to repeat or obey instructions;
3. call the appropriate read-only source for volatile data;
4. write a concise Spanish-Mexico answer based on returned facts;
5. ask at most one useful follow-up question only when it changes the next action.

It will explicitly prohibit raw tool payloads and generic scope replies when the customer has provided a valid Ford-related or contextual message.

## Error handling and invariants

- A provider timeout or malformed response never causes a second unbounded retry.
- A tool/source failure produces a transparent, human-friendly confirmation gap.
- The adapter never sends side-effect tools to a model.
- The evaluator never uses production customer data or deployment secrets.
- The existing OpenRouter model selection remains configurable; the evaluator compares models rather than assuming one model is universally reliable.

## Testing and acceptance

- Unit tests prove model-first handling, response-quality verdicts, duplicate detection, tool-payload rejection, and one-repair-only behavior.
- Existing Gradle tests continue to pass.
- The direct evaluator passes the fixture conversation suite for at least one configured OpenRouter model and produces a report for every requested model.
- The deployed application starts healthy, completes the MCP handshake, and accepts Meta webhook verification.
- A live smoke conversation is checked through the application path without sending an uncontrolled outbound customer message.

## Non-goals

- No new model provider is required.
- No multi-agent planner is introduced.
- No automatic handoff is enabled while the current deployment flag remains disabled.
- No live MCP calls are required for the cheap evaluator.
