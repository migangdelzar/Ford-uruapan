# Catalog LLM-Ready Read Model and Response Quality Design

## Goal

Make catalog-backed answers more reliable across models, years, trims, specifications, comparisons, prices, offers, promotions, and provenance. Transform irregular source facts into compact, typed, source-backed data that tools can return consistently. Stop repeated greetings as a related conversation-quality fix. Preserve raw facts and existing evidence, authorization, freshness, and no-handoff safeguards.

## Audit Findings

- The approved `oct25-candidate` export contains 4,887 `vehicle-facts.csv` rows, loaded by the local diagnostic into disposable PostgreSQL. This design does not change or write to that export or database.
- The facts schema has `fact_name`, `fact_name_normalized`, `fact_value`, `value_numeric`, `fact_unit`, `category`, `variant_id`, `trim`, `source_url`, and `observed_at`.
- Useful parking-related facts exist, but are uneven. Examples include model-level Maverick Hybrid 2026 length (5,073 mm), wheelbase (3,076 mm), and folded-mirror width (1,979 mm); Ranger 2026 width without mirrors (1,910 mm) and wheelbase (3,270 mm). These examples do not establish parking ease.
- Equivalent facts can appear in more than one row/category, and structured numeric/unit fields are not populated consistently. Some facts are model-level while others are trim-level.
- The transcript has Qwen-generated greetings on turns 2, 3, and 5. `introduceOnce` avoids adding the application’s fixed introduction when history is recognized, and the prompt asks the model not to repeat greetings. The model can still generate “¡Hola!” itself. `AdvisorResponseRepeatPolicy` excludes greeting words as boilerplate, so a repeated greeting with changed answer text can escape its near-repeat check.
- The complete approved snapshot also has 39 model/year search rows, 121 trim rows, 2,447 comparison-fact rows, 10 promotion rows, and 1,882 document vectors. These are snapshot counts, not proof of full Ford-market coverage.
- A separate local Ford-scraping candidate worktree already contains a broader retrieval candidate: 907 source/category/trim-scoped fact profiles, 673 PDF reference pages, 1,882 ready BGE-M3 vectors, and 113 of 121 variants with new trim-scoped profiles. Existing raw facts and original 299 documents are preserved. This is not yet broad Spring AI/LangGraph answer evaluation or complete coverage.
- The candidate worktree contains substantial uncommitted user work. Preserve it; inspect and extend it, do not replace or clean it. The primary Ford-scraping repository also has unrelated user changes.
- Ford-scraping owns catalog ingestion and writes. Ford-service reads versioned `ford_read` views through a dedicated read-only datasource; `JdbcCanonicalDataWriter` writes the separate `ford_service` schema. The local catalog has a baseline and a separate candidate database. User authorizes inspection and schema/data changes only on the local candidate, never the baseline or production.

## Design

### 1. Catalog-wide canonical fact projection

First build a read-only coverage/data-quality map by source domain and canonical model/year/trim. Map observed source fact names into an explicit, versioned attribute registry. Categories include identity/configuration, powertrain/performance, dimensions/capacity, safety/driver assistance, comfort/technology/equipment, pricing, dated finance/promotions, comparisons, and official links/provenance. Do not promise arbitrary unbounded question coverage; acceptance is measured against this declared catalog-backed domain list and the verified source scope.

Extend the existing Ford-scraping ETL/profile pipeline and the source-owned versioned read-view contract. Preserve raw facts; add only a versioned canonical read projection where coverage analysis proves it helps exact question answering. Apply schema or backfill changes only to `ford_catalog_candidate_20261025` after verifying the local container, database identity, and recoverable candidate backup. Keep the baseline untouched. Ford-service remains read-only and consumes the approved view contract; never use `JdbcCanonicalDataWriter` or grant the app write access to `ford_read`.

Every projected value includes canonical attribute ID, typed value, canonical unit when relevant, model/year/trim scope, source URL, observation time, and a status. Use explicit statuses such as `PRESENT`, `UNKNOWN`, `CONFLICTED`, `STALE`, and `NOT_PUBLISHED`; only assert `NOT_PUBLISHED` when source checks prove absence. Never turn a missing fact into a negative feature claim. Keep the original fact and provenance available for audits.

Use deterministic normalization rules: explicit aliases, approved unit conversions, typed numeric/boolean/text values, strict parsing, scope-aware deduplication, conflict detection, and freshness. Never let an LLM choose among duplicate conflicting rows or guess units. Preserve the source value and source metadata; derived values must be reproducible and versioned.

