# Local Qwen Catalog-Fact Prefetch Design

## Goal

Make the opt-in, synthetic local Qwen prefetched replay capable of completing its 100-turn groundedness bar without relying on Qwen to recover exact vehicle identity from a long conversation. Keep the raw Qwen replay unchanged and separately measurable.

## Constraints

- All changes stay in integration-test replay code and synthetic local test data.
- No production writes, customer delivery, or hosted AI.
- Qwen writes customer-facing text for prefetched turns; prefetch supplies evidence only.
- Existing inventory, scope, and response safety checks remain enabled. No safety gate is weakened to make the replay pass.
- Prefetched evidence has an explicit `REPLAY_PREFETCH` origin and never counts as Qwen-selected tool use.
- Ambiguous vehicle identity, missing catalog results, or empty evidence fail closed.
- The 100-turn prefetched replay is a no-handoff memory benchmark. Its synthetic inventory prompts are replaced with memory checks; actual inventory requests remain handoff-only outside this benchmark.

## Architecture

Extend the opt-in prefetch path to cover exact catalog facts, promotion applicability follow-ups, and exact trim comparisons. For catalog-fact and promotion follow-up turns, resolve one exact catalog model, year, and trim from the current buyer message or prior buyer messages in replay context. The replay-only decorator retains a bounded, per-conversation sequence of buyer messages so memory compaction cannot erase a previously stated identity. Do not use prior assistant text to identify a vehicle; it may contain an unsupported claim. Do not use semantic matching or guess among multiple candidates.

When exactly one vehicle resolves, use the existing local catalog `get_vehicle` operation with that exact identity. Run vehicle fact prefetch for `VEHICLE_DETAILS` routing and narrowly phrased catalog questions that deterministic routing missed (such as fuel/engine/safety questions and requests for the source of a cited fact). For an offer-applicability follow-up (including explicit `OFFERS` routing or a buyer question referring to a just-discussed amount/benefit), query promotions for the exact model and year using `list_promotions`; preserve promotion conditions and source details so Qwen can distinguish model-level from trim-specific applicability. Inject bounded, marked evidence blocks into the Qwen request. Qwen remains responsible for composing the customer-facing answer. Record each event as `REPLAY_PREFETCH`; keep it distinct from model-selected tool calls. Preserve the existing comparison prefetch behavior and its separate comparison evidence path.

In opt-in prefetched replay mode, route all 100 synthetic turns through the local Qwen assistant. Do not let deterministic catalog answers substitute for Qwen on fact or promotion turns. Raw replay keeps its existing assistant routing and receives no replay-prefetch beans.

Replace fixture turns 67, 69, and 70 (inventory capability/availability requests) with three explicit memory-recall questions about buyer-stated usage, cargo needs, and the exact Ranger trim discussed. Mark each with a `MEMORY_RECALL` expectation and explicit expected buyer facts. Assert the prefetched benchmark completes without handoff. Do not call inventory tools or make availability claims. Production inventory behavior remains unchanged: route such requests to human handoff rather than pretending to check stock.

If no unique vehicle resolves or the catalog returns no usable evidence, do not fabricate or substitute evidence. Leave the request unprefetched and let the existing replay safety gate fail it if a grounded answer cannot be verified. A promotion follow-up requires positive current-turn promotion evidence; vehicle facts alone do not satisfy it.

## Grounding Gate

Catalog-fact safety checks must require positive evidence from the current turn. Remove the current behavior that pools evidence across adjacent turns with the same expectation; earlier evidence cannot ground a new answer. For a prefetched turn, accept only a returned positive-evidence event whose origin is explicitly `REPLAY_PREFETCH`. A promotion-applicability follow-up accepts only `list_promotions` evidence, not vehicle evidence alone. Raw mode continues to accept only model-origin evidence. Other existing safety checks remain unchanged.

Narrow inventory-claim detection to actual stock/location availability language. A catalog fact being absent (for example, “la ficha técnica no está disponible en este catálogo”) is not an inventory claim. Preserve and test detection for real stock assertions; do not weaken inventory safety to improve completion.

## Precision Diagnostics

Report two separate precision measures for the prefetched replay:

- **Catalog claim precision:** On catalog-fact and offer turns, compare deterministically identifiable claims (vehicle/version, price/amount, dates, measurements/spec values, and source URLs) in Qwen's answer with positive same-turn catalog evidence. Report supported, unsupported, and unscored claim counts, plus precision (`supported / (supported + unsupported)`) and coverage (`scored / candidate claims`). Unscored or unknown claims are never treated as correct. This diagnostic supplements but never overrides the grounding/safety gates.
- **Memory recall precision:** On the three `MEMORY_RECALL` turns, compare each recalled buyer fact against explicit expected facts and the buyer-only replay history. Report supported and unsupported recalled facts, precision, and per-turn results. Assistant-authored prior text is not a memory source.

Print both scores and their denominators in the local replay report. A 100-turn success still requires all existing per-turn safety checks to pass and zero handoffs; precision metrics cannot turn a failed check into a pass.

## Testing and Verification

- Unit tests cover exact identity resolution from buyer context, case/spacing normalization, ignoring assistant text, ambiguity, no match, empty catalog results, and exact model/year promotion lookup.
- Safety tests prove that earlier-turn evidence cannot satisfy a current catalog-fact turn, and that raw mode rejects replay-prefetch evidence.
- Focused replay tests verify evidence origin, Qwen-authored response flow, and fail-closed handling.
- Memory tests verify the three expected buyer-fact recalls. Precision tests cover supported, unsupported, and unscored claims and verify reported denominators/coverage.
- Inventory detector tests distinguish stock/location claims from missing catalog specifications; no-handoff replay asserts zero handoffs.
- Run unit tests before integration tests. Run the full local prefetched 100-turn replay only after focused checks pass. Keep raw and prefetched results separate and report failures honestly.

## Out of Scope

- Changing production assistant routing, prompts, catalog APIs, tool authorization, response policies, or delivery behavior.
- Making raw Qwen use replay-prefetched evidence.
- Treating a safe review fallback as a grounded-answer success.
- Relaxing per-turn evidence, inventory, comparison-scope, or choice validation.
