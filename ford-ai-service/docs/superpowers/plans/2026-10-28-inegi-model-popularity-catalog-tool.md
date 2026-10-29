# INEGI Model Popularity in Catalog Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the catalog recommendation tool surface INEGI’s verified model-level popularity, expose it on all matching catalog trims, and support a deterministic “most sold” ranking mode without overriding buyer constraints.

**Architecture:** Keep hybrid retrieval and the current conversation graph. Load a small versioned INEGI model-sales dataset as a read-only sidecar, map INEGI model families to exact catalog model names, enrich vehicle evidence for every vehicle tool response, and provide a popularity spotlight plus explicit sales ranking mode. Sales remains model-level; each trim inherits the parent model’s signal without any trim-sales claim.

**Tech Stack:** Java 21, Spring Boot, Jackson, JUnit 5, AssertJ, Mockito, Gradle, PostgreSQL/Testcontainers for integration tests.

## Global Constraints

- Keep the current workflow/state graph; add no alternate graph, ReAct, Plan-and-Execute, or multi-agent flow.
- No production or `emme-2` writes; no catalog import before fresh all-live acceptance passes.
- Use exact buyer constraints before popularity; popularity cannot make an ineligible vehicle eligible.
- Model self-confidence and vector similarity cannot substitute for sales data.
- INEGI reports model-level data, not trim-level data. Every trim inherits the same parent-model signal.
- Treat source `-`, `NA`, and `ND` as source states, not verified zero-sales claims.
- Keep current hybrid retrieval. Do not add semantic caching or model-generated query expansion in this change.
- Unit tests pass before integration tests. Do not run fresh 100-turn acceptance until focused gates pass and the existing turn-31 failure is understood.
- No commit or push while the worktree contains broad unrelated uncommitted changes.

---

## File Map

- `src/main/java/com/ford/service/catalog/application/ModelPopularity.java` — immutable model-family metric and period/source metadata.
- `src/main/java/com/ford/service/catalog/application/ModelPopularityIndex.java` — lookup and ranked model-family interface.
- `src/main/java/com/ford/service/catalog/adapter/out/ClasspathModelPopularityIndex.java` — validates and loads the versioned bundled dataset and explicit catalog-name aliases.
- `src/main/resources/catalog/inegi-ford-model-popularity.json` — user-reviewed 20-model aggregation: cumulative units (Jan 2024–Sep 2026), share, YTD 2025/2026, origin, source, and source-state fields.
- `src/main/java/com/ford/service/catalog/api/ModelPopularityEvidence.java` — safe, customer-facing model-level popularity fields.
- `src/main/java/com/ford/service/catalog/application/ModelPopularityEnricher.java` — attaches one model-family signal to catalog evidence without changing catalog facts.
- `src/main/java/com/ford/service/catalog/application/CatalogPopularityRecommendationService.java` — buyer-filter-aware popularity spotlight and explicit sales ordering.
- `src/main/java/com/ford/service/catalog/api/VehicleEvidence.java` — optional popularity evidence field, preserving existing constructors.
- `src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java` — ranking mode, explicit/fallback sales-period selection, enriched vehicle outputs, and popularity spotlight in `search_vehicles`.
- `src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java` — wire the index/enricher/recommendation service into the registered tool.
- `src/test/java/com/ford/service/catalog/ModelPopularityIndexTest.java` — bundled data, alias, sorting, and source-state tests.
- `src/test/java/com/ford/service/catalog/ModelPopularityEnricherTest.java` — same parent-model metric on all trims; no false trim claim.
- `src/test/java/com/ford/service/catalog/CatalogPopularityRecommendationServiceTest.java` — fit-first spotlight and explicit popularity ranking under hard filters.
- `src/test/java/com/ford/service/ai/CatalogToolsPopularityTest.java` — registered tool contract and all vehicle-output enrichment paths.
- `src/integrationTest/java/com/ford/service/catalog/CatalogPopularityRecommendationIntegrationTest.java` — read-only catalog filtering/ranking against disposable PostgreSQL.

## Task 1: Add the Versioned INEGI Popularity Index

**Files:** Create the domain/index/adapter/resource above; test in `ModelPopularityIndexTest.java`.

**Interface:**

```java
public interface ModelPopularityIndex {
    Optional<ModelPopularity> findForCatalogModel(String catalogModel);
    List<ModelPopularity> cumulativeRankedModels();
    List<ModelPopularity> ytd2026RankedModels();
}
```

