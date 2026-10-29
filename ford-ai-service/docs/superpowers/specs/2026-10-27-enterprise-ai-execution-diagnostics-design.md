# Enterprise AI Execution Diagnostics and Replay Design

**Status:** Proposed for review  
**Date:** 2026-10-27  
**Scope:** `Ford-uruapan/ford-ai-service`

## Goal

Make every assistant decision diagnosable and reproducible across inbound message handling, conversation workflow, AI orchestration, model calls, tools, catalog/data access, validation, and outbound persistence. Deterministic policy and routing should be testable independently of model prose. A recorded run should identify the first boundary where behavior diverged without weakening safety gates.

This does not promise identical generated prose from a live model. Exact replay uses recorded model/tool/data outputs; live model calls remain probabilistic.

## Evidence From the Current Replay

The latest prefetched Qwen replay stopped at turn 30: 29 turns committed. Turn 30 asked, “¿Qué dato no está disponible para hacer una comparación completa?” Its reply asked which model/version to confirm. The safety gate reported `MISSING_CATALOG_EVIDENCE`; the report recorded comparison status `NOT_A_COMPARISON`, a replay-prefetched `get_vehicle` result with 27 evidence items, and no model-selected tool call. Turn 29 had received positive `compare_trims` replay evidence. This proves the comparison-prefetch path was not used on turn 30; it does not prove the catalog lacks comparison or safety facts.

The partial transcript reports catalog-claim precision of 40% (10 supported of 25 scored; 15 unsupported). This is currently diagnostic, not a gate. Turns 13–16 include engine, power, torque, and safety claims not supported by the evaluator’s same-turn evidence. Each claim and evidence payload must be inspected before concluding the underlying catalog lacks those facts; the evaluator uses deterministic text matching and may have false negatives.

The integration run completed 7 tests: 1 failed and 3 were skipped. The raw replay was skipped because prefetch was enabled. Test startup diagnostics showed tracing and metrics disabled. Existing tool logs report input shape and duration, not complete, replayable request/response payloads. `ConversationGraphConfiguration` currently wraps turn processing in one `process_turn` node; `SpringAiAssistantService` still performs most AI orchestration linearly.

## Design

### 1. Correlated execution trace

Create an execution/turn trace context at the inbound receipt boundary. Propagate stable `traceId`, `tenantId`, `conversationId`, `receiptId`, `providerMessageId`, and monotonic event sequence through graph execution, model calls, tools, data-access operations, validation, and outbound persistence.

Write typed, append-only events through an application-owned trace sink. Each event records operation/node name, start/end, outcome, duration, attempt number, safe error class, and relevant version identifiers. Event ordering must make the first failing boundary obvious.

### 2. Boundary coverage

- **Workflow nodes:** node entry/exit, selected route, state version, checkpoint/resume, and safe state diff. Keep sensitive payloads out of LangGraph checkpoints.
- **Model calls:** provider/model/version, prompt-template and system-prompt version/hash, generation parameters, token usage, retry/fallback, and exact post-security-policy request/response snapshots.
- **Tool calls:** authorized tool name, exact sanitized arguments, result snapshot/reference, evidence count/provenance, duration, outcome, and retry. Model-selected tools and replay-prefetch operations remain distinct origins.
- **Database/data access:** repository/domain operation, SQL fingerprint (not raw SQL with values), parameter names/types and redaction state, rows returned, duration, transaction outcome, and an encrypted result snapshot/reference only when policy allows. Do not emit every raw row to ordinary logs.
- **Policies and answer validation:** input-security decision, routing/scope decision, evidence-gate result, safety violation code, and bounded repair/handoff decision.

Instrument at stable application/adaptor boundaries first. Do not add a generic JDBC interceptor that captures unrelated queries or sensitive binds by default.

### 3. Privacy and storage

Ordinary application logs contain metadata only; never raw prompts, credentials, secrets, or personal data. Capture the exact model-visible request only after input-security redaction. Apply the same secret/PII sanitization policy to model outputs, tool results, database snapshots, and tool arguments before storing payloads; retain hashes and redaction metadata where a field is removed. Store approved sanitized snapshots separately, encrypted, tenant-scoped, access-controlled, and with audited reads/deletes. Retention must be configurable and production activation blocked until a short retention value is approved. Payload storage failure must not silently alter model policy or safety behavior; required trace gaps must be visible and test-failing in diagnostic/replay mode.

### 4. Deterministic replay

A replay runner consumes a trace plus versioned fixture/snapshot references and supplies recorded model, tool, and data-access outputs. It must make no live provider or production database calls. It compares node decisions, tool arguments, evidence, validation outcomes, and final route; generated text is compared exactly only when replaying the recorded response, not by re-calling the model.

Pin model/provider, prompt, tool schema, application build, catalog snapshot, and policy versions. Keep replay outputs separate from production traffic and raw-Qwen benchmarks.

### 5. StateGraph role

Use StateGraph for explicit workflow control and node-level observability where a state transition or recovery boundary matters. Do not move every repository method into the graph or build one giant node. Keep business rules, tenant authorization, catalog access, and safety validation in independently tested services. First add trace events around the existing graph and AI boundaries; then decompose `process_turn` incrementally only where it improves diagnosis or recovery.

## Delivery Order

1. **Close current replay failure.** Reproduce turn 30 at the real webhook-to-prefetch boundary. Compare sanitized current message, recognized intent, exact-pair cache/history, catalog-name entries, selected prefetch branch, and evidence trace. Add a fast regression before changing behavior; preserve all safety gates.
2. **Trace contract and failure-path instrumentation.** Add correlated events and safe decision diagnostics for the failing path. Prove event ordering, redaction, and trace completeness in focused tests.
3. **Instrument model and tool boundaries.** Capture post-policy model request/response and tool inputs/results with explicit origins and versions.
4. **Instrument data-access boundaries.** Add operation-level DB/repository metadata and approved encrypted snapshots; verify redaction and retention.
5. **Add deterministic replay.** Replay the captured failure without live Qwen or live catalog calls; confirm it fails at the same stage before applying a fix, then passes after.
6. **Expand graph nodes and full acceptance.** Decompose only selected workflow stages. Run unit tests, focused integration tests, then the full 100-turn prefetched replay and raw/prefetched separation checks.

## Acceptance Criteria

- Every inbound turn has a correlated, ordered trace spanning all executed workflow, model, tool, data-access, policy, and persistence boundaries.
- The trace identifies the first failed/missing boundary and distinguishes model-selected tool use from prefetch evidence.
- Exact replay reproduces recorded decisions/results without live model or production data calls.
- Ordinary logs contain no raw prompt, secret, credential, or personal data; payload access and expiry are testable and audited.
- Trace-store outage behavior is explicit, observable, and does not bypass safety checks.
- Current local benchmark reaches 100/100 committed turns with zero handoffs and all existing safety gates passing. Precision metrics remain diagnostic unless separately approved as a gate.
- Raw replay remains separate and cannot accept replay-prefetch evidence.

## Non-Goals

- Guarantee deterministic prose from repeated live LLM calls.
- Treat absent evidence as proof that the catalog database lacks a fact.
- Weaken safety gates to obtain a green replay.
- Log unredacted production data in standard logs or LangGraph checkpoints.
- Replace all application services with graph nodes.
