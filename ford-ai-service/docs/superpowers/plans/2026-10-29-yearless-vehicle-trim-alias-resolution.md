# Yearless Vehicle Trim Alias Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make yearless `get_vehicle` requests resolve short trim aliases to canonical catalog variants without choosing among ambiguous candidates.

**Architecture:** Keep the change at `CatalogTools` lookup boundary. Use the existing catalog search to find candidate model years, then `CatalogVehicleResolver` and `nameIndexEntries()` to resolve a trim alias for each year. Fetch and return only canonical identities present in the catalog; preserve empty results and existing fail-closed review behavior when none match.

**Tech Stack:** Java 25, Spring Boot, JUnit 5, AssertJ, Gradle.

## Global Constraints

- No production/`emme-2` writes, catalog import, transcript seeding, or 100-turn acceptance.
- Never select one trim/year when multiple canonical candidates match.
- Unknown or unmatched trims continue to return no evidence.
- Keep the exact-year lookup path and yearless model-only lookup behavior unchanged.
- No changes to intent routing, model prompts, retries, response verification, handoff, schema, or public API.

---

### Task 1: Resolve short trims in yearless catalog lookups

**Files:**
- Modify: `src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java`
- Modify: `src/test/java/com/ford/service/ai/CatalogToolsTest.java`

**Interfaces:**
- Keep public `getVehicle(String model, Integer year, String trim)` signature unchanged.
- For `year == null && trim != null`, use candidate years already found by the existing model search; resolve each year through the existing `CatalogVehicleResolver` and name index.
- Query `CatalogPort.find` only with canonical `VehicleLookup(model, year, trim)` identities.
- Return the existing `ModelYearOptions` result. Preserve multiple canonical candidates; do not return an arbitrary winner.

- [x] **Step 1: Add failing yearless alias tests**

In `CatalogToolsTest`, add cases using `FakeCatalog`:

1. Search returns Ranger model-year rows for 2027 and 2026. The name index contains `XLT Gasolina 4x2` and `XLT Diésel 4x4` for 2027, plus `XLT Sport Gasolina 4x2` for 2026. Populate `FakeCatalog.vehicles` with those exact canonical trims. Calling `getVehicle("Ranger", null, "XLT")` must return `ModelYearOptions` containing all three exact variants in descending year order.
2. A trim alias with no canonical match returns `ModelYearOptions` with `no_model_year_options_found` and an empty `options` list.
3. Existing `getVehicleReturnsYearOptionsWhenYearIsOmitted` and exact-year alias tests continue to pass unchanged.

Example assertion for case 1:

```java
import static org.assertj.core.api.Assertions.tuple;

assertThat(options.options())
        .extracting(VehicleEvidence::year, VehicleEvidence::trim)
        .containsExactly(
                tuple(2027, "XLT Diésel 4x4"),
                tuple(2027, "XLT Gasolina 4x2"),
                tuple(2026, "XLT Sport Gasolina 4x2"));
```

- [x] **Step 2: Run the focused test and observe RED**

From `ford-ai-service`, run:

```bash
./gradlew test --tests com.ford.service.ai.CatalogToolsTest
```

Expected: the new short-alias test fails because the yearless path currently uses the alias as an exact trim for every model year and returns no canonical variants.

- [x] **Step 3: Canonicalize only the yearless trim path**

In `CatalogTools.availableVehicles(model, trim)`, retain the existing search and distinct descending candidate-year calculation. When `trim` is blank, keep the current exact year lookup loop. When `trim` is present:

1. Read `catalog.nameIndexEntries()` once.
2. For each candidate year, call `vehicleResolver.resolve(model, year, trim, entries)`.
3. For `EXACT` or `UNIQUE_CANONICAL_MATCH`, add `resolution.exact()`; for `AMBIGUOUS`, add every `resolution.choices()` entry; for `MISSING` or `NOT_REQUIRED`, add none.
4. Deduplicate candidates by normalized model, year, and canonical trim; preserve descending year and resolver choice order.
5. Call `catalog.find(new VehicleLookup(candidate.model(), candidate.year(), candidate.trim()))`; drop absent values and enrich returned evidence as before.

Do not modify the `year != null` branch, which already resolves partial trim aliases and returns `AmbiguousVehicleTrim` when needed.

- [x] **Step 4: Rerun focused and full unit tests**

Run:

```bash
./gradlew test --tests com.ford.service.ai.CatalogToolsTest
./gradlew test
```

Expected: both commands finish with `BUILD SUCCESSFUL`. Existing model-only and exact-year behavior remains unchanged; unknown aliases still return no evidence.

- [x] **Step 5: Review and commit**

Run `git diff --check`, inspect the diff, then commit:

```bash
git add src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java src/test/java/com/ford/service/ai/CatalogToolsTest.java
git commit -m "fix: resolve yearless vehicle trim aliases"
```

## Self-Review

- Spec coverage: alias canonicalization, cross-year candidates, ambiguity preservation, no-match fail-closed behavior, and unchanged existing paths are covered by Task 1 tests and implementation.
- Scope is limited to the catalog tool and its focused unit tests; no replay or database work required.
- No placeholders, public API changes, or production data writes are included.