- [ ] Write tests for 20 unique model families, total cumulative sales `144980`, first rank Territory `41241`, F-150 alias normalization, and deterministic descending order.
- [ ] Write tests proving all explicit aliases map only to their reviewed model family; unknown names return empty and never fuzzy-match.
- [ ] Write tests proving Escape/HEV and Transit Courier keep a `NOT_REPORTED_OR_NOT_MARKETED` 2026 YTD state; do not turn INEGI dashes into a factual zero-sales statement.
- [ ] Run `cd ford-ai-service && ./gradlew test --tests com.ford.service.catalog.ModelPopularityIndexTest`; expect new tests to fail because types/resource do not exist.
- [ ] Implement immutable `ModelPopularity` fields: canonical family key, catalog model aliases, cumulative units/share, Jan–Sep 2025 and Jan–Sep 2026 values and source states, origin, geography, period start/end, source label/URL, and retrieval date.
- [ ] Implement classpath JSON loading with startup validation: exactly 20 unique family keys; non-negative numeric values; period ordering; cumulative sum `144980`; aliases unique after normalization; missing/stale source states are explicit.
- [ ] Add the 20 rows exactly as listed in the approved design spec. Keep the source table separate from the `ford_read` catalog and do not write/import it into production or `emme-2`.
- [ ] Re-run the focused index tests; expect PASS.

## Task 2: Enrich Every Vehicle Evidence With Parent-Model Popularity

**Files:** Modify `VehicleEvidence.java`, add `ModelPopularityEvidence.java` and `ModelPopularityEnricher.java`, and add `ModelPopularityEnricherTest.java`.

**Interface:**

```java
public record ModelPopularityEvidence(
        String modelFamily,
        long cumulativeUnits,
        BigDecimal cumulativeSharePercent,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long ytd2026Units,
        String ytd2026Status,
        String geography,
        String sourceLabel,
        String sourceUrl) {}

public VehicleEvidence withModelPopularity(ModelPopularityEvidence popularity);
public VehicleEvidence ModelPopularityEnricher.enrich(VehicleEvidence vehicle);
```

- [ ] Add tests for Ranger XLT and Ranger XL receiving identical model-level metrics; verify neither result carries a trim-level rank or units field.
- [ ] Add tests for a catalog model absent from the reviewed index remaining unchanged with null popularity.
- [ ] Add the optional `modelPopularity` field to `VehicleEvidence`; retain all current public constructor overloads and `withRetrievedEvidence` behavior.
- [ ] Implement `ModelPopularityEnricher` as a pure model-family lookup; never derive popularity from a trim name or from semantic similarity.
- [ ] Update `CatalogTools` vehicle-returning paths to enrich `search_vehicles`, `get_vehicle`, `search_vehicle_and_offers`, `compare_vehicles`, and `compare_model_years` evidence. Leave offers and technical facts unchanged.
- [ ] Wire one shared classpath index and enricher through `SpringAiConfiguration.java`; preserve existing test constructors or add compatible overloads, but do not let any constructor silently disable model-popularity enrichment. Reuse the injected index/service per tool instance instead of reparsing the dataset on every search call.
- [ ] Run focused tests: `./gradlew test --tests com.ford.service.catalog.ModelPopularityEnricherTest --tests com.ford.service.ai.CatalogToolsPopularityTest`; expect PASS after implementation.

## Task 3: Add Fit-First Spotlight and Explicit Most-Sold Mode

**Files:** Add `CatalogPopularityRecommendationService.java`; modify `CatalogTools.java` and tool tests.

**Behavior contract:**

- `FIT_FIRST` is default. Preserve existing reciprocal-rank-fusion order. Return the best-selling model that passes the same structured buyer filters as a separate `mostSoldEligibleModel` spotlight. Spotlight does not displace a better-fit result.
- `MOST_SOLD` orders eligible catalog model candidates by cumulative Jan 2024–Sep 2026 units by default. An explicit `salesPeriod` (`CUMULATIVE` or `CURRENT_YTD`) wins; if omitted, infer current-trend intent from query text for compatibility. `CURRENT_YTD` uses Jan–Sep 2026 and exposes Jan–Sep 2025 comparison separately.
- If explicit/current-trend YTD selection has no eligible model with reported YTD sales, keep ordinary FIT_FIRST results, disclose `UNAVAILABLE_NO_REPORTED_YTD`, and do not imply a sales ranking or substitute cumulative rank.
- Both modes honor model/year/min-price/max-price/category/body-style/fuel filters. Never pass popularity through as an inventory/availability claim.
- Every returned vehicle/trim carries its inherited `modelPopularity`; customer-facing text says this is model-level and names the period.
- For no eligible popularity record, return no spotlight and no sales claim; keep ordinary catalog results.

