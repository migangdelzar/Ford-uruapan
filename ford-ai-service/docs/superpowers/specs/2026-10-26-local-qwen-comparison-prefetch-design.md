# Local Qwen Comparison Evidence Prefetch — Design

## Status

Approved direction: replay-only evidence prefetch. User approved keeping raw and prefetch runs separate.

## Problem

The local 100-turn Qwen replay repeatedly fails at turn 25. The buyer names the two Ranger 2027 trims and asks which published price is lower. Qwen calls `list_promotions` and one `get_vehicle`, but not a comparison tool. The replay safety gate correctly rejects the answer because it has no evidence comparing both trims. Repeated prompt-only rules did not change tool selection.

## Goals

- Keep the existing raw-Qwen replay as a diagnostic of model tool selection.
- Add a separate opt-in replay mode that deterministically retrieves comparison evidence before Qwen writes its answer.
- Keep Qwen responsible for concise, channel-aware Spanish response wording.
- Preserve the existing safety gate; prefetch evidence must not be recorded as a Qwen-selected tool call.
- Keep all changes in integration-test/replay code. No production runtime behavior, customer delivery, or hosted AI changes.

## Non-goals

- Make the raw replay pass by relaxing its evidence gate.
- Replace Qwen response generation with a deterministic renderer.
- Resolve arbitrary or fuzzy model/trim pairs in this iteration.
- Change normal application prompts or catalog behavior.

## Design

Add an opt-in local replay mode with a test-only `ChatCompletionPort` decorator. For a `COMPARISON` scenario turn, the decorator resolves the exact two trims from the synthetic buyer message against the currently loaded local catalog. For a same-model, same-year pair, it calls the existing `CatalogTools.compareTrims` implementation and adds its structured result to the `AssistantRequest` context under an explicit replay-prefetch evidence marker. The wrapped local Qwen port then generates the customer-facing response.

The prefetcher handles only exact, unambiguous pairs. If it cannot resolve exactly two trims for one model/year, it produces no evidence; the replay remains unverified and stops under the existing safety gate. It must not substitute semantically similar candidates, promotions, or individual vehicle lookups.

Prefetch evidence is represented separately from model tool traces in the transcript and validation input, for example `REPLAY PREFETCH — compare_trims — returned`. It is never written as a `conversation_decision_event` claiming Qwen selected that tool. The safety gate accepts the prefetch record only in prefetch mode and still rejects out-of-scope model mentions, unsupported inventory claims, and missing comparison evidence.

The current raw test remains unchanged in purpose and reports its existing turn-25 tool-selection failure. The prefetch run uses a separate mode flag and distinct transcript/report identity so its success cannot be confused with raw model performance.

## Data flow

1. Synthetic turn enters the replay-only decorator.
2. For comparison expectation, resolve exact model, year, and two trims from the buyer message and local names-only catalog index.
3. Call the existing `compare_trims` catalog tool against the disposable local snapshot.
4. Add returned comparison evidence to request context with a clear prefetch marker.
5. Delegate to local Qwen; it writes the response using only that pair and evidence.
6. Record prefetch and Qwen-selected tool calls separately in transcript metadata.
7. Run existing per-turn safety assertions against both sources, preserving the distinction.

## Failure behavior

- Missing or ambiguous pair: no prefetch; do not invent or broaden the pair. Existing safety gate stops the replay if comparison evidence is required.
- Empty comparison result: preserve the empty result; Qwen may state that the catalog has no comparable data, but cannot infer equality or price ordering.
- Qwen uses unrelated tools or names unrelated models: existing scope/evidence assertions fail the prefetch replay.
- Catalog or local provider unavailable: fail the replay; no hosted-provider fallback.

## Verification

- Unit/integration tests for exact pair resolution, zero/one/more-than-two matches, and same-model/year validation.
- Test that comparison evidence reaches Qwen through the marked replay context.
- Test transcript distinguishes `REPLAY PREFETCH` from model-selected calls.
- Test safety gate accepts valid prefetch comparison evidence but does not accept it in raw mode.
- Run focused tests first, then the separate opt-in prefetched Qwen replay. Report raw and prefetch outcomes independently.

## Self-review

- Scope is limited to the local synthetic replay and the agreed comparison failure.
- No production bean, prompt, delivery path, or cloud provider changes are included.
- Prefetch is not misreported as Qwen tool-use success.
- Ambiguous or unsupported comparisons fail closed.
