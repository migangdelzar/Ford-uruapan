# Yearless Trim Ambiguity and Replay Precision

## Problem

The bounded local replay now resolves a yearless `get_vehicle("Ranger", null, "XLT")` lookup, but the result can contain multiple canonical variants. The approved local snapshot has both `Ranger 2027 XLT Diésel 4x4` and `Ranger 2027 XLT Gasolina 4x2`, each with its own price/comparison URL. On turn 31, the reply described the gasoline trim and linked to the diesel page.

The current source-link collector recursively flattens links from a tool payload and takes up to two URLs without binding each URL to the variant named in the answer. The evidence-grounded policy can therefore accept a URL that belongs to a different choice in the same payload.

The replay's `79%` catalog-claim precision is also not an end-to-end accuracy score. `LocalReplayPrecisionEvidence` stores replay-prefetched evidence only. It does not store results returned by model-invoked tools such as `get_vehicle`, while the evaluator compares the answer against that incomplete evidence set and can label supported claims unsupported.

## Goals

1. Prevent the assistant from stating variant-specific facts or linking to a variant-specific source when a yearless trim alias resolves to multiple candidates.
2. Make replay precision diagnostics explicit about evidence coverage; never label a claim unsupported solely because model-tool output was not captured by the diagnostic collector.
3. Preserve exact-year lookup behavior, yearless model-only options, local-only replay boundaries, and all safety/no-handoff checks.

## Design

### Yearless ambiguous vehicle lookup

For `get_vehicle` calls with a model, no year, and a trim:

1. Resolve the trim against the canonical name index across candidate model years, as implemented by the prior alias-resolution change.
2. If there is one canonical candidate total, return that candidate's evidence as today.
3. If there are multiple candidates, return a dedicated ambiguity result containing only canonical model/year/trim identities. Do not include prices, facts, or variant-specific URLs in the ambiguous result.
4. Extend ambiguity tracking to accept candidates spanning multiple years. The adapter returns a deterministic clarification asking the buyer to choose a year and exact version; it does not ask the model to synthesize facts from the choices.
5. Keep the no-trim path, exact-year lookup path, and existing exact-year ambiguity behavior unchanged.

### Source-link binding

Make `CatalogFactResponseRenderer.appendSourceLinks` fail closed for variant-specific links when a payload contains multiple vehicle candidates. A trim-specific price or comparison URL may be appended only when it is bound to one exact vehicle identity. A shared model-page URL may be retained if it is common to the candidates. Never choose a link by traversal order.

### Replay precision reporting

The replay collector currently captures prefetched evidence but not model-invoked tool payloads. Until all relevant tool payloads are captured, report the metric as **replay-prefetch-only catalog claim precision**. For any turn that used a model-invoked catalog fact tool whose result is not in the collector, mark catalog claims unscored for that turn rather than unsupported. Retain precision scoring for turns where the evidence set is complete for the claims being checked. Include an explicit count of turns/claims excluded for incomplete evidence.

Do not serialize raw tool arguments, full tool payloads, secrets, or production data into replay artifacts.

## Alternatives Considered

- **Keep all variant facts and add stronger prompt instructions:** Minimal API change, but depends on model behavior and still exposes cross-variant URLs. Rejected as insufficiently fail-closed.
- **Keep detailed choices and add URL-to-claim matching only:** Useful defense in depth, but leaves ambiguous facts available for unsupported synthesis. Not selected as the primary control.
- **Return identity-only ambiguity and ask the buyer to choose (selected):** Prevents cross-variant fact/link mixing at the source and keeps candidate identities visible without choosing for the buyer.
- **Capture every model-tool payload for precision scoring:** Could produce a more complete score, but requires broader instrumentation and data retention. Defer; use an explicitly scoped metric and unscore turns with incomplete evidence.

## Acceptance Criteria

- Yearless `XLT` with multiple exact catalog candidates returns an ambiguity result containing all candidate identities and no prices, facts, or variant URLs.
- The assistant turns that result into a deterministic clarification; no candidate is selected implicitly.
- A single unique yearless candidate still returns grounded evidence; model-only and exact-year behavior remain unchanged.
- Source-link collection never appends a trim-specific URL from an ambiguous multi-variant payload based only on result ordering.
- Tests prove a Gasolina claim cannot receive a Diésel URL from the same ambiguous result.
- Precision reporting labels its scope as prefetch-only and marks turns with uncaptured model-tool evidence unscored, not unsupported.
- Focused unit tests and non-live integration gates pass before another live replay.
- A bounded local replay may run only with the approved snapshot and disposable PostgreSQL, capped at 32 turns, with no handoff and all safety checks enabled.
- The 100-turn acceptance remains paused until the historical unsupported-price discrepancy is explained. No production/`emme-2` writes, catalog import, or transcript seeding.

## Latest Bounded Replay — 2026-10-29

- Run ID: `7d4daebccbdd4cf1b005e88aa421f188` (approved `oct25-candidate` snapshot; local `qwen3.5:4b`; disposable Testcontainers PostgreSQL).
- Outcome: turns 1–30 committed. Turn 31 entered `NEEDS_OPERATOR` with safe failure category `inbound_or_turn_invariant`; no assistant reply committed. Turn 32 was not sent. The new turn-31 clarification assertion therefore did not execute.
- Handoff count: not recorded because the bounded replay stopped before completion. Do not infer zero handoffs from this run.
- Partial precision: 30 turns recorded; 5 turns had incomplete catalog evidence. Prefetch-only catalog report: 2 supported, 1 unsupported, 15 unscored; 67% precision over 3 scored claims and 17% coverage. This does not explain the historical unsupported-price reply.
- Artifacts: `build/reports/local-qwen/32-turn-prefetched-qwen-transcript-7d4daebccbdd4cf1b005e88aa421f188.md`, `build/reports/local-qwen/32-turn-transcript-7d4daebccbdd4cf1b005e88aa421f188.html`, and `build/reports/local-qwen/turn-telemetry-7d4daebccbdd4cf1b005e88aa421f188-oct25-candidate-bounded-diagnostic.jsonl` (plus smoke telemetry `build/reports/local-qwen/turn-telemetry-7d4daebccbdd4cf1b005e88aa421f188-smoke.jsonl`).
- Gate: 100-turn acceptance remains paused. This run is diagnostic only and does not satisfy bounded replay acceptance.

## Scope

Modify `CatalogTools`, ambiguity tracking/response behavior, source-link rendering, focused unit tests, and local replay precision diagnostics/tests. Do not change production catalog contents, persistence schema, provider credentials, replay cap, or the 100-turn gate.