- [ ] Write tests showing an explicit pickup filter excludes top-selling Territory and selects top-selling eligible pickup (Ranger) for the spotlight.
- [ ] Write tests showing a price/year/model filter cannot be bypassed by popularity; verify no eligible result returns a null spotlight.
- [ ] Write tests showing `FIT_FIRST` keeps its original hybrid result order and adds the spotlight separately; `MOST_SOLD` orders eligible models by cumulative sales.
- [ ] Add optional validated `rankingMode` (`FIT_FIRST` default, `MOST_SOLD` explicit) and `salesPeriod` (`CUMULATIVE` / `CURRENT_YTD`) arguments to the registered `search_vehicles` tool. Explicit period takes precedence; query-text inference is fallback only. Return a dedicated `VehicleSearchResult` DTO with query, semantic state, results, requested/applied ranking state, selected period, spotlight, and concise period disclosure.
- [ ] Test explicit period precedence, fallback detection, invalid period handling, and no-reported-YTD behavior (ordinary fit order + unavailable status; no cumulative fallback).
- [ ] In `CatalogPopularityRecommendationService`, use the versioned index order and `CatalogPort.search` with only confirmed structured filters to find the first eligible model; do not reuse free-text “most sold” wording as a catalog text filter.
- [ ] Keep ordinary search routed through existing `CatalogRetrievalPort`/RRF. Do not introduce a new graph or change offer-search logic.
- [ ] Update the tool description to tell the model: obey buyer constraints; use `mostSoldEligibleModel` as model-level sales evidence; never describe a trim as a best-selling trim; include period/source when claiming “most sold.”
- [ ] Re-run `./gradlew test --tests com.ford.service.catalog.CatalogPopularityRecommendationServiceTest --tests com.ford.service.ai.CatalogToolsPopularityTest`; expect PASS.

## Task 4: Add Deterministic Recommendation Evaluations

**Files:** Add `CatalogPopularityRecommendationIntegrationTest.java`; add/extend catalog AI evaluation fixture and `CatalogTools` tests.

- [ ] Create gold cases for: open-ended family recommendation, pickup-only buyer, max-budget buyer, explicit “most sold” request, current-trend request, exact model/year request, and unknown/unmapped model.
- [ ] Assert model-family precision and ranking against the approved cumulative table; assert period provenance and that trim-level sales claims are absent.
- [ ] Assert all canonical trims in the catalog name index inherit their parent model’s popularity, while unknown model families remain unranked and appear in a coverage report.
- [ ] Add PostgreSQL integration coverage using disposable Testcontainers only; test model filters and eligibility without contacting `emme-2` or production.
- [ ] Use deterministic expected ranks/constraint checks as the acceptance gate. RAGAS may be added later for answer faithfulness, but cannot replace exact model/rank/constraint assertions.
- [ ] Run focused unit tests first, then `./gradlew integrationTest --tests com.ford.service.catalog.CatalogPopularityRecommendationIntegrationTest`.

## Task 5: Full Verification and Safe Handoff

- [ ] Run `cd ford-ai-service && ./gradlew test`; all unit tests must pass before integration.
- [ ] Run focused and full `./gradlew integrationTest` with the approved local snapshot path and all live Qwen replay flags unset; do not write catalog data.
- [ ] Review the model alias coverage report, including F-150/Lobo, Ranger/Crew Cab, Escape/HEV, and F-450 diesel grouping; unresolved mappings stay unranked until explicitly reviewed.
- [ ] Do not rerun fresh 100-turn acceptance until the previous turn-31 `assistant_response_review` failure is understood and focused gates pass.
- [ ] Do not import sales/popularity into production or `emme-2`; report readiness and any unmapped model families. Do not commit or push while broad unrelated worktree changes remain.

## Self-Review

- Spec coverage: dataset/source states (Task 1), all-trim inheritance (Task 2), fit-first and explicit most-sold behavior (Task 3), exact ranking/constraint/generation evals (Task 4), privacy/read-only gates and acceptance workflow (Task 5).
- Placeholder scan: no TODO/TBD steps; task commands and expected outcomes are named.
- Type consistency: `ModelPopularityIndex` is the lookup boundary; `ModelPopularityEnricher` produces `ModelPopularityEvidence`; `VehicleEvidence` carries that optional record; `CatalogPopularityRecommendationService` chooses eligible model candidates and `VehicleSearchResult` exposes results plus the spotlight.
- Scope: this plan improves the vehicle recommendation surface only. Offer, promotion, finance, and page-link behavior stays unchanged except where a response contains shared `VehicleEvidence`.
