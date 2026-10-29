# INEGI Model-Sales Popularity for Vehicle Recommendations

## Goal

Use INEGI national model-level sales as a sourced popularity signal for Ford recommendations. Surface the most-sold model when it fits the buyer's needs, and show all catalog trims/versions for that model. Never imply INEGI reports trim-level sales.

## Evidence and Data Scope

The supplied `RAIAVL_8_9.xlsx` is an INEGI tabulado export queried on 2026-10-28. It contains monthly sales rows by year, month, and `Marca-Modelo-Tipo-Segmento-Origen-País Origen`. Repeated models across months are expected observations, not duplicate January rows. The workbook has numeric monthly values through September 2026; October–December 2026 are marked `-`.

Use the supplied cumulative period, January 2024–September 2026, as the stable popularity ranking. Keep January–September 2026 and January–September 2025 as separate recency/trend metrics; do not blend cumulative and YTD numbers into an invented score. The supplied table groups raw INEGI rows into canonical model families (including Ranger/Crew Cab and Lobo Regular/Crew Cab). Refresh cumulative and YTD periods when later monthly INEGI data is reviewed. Preserve model-level scope, national geography, exact period, source, and retrieval date with every metric.

The supplied worksheet includes two identical Maverick labels per month; the second row is `-` for every month checked, while the other row contains the reported sales. Preserve source-row identity and marker semantics; never count the dash row as zero or duplicate sales. Retain a diagnostic for duplicate labels. Unclear or unmatched names must not be fuzzy-attached.

## Recommended Architecture

Keep popularity as a separate, versioned model-sales dataset, not as trim facts and not as mutable fields in the read-only `emme-2` catalog. Each record contains canonical model-family key, reported units, period start/end, market, source name/URL, workbook retrieval date, and source status. Maintain an explicit alias map from INEGI's model labels to the catalog's canonical model families.

At recommendation time, join a catalog candidate to the sales record by canonical model family. Every trim/version of a matched model inherits the same model-level units/rank and must be labeled as model-level popularity. Trim ordering remains based on buyer fit and verified trim attributes; no trim sales rank is synthesized. Unmatched models retain no popularity claim and are reported in an offline coverage audit.

## Recommendation Behavior

1. Enforce buyer hard constraints first (for example body style, price range, seating, requested model/year, and required features).
2. Rank eligible models primarily by buyer fit. Use model popularity as a secondary boost or tie-breaker so it cannot override a material buyer constraint.
3. For an explicit “most sold/popular” request with no timeframe, order eligible model families by cumulative INEGI units from January 2024–September 2026 and state that period. The catalog tool accepts an explicit `salesPeriod` (`CUMULATIVE` or `CURRENT_YTD`); this value wins. When omitted, the tool may infer current-trend intent from the buyer query as a compatibility fallback. If the buyer asks about current trend, use the comparable YTD columns. If no eligible model has reported YTD sales, return ordinary fit-ranked catalog results with an explicit unavailable status; do not imply a sales ranking or silently fall back to cumulative ranking. If the overall top seller is ineligible, explain that and show the top-selling eligible choice instead.
4. Lead with the best-selling eligible model when useful, then show all matching catalog trims/versions. State that the popularity signal is for the model, not an individual trim.
5. If evidence is missing, stale, or unmapped, do not claim a sales rank. Never use model self-confidence or vector similarity as a substitute for sales data.

## Evaluation

- Parse/fixture checks preserve all period and source states, validate the January 2024–September 2026 cumulative totals, validate comparable YTD aggregates, and keep these metrics separate without implicit blending.
- Alias-map tests cover canonical names, known aliases, duplicate source rows, and unresolved/ambiguous labels.
- Catalog coverage test checks all current catalog model families and variants: mapped variants inherit parent-model popularity; unmapped variants remain unranked and are listed for review.
- Ranking tests prove hard constraints outrank popularity, popularity improves ordering among eligible candidates, and an explicit popularity request uses descending model sales.
- Period-selection tests prove explicit period overrides text inference, text inference is used only when period is omitted, and unavailable current-YTD evidence never falls back to a sales ranking.
- Response-grounding tests allow a model-level popularity statement with period/source, reject unsupported trim-level sales claims, and reject mismatched vehicle categories (for example, recommending an SUV for a pickup-only request).
- Run deterministic tests first, then local PostgreSQL/production-graph webhook integration tests. Do not import the dataset into production or `emme-2`; catalog import remains blocked until fresh all-live acceptance passes.

