# Yearless Vehicle Trim Alias Resolution

## Problem

The bounded local Qwen replay reproduced a safe-review stop at turn 31. The assistant required vehicle evidence, then called `get_vehicle` with a model, no model year, and a short trim alias (`XLT`). `CatalogTools.getVehicle` handles yearless requests through `availableVehicles(model, trim)`, which forwards that short alias as an exact trim lookup for each model year. The local catalog uses canonical trim names such as `XLT Gasolina 4x2`, so the lookup returned no evidence. The adapter withheld the answer and the existing fail-closed path recorded `SAFE_FALLBACK_REVIEW`.

## Goal

Resolve short trim aliases in yearless `get_vehicle` requests to canonical catalog variants, so the tool can return grounded choices instead of an empty result when exact matches exist.

## Design

When `year` is absent and `trim` is present:

1. Discover candidate model years using the existing model search.
2. Use `CatalogVehicleResolver` and the catalog name index to resolve the requested trim for each candidate year.
3. Fetch catalog facts only for exact or uniquely canonicalized candidates. For ambiguous prefixes, fetch each canonical candidate and return all matching choices; never select one implicitly.
4. Return the existing `ModelYearOptions` shape, with the current unavailable status when no canonical candidates exist.
5. Keep the no-year/no-trim path and the existing exact-year path unchanged.

The existing review fallback remains in force whenever the catalog has no matching evidence. This change does not alter intent routing, model prompts, retry policy, response verification, or handoff behavior.

## Alternatives Considered

- **Clarify instead of calling the tool:** Safer for genuinely ambiguous buyer intent, but does not correct a valid yearless lookup that uses a short trim alias.
- **Resolve against canonical names across candidate years (selected):** Smallest change at the lookup boundary; preserves all candidates and fail-closed behavior.
- **Change intent routing or required-tool policy:** Broader behavior change with greater risk of weakening volatile-data checks; not needed to resolve the observed empty lookup.

## Acceptance Criteria

- A yearless `get_vehicle("Ranger", null, "XLT")` returns exact catalog evidence when canonical XLT variants exist.
- Multiple canonical XLT variants remain separate choices; no arbitrary trim/year is selected.
- Unknown or unmatched aliases return no evidence and retain fail-closed review behavior.
- Existing yearless model-only options and exact-year alias resolution remain unchanged.
- Unit tests cover canonicalization, ambiguity, no-match, and existing behavior.
- No production/`emme-2` writes, catalog import, transcript seeding, or 100-turn acceptance.
- If a live check is needed, use only the approved local snapshot and disposable PostgreSQL; keep no-handoff and safety assertions enabled.

## Scope

Modify `CatalogTools` and focused catalog-tool unit tests only. No database schema, production configuration, replay harness, or public API changes.
