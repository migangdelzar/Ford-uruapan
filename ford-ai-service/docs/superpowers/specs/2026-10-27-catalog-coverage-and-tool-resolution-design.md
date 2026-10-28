# Ford Catalog Coverage and Deterministic Tool Resolution

## Goal

Improve coverage of Ford Mexico public catalog, finance, and promotion data in the existing local catalog database. Ensure tool calls resolve canonical model/year/trim/package identity safely and return source-backed results or clear unknown/clarification outcomes. Keep dealer inventory separate unless a trusted inventory source is configured.

## Current State

- The service already reads local `ford_read` catalog views through `JdbcCatalogAdapter`.
- Vehicle variants, sourced vehicle facts, offers/promotions, freshness, and provenance are represented in the catalog read path.
- `DefaultSyncCoordinator` can ingest a Ford catalog endpoint, an inventory endpoint, an authorized scraper endpoint, and manual CSV imports.
- `FordIngestionConfiguration` returns empty snapshots when source endpoints or the import path are blank. Configured sync therefore does not prove source coverage.
- The latest replay snapshot reported 121 variants and 10 promotions. These counts do not prove complete coverage of Ford Mexico's published catalog.
- A trim-family query can yield one partial canonical candidate but still be tagged ambiguous. The response tracker requires multiple candidates to recognize ambiguity; the empty-fact evidence gate can then withhold a valid clarification. This is a tool-result contract issue, separate from missing catalog data.

## Scope

### In scope

- Official Ford Mexico public sources for new vehicles: model, model year, canonical trim, published packages, and source-backed specifications.
- Published Ford Mexico prices with source and observation date.
- Published finance products and promotions with applicability, requirements, effective dates, and source provenance.
- Existing catalog sync and local read database; no second database.
- Coverage and freshness reporting by source, model year, trim, package, and data domain.
- Deterministic tool outcomes and tests for exact, unique canonical, ambiguous, missing, stale, and unsupported requests.

### Out of scope

- Guaranteeing unpublished, inaccessible, or undocumented Ford data.
- Treating model/package names as proof of physical inventory or availability.
- Dealer stock, reservations, or VIN-level availability without a separately authorized, trusted inventory source.
- Using model-generated facts to fill database gaps.

## Recommended Design

### 1. Coverage audit before bulk ingestion

Inventory configured source endpoints and import paths. Compare the current local read snapshot with an allowlisted manifest of official Ford Mexico source pages/documents. Report observed counts and coverage gaps by model year, canonical trim, package, and data domain. A source returning an empty snapshot must be visible as unconfigured/empty, not counted as complete.

### 2. Reuse existing sync and database

Use `DefaultSyncCoordinator`, the existing source adapters, normalization, precedence policy, and `CanonicalDataWriter`. Configure or extend official catalog and authorized scraper feeds where needed; use manual imports only as an auditable supplemental source. Store records in existing catalog tables/views and preserve source kind, URL, observation time, validity window, and freshness. Do not create a parallel catalog database.

### 3. Keep facts applicability- and provenance-bound

Represent package identity separately from trim identity when sources distinguish them. Associate each fact with the narrowest identity the source supports: model, year, trim, and package where known. Normalize units without discarding the original value. Only source-backed, dated facts can support factual answers. Missing or conflicting evidence remains `UNKNOWN` or `CONFLICTED`.

Promotions and finance products retain eligibility conditions, approval requirements, applicability, effective dates, source URL, and observation date. Expired offers are not presented as current. Vehicle catalog data never implies stock availability.

### 4. Resolve tool requests deterministically

Resolve buyer wording against the local canonical name index before fact lookup:

- Exact canonical identity: query facts for that identity.
- One uniquely matching canonical identity: map only through a deterministic, tested canonical-name rule; retain the canonical identity in the result and trace.
- Multiple matches: return a typed clarification containing the exact canonical choices; do not query facts for an arbitrary choice.
- No match or unavailable index: return `MISSING`/`UNKNOWN`; do not guess or substitute.

Clarification is a valid terminal response even though candidate names are not fact evidence. Evidence-quality gates must not reject a valid clarification merely because no vehicle facts were returned. Keep raw tool arguments and personal data out of ordinary logs.

### 5. Coverage and acceptance reporting

Produce an auditable report for each sync/snapshot with:

- configured, skipped, successful, and failed sources;
- distinct model/year/trim/package counts by source;
- sourced fact counts and missing required domains;
- stale, expired, conflicting, and quarantined records;
- snapshot timestamp and manifest hash.

Define completeness as coverage of the selected public-source manifest, not as all possible Ford knowledge. Require explicit source coverage before describing the database as complete.

## Alternatives Considered

1. **Enhance the existing local database and sync path (recommended):** preserves one source of truth, provenance, and current tool contracts.
2. **Create a second local database:** rejected due to duplication, drift, and unclear ownership.
3. **Scrape live for every customer question:** rejected as the primary path due to latency, source variability, and weak replay reproducibility. Live retrieval may be a later, bounded supplement.

## Verification

1. Unit tests for source configuration, normalization, source precedence, canonical package/trim identity, and typed tool outcomes.
2. Integration tests against a validated local snapshot: exact lookups return sourced facts; ambiguous identity asks canonical choices; unique identity follows its explicit deterministic policy; missing facts remain unknown; stale/expired offers are not current.
3. Coverage report tests for empty/unconfigured sources and manifest gaps.
4. Focused turn-73/74 replay verifies safe XLT clarification and XL resolution/clarification without an evidence-gate fallback.
5. Seeded replay is diagnostic only. Final acceptance remains a fresh, unseeded 100-turn run with 100 committed turns and zero handoffs.

## Canonical Partial-Name Rule

Normalize buyer wording and canonical names, then match whole trim-name tokens within the selected model and year. If the requested trim is a prefix of exactly one canonical trim, resolve to that canonical identity and retain the mapping in the typed result. If it matches multiple canonical trims, ask using those exact names. If it matches none, return unknown. Never use semantic similarity or model judgment to break a tie. Unit and integration tests must cover the one-, many-, and zero-match cases.
