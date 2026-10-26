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

## Architecture

Extend the opt-in prefetch path to cover exact catalog facts as well as exact trim comparisons. For a catalog-fact turn, resolve one exact catalog model, year, and trim from the current buyer message or prior buyer messages in replay context. Do not use prior assistant text to identify a vehicle; it may contain an unsupported claim. Do not use semantic matching or guess among multiple candidates.

When exactly one vehicle resolves, use the existing local catalog `get_vehicle` operation with that exact identity. Inject a bounded, marked evidence block into the Qwen request. Qwen remains responsible for composing the customer-facing answer. Record the event as `REPLAY_PREFETCH`; keep it distinct from model-selected tool calls. Preserve the existing comparison prefetch behavior and its separate comparison evidence path.

If no unique vehicle resolves or the catalog returns no usable evidence, do not fabricate or substitute evidence. Leave the request unprefetched and let the existing replay safety gate fail it if a grounded answer cannot be verified.

## Grounding Gate

Catalog-fact safety checks must require positive evidence from the current turn. Remove the current behavior that pools evidence across adjacent turns with the same expectation; earlier evidence cannot ground a new answer. For a prefetched turn, accept only a returned positive-evidence event whose origin is explicitly `REPLAY_PREFETCH`. Raw mode continues to accept only model-origin evidence. Other existing safety checks remain unchanged.

## Testing and Verification

- Unit tests cover exact identity resolution from buyer context, case/spacing normalization, ignoring assistant text, ambiguity, no match, and empty catalog results.
- Safety tests prove that earlier-turn evidence cannot satisfy a current catalog-fact turn, and that raw mode rejects replay-prefetch evidence.
- Focused replay tests verify evidence origin, Qwen-authored response flow, and fail-closed handling.
- Run unit tests before integration tests. Run the full local prefetched 100-turn replay only after focused checks pass. Keep raw and prefetched results separate and report failures honestly.

## Out of Scope

- Changing production assistant routing, prompts, catalog APIs, tool authorization, response policies, or delivery behavior.
- Making raw Qwen use replay-prefetched evidence.
- Treating a safe review fallback as a grounded-answer success.
- Relaxing per-turn evidence, inventory, comparison-scope, or choice validation.