Expose task-sized read-only projections through catalog tools. Keep exact model/year/trim resolution and current authorization. A tool should return a small relevant profile plus explicit unknown/conflict/freshness and provenance—not the entire raw catalog by default. Route question domains to relevant attributes while retaining complete detail access when explicitly requested. Keep financial terms, published base price, promotional validity, and inventory as separate concepts. This catalog has no physical dealer inventory proof; never imply stock from vehicle listings or sales evidence.

Parking is the first vertical profile: dimensions and parking aids. It uses the same canonical projection and must not derive subjective `easy_to_park` labels. Compare only supported measurements with clear scope; say “shorter/narrower” rather than “easier to park.” Apply the same pattern to other domains rather than adding one-off LLM prompt prose for each fact type.

### 2. No repeated greeting after first assistant turn

Make greeting behavior deterministic using conversation history already available to the assistant service. Allow the introduction/greeting on the first assistant turn. On later turns, reject a candidate that begins with a greeting and request one repair without a greeting. If repair still begins with a greeting, use the existing safe response/review path; do not strip arbitrary customer-facing text or weaken other quality checks. Keep acknowledgements such as “Entiendo” available.

The rule applies to generated Qwen responses and the fixed application introduction. It does not change the response to the customer’s first greeting, conversation identity, or turn memory. Track this as a separate implementation workstream because greeting quality does not depend on catalog normalization.

## Example Tool Result Shape

```json
{
  "model": "RANGER",
  "year": 2026,
  "trim": "XL Gasolina 4x2",
  "dimensions": [
    {
      "attribute": "overall_width_without_mirrors",
      "value": 1910,
      "unit": "mm",
      "scope": "MODEL",
      "sourceUrl": "source-backed URL",
      "observedAt": "source-backed timestamp"
    }
  ],
  "parkingAids": [],
  "unknownAttributes": ["overall_length", "turning_diameter"]
}
```

The example is illustrative only; runtime values must come from the current exact catalog evidence.

## Alternatives

- **Add `easy_to_park` to source rows:** rejected. It is a subjective conclusion not supported by the source facts and risks stale or misleading labels.
- **Add nullable canonical columns to `vehicle_facts_v2`:** rejected for the first iteration. That table is heterogeneous and source-owned; appending special-purpose columns would couple the reader to a catalog schema change.
- **Ask the LLM to interpret all raw facts:** rejected. It leaves inconsistent labels, duplicates, units, and variant scope for the model to resolve.
- **Build structured source facts plus scoped semantic profiles upstream, then serve through versioned read views:** selected. It reuses the existing owner boundary, keeps exact facts authoritative, and gives semantic retrieval scoped documents with citations.
- **Put all facts only into embeddings:** rejected. Exact numeric, identity, price, date, and eligibility questions need structured lookup; vectors only help find relevant descriptive evidence.
- **Fix greetings only in the prompt:** rejected. Existing prompt already asks for this and the replay shows it is insufficient.

## Acceptance Criteria

1. A coverage audit reports snapshot scope and field coverage by model/year/trim and domain without claiming full-market completeness.
2. Projection emits only registry-backed, source-backed values with typed values, scope, freshness, and provenance; raw facts remain unchanged.
3. Aliases and known units normalize deterministically; malformed, unsupported, missing, stale, and conflicting values remain explicitly distinguishable.
4. Exact trim identity is preserved; no fact, price, or offer leaks from another trim or year.
5. Catalog tools return bounded, question-relevant results with clear status and provenance; price, finance, promotion, inventory, and sales remain separate.
6. Parking answers never infer ease from size or driver aids alone; other domains also avoid converting unknown into false.
7. First assistant turn can greet; later turns do not start with a repeated greeting after successful generation/repair. Persistent violation uses existing safe review behavior.
8. Any schema/backfill run targets only the verified local candidate database with a recoverable backup; baseline and production stay untouched. Ford-service stays read-only. No external delivery occurs.
9. Tests cover registry mappings, parsing/conversion, duplicates/conflicts, scope, freshness, all returned tool contracts, authorization, question-domain routing, and greeting behavior. Validate unit tests before focused non-live integration tests; any local model replay stays bounded and local.

## Review Status

Expanded design draft for user review. No implementation changes made.
