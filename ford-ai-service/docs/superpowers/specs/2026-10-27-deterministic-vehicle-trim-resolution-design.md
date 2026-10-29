# Typed Conversation Stage Outputs and Deterministic Vehicle Resolution

## Goal

Define and validate explicit outputs at every conversation-processing stage so routing, catalog grounding, clarification, safety, and handoff behavior are deterministic and auditable. Use partial vehicle-trim resolution as a regression case.

## Current State

The Java service already has typed pieces, including `InputSecurityPolicy.Result`, `ConversationDecision`, `ClassificationEvidence`, `DecisionTraceEvent`, `AssistantResponse`, and `AssistantTurn`. It does not have one complete contract across every stage. Some stage results remain implicit or loosely represented, and there is no single validated transition model tying them together.

Current replay evidence:

- The validated Oct 25 export contains Ranger 2027 XLT variants.
- Replay preflight successfully looks up `Ranger / 2027 / XLT Gasolina 4x2` and its facts.
- Live turn 73 asked about a Ranger XLT 2027 bed measurement. `get_vehicle` returned zero evidence and the safety gate stopped the replay.
- The exact lookup requires a canonical trim string. Its fallback can report no model-year options when the actual mismatch is a partial/ambiguous trim.
- The exact tool arguments are not retained in the safe replay transcript; partial-trim input is a leading hypothesis, not a confirmed cause.

## Recommended Architecture

Keep the existing Java/Spring flow. Do not migrate to LangGraph or introduce one global bag of optional fields. Add immutable records, enums, and—where useful—sealed result types at existing stage boundaries. Each stage validates its input and emits one explicit outcome. Deterministic code owns authorization, catalog matching, evidence checks, safety, and handoff. Models may classify or draft only within validated schemas.

### Stage Output Contracts

| Stage | Output contract | Required outcomes / invariants |
|---|---|---|
| Input security | `SecuredTurn` | `ALLOWED` with sanitized input, or `BLOCKED` with a bounded reason. No unsafe input reaches later stages. |
| Classification | `ClassificationResult` | Intent, tool family, bounded confidence/evidence, and next action. Low confidence is explicit; it cannot silently broaden tool access. |
| Entity resolution | `EntityResolutionResult` | Canonical model/year/trim identity and `EXACT`, `AMBIGUOUS`, `MISSING`, or `NOT_REQUIRED`. Ambiguity includes only validated catalog choices. |
| Scope and routing | `RouteResult` | `ALLOWED` or `DENIED`, selected route, and policy reason. Denied scope has no authorized catalog tools. |
| Tool authorization | `AuthorizedToolSet` | Exact allowlisted tool names for this turn. Later stages cannot add tools. |
| Tool execution | `ToolExecutionResult<T>` | `RETURNED`, `EMPTY`, or `FAILED`, with bounded tool identity and safe failure code. No exception or empty payload is reinterpreted as evidence. |
| Evidence assembly | `EvidenceResult` | `SUPPORTED` with source-backed evidence references, `UNKNOWN`, or `CONFLICTED`. Evidence records retain source and freshness metadata. |
| Answer generation | `AnswerDraft` | Draft plus claims/evidence references, or explicit `CLARIFICATION`. Every factual claim must map to valid evidence. |
| Quality and safety | `QualityDecision` | `PASS`, `REPAIR`, `CLARIFY`, `HANDOFF`, or `BLOCK`, with bounded reason codes. Safety and handoff policy remain authoritative and unchanged. |
| Finalization | `TurnResult` | Approved response, provenance, handoff status, and final outcome. No unvalidated draft is sent. |

Stages that are not applicable return an explicit `NOT_REQUIRED`/`SKIPPED` result rather than an ambiguous null. Avoid duplicating existing domain data: wrap and validate current types where they already express the contract.

### Deterministic Trim Resolution

Build the vehicle index from all validated model/year/trim entries and verify it against the imported snapshot. Resolve buyer wording against canonical entries before exact lookup:

- One exact canonical match: use it.
- A short name matching multiple variants (for example, `XLT`): return `AMBIGUOUS` with the exact catalog choices and ask the buyer to select one.
- No match: return `MISSING` or `UNAVAILABLE` accurately; never claim the model/year is absent solely because a trim did not match.
- Never fuzzy-select or silently substitute a trim.

Use existing structured JSON tool calls, validated against canonical values. A stable variant ID can replace the model/year/trim tuple only if it preserves existing authorization and evidence provenance without expanding scope unnecessarily.

### State Transitions

The orchestrator advances only when the preceding output permits it:

`SecuredTurn → ClassificationResult → EntityResolutionResult → RouteResult → AuthorizedToolSet → ToolExecutionResult → EvidenceResult → AnswerDraft → QualityDecision → TurnResult`

`BLOCKED`, `DENIED`, `AMBIGUOUS`, `UNKNOWN`, `CLARIFICATION`, and `HANDOFF` are terminal or explicitly routed outcomes; none may fall through as a normal factual answer. Some paths may skip catalog/evidence stages, but must record an explicit skip reason.

### Trace and Privacy

Extend bounded decision tracing to record stage, outcome, and reason code. Include counts or canonical internal identifiers only where existing privacy rules permit. Do not add raw buyer text, full tool arguments, personal data, or secrets to logs, telemetry, or ordinary replay artifacts. For `get_vehicle`, diagnostic shape may indicate whether year/trim were present and whether matching was exact, ambiguous, or unmatched; it must not emit raw arguments.

## Safety and Compatibility

- Preserve existing handoff rules and safety gates; this design does not relax either.
- Missing evidence means `UNKNOWN`, not absent or false.
- A candidate-trim list is not evidence for a variant-specific fact.
- Keep raw and prefetched replay paths and artifacts separate.
- Keep seeded replay diagnostic only. Final acceptance remains a fresh, unseeded 100-turn Qwen run with zero handoffs.
- Add contracts around current service boundaries incrementally; no broad framework migration.

## Verification Plan

1. Contract unit tests for every outcome, invalid transitions, and required fields.
2. Stage tests proving security blocks stop, low-confidence classification cannot broaden tools, ambiguous entities request clarification, empty/failed tools do not create evidence, unsupported claims cannot pass quality, and handoff policy remains unchanged.
3. Catalog tests proving the cache includes every validated trim, exact trim resolves, partial `XLT` returns both exact variants, and unknown trims remain unknown.
4. Integration tests against the validated snapshot proving canonical Ranger 2027 XLT lookup returns source-backed facts.
5. Focused replay test seeded through turn 72 with turn 73 live; require either an exact buyer-specified variant or safe clarification, with safety checks active.
6. Resume progressive replay only after focused tests pass. Run final fresh unseeded 100-turn acceptance only after diagnostics complete.

## Implementation Boundaries

- First map every existing service boundary to these contracts and identify which current records can be reused.
- Add contracts in slices, starting with entity resolution/catalog evidence because of the turn-73 failure; then classification/routing, answer/quality, and finalization.
- Avoid a wholesale rewrite of `SpringAiAssistantService` or new graph framework unless implementation evidence proves existing boundaries cannot enforce the contracts.
- Before replaying turn 73, add allowlisted, privacy-safe match-shape diagnostics and verify the failure with focused tests.
