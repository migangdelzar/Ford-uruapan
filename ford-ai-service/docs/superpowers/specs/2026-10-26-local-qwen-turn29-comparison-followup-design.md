# Local Qwen Turn 29 Comparison Follow-up — Design

## Status

Approved direction: repair and verify the replay-only comparison evidence path. No implementation yet.

## Problem

The latest prefetched replay committed turn 29, then failed its safety check. The buyer asked whether the Ranger 2027 XL and XLT comparison included safety features. The reply said the catalog had not confirmed safety differences. The transcript records a model-selected `get_vehicle` call with zero evidence, but no successful comparison evidence. The failure is therefore missing comparison evidence, not proof that the catalog lacks safety facts.

## Goals

- Ensure exact comparison follow-ups receive current-turn `compare_trims` evidence before Qwen writes a reply.
- Keep Qwen responsible for customer-facing Spanish wording.
- Keep evidence sourced, bounded, and clearly marked `REPLAY_PREFETCH`.
- Fail closed on unresolved pairs, missing evidence, and unknown facts.
- Distinguish a successful comparison with no source-backed safety facts from failed prefetch, dropped evidence, or oversized evidence.
- Keep raw replay and all production behavior unchanged.

## Non-goals

- Add or infer safety facts absent from the catalog snapshot.
- Let Qwen compare from its pretrained knowledge or unsupported claims.
- Weaken the comparison safety gate or count prefetch as model-selected tool use.
- Change production prompts, routing, catalog APIs, or customer delivery.

## Design

For the safety follow-up, resolve the nearest exact buyer-stated model/year/trim pair from buyer-only history. Run the existing `compare_trims` catalog operation and inject its structured, source-backed result into the local Qwen request under the existing comparison evidence marker. Qwen may summarize supplied facts only. Missing safety facts remain unknown; the answer must not claim the versions are equal or assert a difference.

The replay trace must make these outcomes distinguishable: pair unresolved; catalog comparison unavailable/empty; evidence too large or not injected; evidence injected and returned; and evidence injected but Qwen also made model-selected tool calls. Do not expose sensitive contents in telemetry; retain only safe diagnostic metadata needed to identify the boundary that failed.

If no usable comparison evidence reaches Qwen, the existing safety gate remains authoritative and stops the replay. A safe wording may say the available information does not confirm a safety comparison; it may not present that wording as a sourced catalog result unless the current turn has positive comparison evidence.

## Verification and acceptance

1. Add a focused regression for the exact turn 29 question with prior buyer messages naming the two trims.
2. Assert the prefetch resolves the exact pair and records positive `REPLAY_PREFETCH / compare_trims` evidence for the current turn.
3. Assert Qwen authors the reply using the marked evidence, and the safety gate passes without unsupported safety claims. If the snapshot has no source-backed safety facts, the answer says so cautiously without claiming equality.
4. Cover unresolved pairs, empty results, oversized evidence, and dropped/missing traces. Each must fail closed with a specific diagnostic reason.
5. Run unit tests and focused integration tests before the opt-in full replay.
6. Run the 100-turn prefetched replay only after focused tests pass. Completion requires 100/100 turns, zero handoffs, and all existing safety checks passing. Raw replay remains separate and unchanged.

## Self-review

- The replay evidence gap is separated from actual catalog data absence.
- No catalog fact is fabricated, and missing data is not treated as equality.
- All changes are limited to local integration-test/replay behavior and diagnostics.
- No ambiguity remains about the safety gate or completion criteria.