## Implementation Boundaries

- Preserve the existing conversation graph and hybrid catalog retrieval. Add no alternate graph, semantic cache, or model-generated query expansion for this feature.
- Keep this dataset read-only and versioned. No write to production or `emme-2`.
- Do not commit or push while the current worktree contains broad unrelated uncommitted changes.
- Use the supplied workbook as the data source; refresh through a later reviewed INEGI export, not scraping values from the interactive HTML shell.

## Initial Model-Level Dataset

Use the user-reviewed aggregation from `RAIAVL_8_9.xlsx`. Period columns are national model-level units. `Jan 2024–Sep 2026` is the default stable popularity ranking; `Jan–Sep 2026` and `Jan–Sep 2025` remain separate recency and comparison metrics. Shares are rounded by the source/aggregation and may not total exactly 100%.

| # | Model family | Jan 2024–Sep 2026 units | Share | Jan–Sep 2025 | Jan–Sep 2026 | Origin |
|---:|---|---:|---:|---:|---:|---|
| 1 | Territory | 41,241 | 28.4% | 9,478 | 13,719 | China |
| 2 | Ranger (incl. Crew Cab) | 17,452 | 12.0% | 4,800 | 4,411 | Argentina |
| 3 | Transit | 12,606 | 8.7% | 3,710 | 2,295 | U.K. |
| 4 | F-150 | 12,475 | 8.6% | 4,018 | 2,805 | U.S. |
| 5 | Lobo (Regular + Crew Cab) | 9,884 | 6.8% | 2,424 | 2,433 | U.S. |
| 6 | Maverick | 7,128 | 4.9% | 2,171 | 1,798 | Mexico |
| 7 | Explorer | 6,669 | 4.6% | 2,939 | 1,247 | U.S. |
| 8 | F-350 | 6,456 | 4.5% | 1,974 | 1,914 | U.S. |
| 9 | Bronco Sport | 6,048 | 4.2% | 1,735 | 1,259 | Mexico |
| 10 | Escape (gasoline + HEV) | 4,830 | 3.3% | 1,478 | 0 | U.S. |
| 11 | Edge | 2,943 | 2.0% | 14 | 2,379 | Canada (per file) |
| 12 | Bronco | 2,909 | 2.0% | 740 | 766 | U.S. |
| 13 | F-450 (incl. Diesel) | 2,872 | 2.0% | 766 | 909 | U.S. |
| 14 | Mustang | 2,321 | 1.6% | 639 | 615 | U.S. |
| 15 | F-250 Super Duty | 2,223 | 1.5% | 574 | 537 | U.S. |
| 16 | F-550 | 2,140 | 1.5% | 637 | 544 | U.S. |
| 17 | Expedition | 2,000 | 1.4% | 635 | 591 | U.S. |
| 18 | E-Transit | 1,789 | 1.2% | 122 | 455 | U.S. |
| 19 | Transit Courier | 609 | 0.4% | 0 | 0 | Turkey |
| 20 | Mustang Mach-E | 385 | 0.3% | 103 | 119 | Mexico |

The table reports 144,980 combined units. The 2026 YTD `0` values for Escape/Escape HEV and Transit Courier need source-state handling: INEGI's `-` marker means not marketed in that period or no longer reported, not necessarily literal zero sales. Preserve `NOT_REPORTED_OR_NOT_MARKETED` in the source record; a zero may be used only as a ranking arithmetic fallback, never stated as a verified zero-sales claim.

The earlier exploratory ranking grouped raw INEGI labels too narrowly; this user-reviewed canonical aggregation supersedes it.
