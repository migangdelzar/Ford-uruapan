# Replay Evidence and Parking Follow-up Design

## Goal

Fix two failures observed in the bounded local Qwen replay: unrelated catalog links are appended to answers, and a parking-ease follow-up gets a generic reply that drops the buyer's family/city needs.

No production, delivery, catalog, database, or deployment changes. The 100-turn acceptance gate stays paused.

## Design

### Relevant source links only

Keep links from approved catalog evidence, but append a link only when the final answer identifies the model or trim that the link belongs to. Do not append search-result links to a reply that only asks a budget question or otherwise does not use a candidate. Preserve URL validation, comparison-source scoping, deduplication, and existing limits.

### Safe parking follow-up

When a buyer asks whether a vehicle is easy to park, reject unsupported ease/fit claims. If the model's reply fails validation, use a deterministic clarification that says parking ease cannot be confirmed from catalog data and offers to compare published dimensions for family and city use. It must not recommend a specific vehicle, infer fit, or claim dimensions without evidence.

Keep other safe clarifications unchanged. Preserve the independent no-repeated-greeting workstream.

## Tests and verification

1. Add unit regressions for irrelevant search links, relevant model/trim links, and the parking follow-up fallback.
2. Run each new test first and confirm it fails for the intended reason.
3. Run focused Service unit tests, then the complete unit suite and integration tests, plus `git diff --check`.
4. If local Qwen replay is rerun, use only the approved offline catalog snapshot; keep cloud and delivery disabled. Treat replay as diagnostic, not acceptance.

## Risks and boundaries

- Entity matching must not confuse model/trim names or attach another candidate's source URL.
- A safe fallback can state uncertainty and offer a sourced comparison; it cannot imply parking suitability.
- No catalog records or credentials change. No production call, customer message, deployment, or 100-turn acceptance run.
