package com.ford.service.ai.adapter.in.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.PromotionEvidence;
import com.ford.service.catalog.api.PromotionQuery;
import com.ford.service.catalog.api.PromotionPage;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.OfferEvidence;
import com.ford.service.catalog.api.CatalogPageLinkEvidence;
import com.ford.service.catalog.api.CatalogPageLinkPage;
import com.ford.service.catalog.api.DocumentProvenance;
import com.ford.service.catalog.api.CatalogStatus;
import com.ford.service.catalog.api.CatalogRetrievalPort;
import com.ford.service.catalog.api.CatalogSearchPage;
import com.ford.service.catalog.api.VariantComparisonEvidence;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleFact;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.VehicleSearchQuery;
import com.ford.service.catalog.application.ModelPopularityEnricher;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Spring AI's local, read-only tool surface for the Ford-scraping catalog. */
public final class CatalogTools {

    private final CatalogPort catalog;
    private final CatalogRetrievalPort retrieval;
    private final ModelPopularityEnricher popularityEnricher;
    private final com.ford.service.catalog.application.CatalogPopularityRecommendationService popularityService;
    private final com.ford.service.ai.application.CatalogVehicleResolver vehicleResolver =
            new com.ford.service.ai.application.CatalogVehicleResolver();

    public CatalogSearchPage<VehicleEvidence> searchVehicles(String query, String model, Integer modelYear,
            BigDecimal minPrice, BigDecimal maxPrice, String category, String bodyType, String fuelType, Integer limit) {
        var filters = publishedVehicleFilters(category, bodyType);
        requireVehicleSearchCriteria(query, model, modelYear, minPrice, maxPrice, filters, fuelType);
        var selectedYear = isBlank(query) ? modelYear : explicitYear(query, modelYear);
        return searchVehicles(new VehicleSearchQuery(query, model, selectedYear, minPrice, maxPrice,
                filters.category(), filters.bodyType(), fuelType, limit == null ? 5 : Math.min(limit, 5)));
    }

    public CatalogTools(CatalogPort catalog) {
        this(catalog, null);
    }

    public CatalogTools(CatalogPort catalog, CatalogRetrievalPort retrieval) {
        this(catalog, retrieval, null);
    }

    public CatalogTools(CatalogPort catalog, CatalogRetrievalPort retrieval,
                        ModelPopularityEnricher popularityEnricher) {
        this.catalog = Objects.requireNonNull(catalog);
        this.retrieval = retrieval;
        var index = popularityEnricher == null
                ? new com.ford.service.catalog.adapter.out.ClasspathModelPopularityIndex()
                : popularityEnricher.index();
        this.popularityEnricher = popularityEnricher == null ? new ModelPopularityEnricher(index) : popularityEnricher;
        this.popularityService = new com.ford.service.catalog.application.CatalogPopularityRecommendationService(catalog, index);
    }

    public VehicleSearchResult searchVehicles(String query, String model, Integer modelYear, BigDecimal minPrice,
            BigDecimal maxPrice, String category, String bodyType, String fuelType, Integer limit, String rankingMode) {
        return searchVehicles(query, model, modelYear, minPrice, maxPrice, category, bodyType, fuelType, limit,
                rankingMode, null);
    }

    @Tool(name = "search_vehicles", description = "Busca modelos Ford respetando siempre las restricciones del cliente. Use mostSoldEligibleModel como evidencia de ventas a nivel modelo, nunca afirme que una versión es la más vendida; incluya periodo y fuente INEGI. Las ventas no indican inventario.")
    public VehicleSearchResult searchVehicles(
            @ToolParam(description = "Texto de búsqueda opcional; úselo para búsquedas abiertas. Puede omitirse si se proporciona un filtro explícito", required = false) String query,
            @ToolParam(description = "Nombre exacto del modelo si el cliente lo indicó", required = false) String model,
            @ToolParam(description = "Año exacto indicado por el cliente", required = false) Integer modelYear,
            @ToolParam(description = "Precio mínimo en MXN indicado por el cliente", required = false) BigDecimal minPrice,
            @ToolParam(description = "Presupuesto máximo en MXN indicado por el cliente", required = false) BigDecimal maxPrice,
            @ToolParam(description = "Categoría explícita, por ejemplo SUV", required = false) String category,
            @ToolParam(description = "Carrocería explícita indicada por el cliente", required = false) String bodyType,
            @ToolParam(description = "Combustible explícito indicado por el cliente", required = false) String fuelType,
            @ToolParam(description = "Máximo de resultados, entre 1 y 5", required = false) Integer limit,
             @ToolParam(description = "FIT_FIRST predeterminado; MOST_SOLD ordena por ventas del periodo seleccionado. Si CURRENT_YTD no tiene ventas reportadas, conserve orden FIT_FIRST: no use acumuladas ni afirme ventas actuales.", required = false) String rankingMode,
             @ToolParam(description = "Periodo opcional: CUMULATIVE o CURRENT_YTD; si falta, se infiere solo por la consulta", required = false) String salesPeriod) {
        if (rankingMode != null && !rankingMode.equals("FIT_FIRST") && !rankingMode.equals("MOST_SOLD")) {
            throw new IllegalArgumentException("rankingMode must be FIT_FIRST or MOST_SOLD");
        }
        if (salesPeriod != null && !salesPeriod.equals("CUMULATIVE") && !salesPeriod.equals("CURRENT_YTD"))
            throw new IllegalArgumentException("salesPeriod must be CUMULATIVE or CURRENT_YTD");
        var filters = publishedVehicleFilters(category, bodyType);
        requireVehicleSearchCriteria(query, model, modelYear, minPrice, maxPrice, filters, fuelType);
        var selectedYear = isBlank(query) ? modelYear : explicitYear(query, modelYear);
        var searchQuery = new VehicleSearchQuery(query, model, selectedYear,
                minPrice, maxPrice, filters.category(), filters.bodyType(), fuelType,
                limit == null ? 5 : Math.min(limit, 5));
        var fit = searchVehicles(searchQuery);
        var currentTrend = salesPeriod == null ? isCurrentTrend(query) : salesPeriod.equals("CURRENT_YTD");
        var recommendation = popularityService.recommend(searchQuery, currentTrend, searchQuery.limit());
        var mode = rankingMode == null ? "FIT_FIRST" : rankingMode;
        var result = fit.results();
        var resultCount = fit.resultCount();
        if (mode.equals("MOST_SOLD")) {
            if (!recommendation.results().isEmpty()) {
                result = recommendation.results().stream().map(popularityEnricher::enrich).toList();
            } else if (currentTrend) {
                // Approved fallback: current-trend is unavailable, so retain ordinary fit results without
                // presenting them as a sales ranking.
                result = fit.results();
            } else {
                // Cumulative MOST_SOLD must never present unranked FIT_FIRST vehicles as sales-ranked.
                result = List.of();
                resultCount = 0;
            }
        }
        boolean unavailable = currentTrend && recommendation.mostSoldEligibleModel() == null;
        boolean noEligiblePopularity = mode.equals("MOST_SOLD") && !currentTrend && recommendation.results().isEmpty();
        return new VehicleSearchResult(query, fit.semanticSearch(), resultCount, result, mode,
                recommendation.mostSoldEligibleModel(), recommendation.periodDisclosure(),
                unavailable ? "UNAVAILABLE_NO_REPORTED_YTD"
                        : noEligiblePopularity ? "UNAVAILABLE_NO_ELIGIBLE_POPULARITY" : "AVAILABLE",
                salesPeriod == null ? currentTrend ? "CURRENT_YTD" : "CUMULATIVE" : salesPeriod);
    }

    private static boolean isCurrentTrend(String query) {
        if (query == null) return false;
        var normalized = java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("(?s).*(actualmente|actual|tendencia|este ano|en lo que va del ano|durante 2026|ventas de 2026|al dia de hoy|reciente|ultimos meses|ytd).*");
    }

    @Tool(name = "get_vehicle", description = "Consulta datos y especificaciones del modelo/año/versión exactos. Si no hay coincidencia exacta, no sustituya otra versión. Incluya enlaces y fecha de observación.")
    public Object getVehicle(
            @ToolParam(description = "Modelo Ford exacto") String model,
            @ToolParam(description = "Año exacto, opcional", required = false) Integer year,
            @ToolParam(description = "Versión exacta, opcional", required = false) String trim) {
        if (isBlank(model)) {
            throw new IllegalArgumentException("model is required");
        }
        if (year != null) {
            var resolvedTrim = trim;
            if (!isBlank(trim)) {
                var resolution = vehicleResolver.resolve(model, year, trim, catalog.nameIndexEntries());
                if (resolution.status() == com.ford.service.ai.application.CatalogVehicleResolution.Status.AMBIGUOUS) {
                    return new AmbiguousVehicleTrim(model, year, resolution.choices());
                }
                if (resolution.status() == com.ford.service.ai.application.CatalogVehicleResolution.Status.UNIQUE_CANONICAL_MATCH) {
                    resolvedTrim = resolution.exact().trim();
                }
            }
            var vehicle = catalog.find(new VehicleLookup(model, year, resolvedTrim));
            if (vehicle.isPresent()) return enrich(vehicle.orElseThrow());
            var options = availableVehicles(model, resolvedTrim);
            var knownYears = availableVehicles(model, null).stream().map(VehicleEvidence::year).toList();
            var status = knownYears.contains(year) ? "variant_not_found"
                    : knownYears.isEmpty() ? "no_model_year_options_found" : "model_year_not_found";
            return new UnavailableModelYear(model, year, status,
                    knownYears, options);
        }
        var options = availableVehicles(model, trim);
        return new ModelYearOptions(model,
                options.isEmpty() ? "no_model_year_options_found" : "available_model_year_options", options);
    }

    private List<VehicleEvidence> availableVehicles(String model, String trim) {
        return catalog.search(new VehicleSearchQuery(null, model, null,
                        null, null, null, null, null, 5)).stream()
                .filter(vehicle -> normalize(vehicle.model()).equals(normalize(model)))
                .map(VehicleEvidence::year)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator.reverseOrder())
                .map(year -> catalog.find(new VehicleLookup(model, year, trim)).orElse(null))
                .filter(Objects::nonNull)
                .map(this::enrich)
                .toList();
    }

    @Tool(name = "search_offers", description = "Consulta términos estructurados publicados de Ford Credit y ofertas. Use solo registros vigentes con fuente; no calcule mensualidades, descuentos ni aprobaciones.")
    public OfferSearch searchOffers(
            @ToolParam(description = "Consulta breve opcional", required = false) String query,
            @ToolParam(description = "Modelo Ford exacto, opcional", required = false) String model,
            @ToolParam(description = "Año exacto, opcional", required = false) Integer modelYear,
            @ToolParam(description = "Tipo publicado, por ejemplo financing o months_without_interest", required = false) String offerType,
            @ToolParam(description = "Proveedor exacto, opcional", required = false) String provider,
            @ToolParam(description = "Precio de contado máximo solicitado, MXN", required = false) BigDecimal maxCashPrice,
            @ToolParam(description = "Mensualidad máxima solicitada, MXN", required = false) BigDecimal maxMonthlyPayment,
            @ToolParam(description = "Enganche máximo solicitado, MXN", required = false) BigDecimal maxDownPayment,
            @ToolParam(description = "Meses sin intereses mínimos solicitados", required = false) Integer minimumMonthsWithoutInterest,
            @ToolParam(description = "Plazo exacto solicitado en meses", required = false) Integer termMonths,
            @ToolParam(description = "Fecha de consulta ISO-8601", required = false) LocalDate asOf,
            @ToolParam(description = "Máximo de resultados entre 1 y 50", required = false) Integer limit) {
        var effectiveQuery = !isBlank(query) ? query.trim()
                : java.util.stream.Stream.of(model, modelYear == null ? null : modelYear.toString(), offerType)
                .filter(value -> !isBlank(value)).collect(java.util.stream.Collectors.joining(" "));
        if (effectiveQuery.isBlank()) effectiveQuery = "Ford offers";
        var offerQuery = new OfferQuery(isBlank(query) ? null : query.trim(), model, modelYear, offerType,
                provider, maxCashPrice, maxMonthlyPayment, maxDownPayment, minimumMonthsWithoutInterest,
                termMonths, true, asOf, limit == null ? 2 : limit);
        var page = searchOffers(offerQuery);
        var offers = page.results();
        var results = offers.stream().map(CatalogTools::compactOffer).toList();
        var disclosures = results.stream().filter(result -> result.validityDisclosure() != null)
                .map(result -> (isBlank(result.title()) ? "Esta oferta" : result.title())
                        + ": " + result.validityDisclosure())
                .distinct().toList();
        return new OfferSearch(effectiveQuery, page.semanticSearch(), results.size(), results.size(), disclosures,
                results);
    }

    private static OfferResult compactOffer(OfferEvidence offer) {
        var currency = isBlank(offer.currency()) ? "MXN" : offer.currency();
        var cashPriceDisplay = priceDisplay(offer.cashPrice(), currency);
        var monthlyPaymentDisplay = priceDisplay(offer.monthlyPayment(), currency);
        var downPaymentDisplay = priceDisplay(offer.downPaymentAmount(), currency);
        var validityDisclosure = "unknown".equalsIgnoreCase(offer.validityStatus())
                ? "La vigencia no está confirmada."
                : null;
        var summary = java.util.stream.Stream.of(
                        (isBlank(offer.title()) ? "Esta oferta" : offer.title()) + ".",
                        cashPriceDisplay == null ? null : "Precio de contado: " + cashPriceDisplay + ".",
                        monthlyPaymentDisplay == null ? null : "Mensualidad: " + monthlyPaymentDisplay + ".",
                        validityDisclosure)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(" "));
        return new OfferResult(offer.offerId(), offer.model(), offer.modelYear(), offer.title(), offer.offerType(), offer.provider(),
                offer.cashPrice(), cashPriceDisplay, offer.monthlyPayment(), monthlyPaymentDisplay, summary,
                offer.downPaymentAmount(), downPaymentDisplay, offer.downPaymentPercent(), offer.termMonths(),
                offer.monthsWithoutInterest(), offer.annualInterestRate(), offer.catPercent(), currency,
                offer.validityStatus(), offer.validFrom(), offer.validUntil(), offer.requiresCreditApproval(),
                offer.conditions(), validityDisclosure, offer.sourceUrl(), offer.modelPageUrl(), offer.observedAt(),
                offer.benefits(), offer.termConflict(), offer.taxRatePercent(), offer.openingCommissionRatePercent(),
                offer.maximumDownPaymentPercent(), offer.lifeInsurancePrice());
    }

    private CatalogSearchPage<VehicleEvidence> searchVehicles(VehicleSearchQuery query) {
        if (retrieval != null) {
            var page = retrieval.searchVehicles(query);
            return new CatalogSearchPage<>(page.semanticSearch(), page.resultCount(),
                    page.results().stream().map(this::enrich).toList());
        }
        var results = catalog.search(query);
        var semanticSearch = isBlank(query.query()) ? "not_requested" : "unavailable";
        return new CatalogSearchPage<>(semanticSearch, results.size(), results.stream().map(this::enrich).toList());
    }

    private VehicleEvidence enrich(VehicleEvidence vehicle) {
        return popularityEnricher.enrich(vehicle);
    }

    private CatalogSearchPage<OfferEvidence> searchOffers(OfferQuery query) {
        if (retrieval != null) return retrieval.searchOffers(query);
        var results = catalog.offers(query);
        var semanticSearch = isBlank(query.query()) ? "not_requested" : "unavailable";
        return new CatalogSearchPage<>(semanticSearch, results.size(), results);
    }

    private static String priceDisplay(BigDecimal amount, String currencyCode) {
        if (amount == null) return null;
        var formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX"));
        formatter.setCurrency(Currency.getInstance(currencyCode));
        formatter.setMaximumFractionDigits(0);
        formatter.setMinimumFractionDigits(0);
        return currencyCode + " " + formatter.format(amount);
    }

    @Tool(name = "search_vehicle_and_offers", description = "Busca hasta un vehículo y su financiamiento para el mismo modelo/año exactos. Use answerSummary como respuesta concisa; no mezcle precio de lista con precio de crédito.")
    public CombinedSearch searchVehicleAndOffers(
            @ToolParam(description = "Consulta breve opcional", required = false) String query,
            @ToolParam(description = "Modelo Ford exacto, opcional", required = false) String model,
            @ToolParam(description = "Año exacto, opcional", required = false) Integer year,
            @ToolParam(description = "Alias de año modelo", required = false) Integer modelYear,
            @ToolParam(description = "Presupuesto máximo de contado, MXN", required = false) BigDecimal budget,
            @ToolParam(description = "Plazo solicitado en meses", required = false) Integer termMonths) {
        var selectedYear = year == null ? modelYear : year;
        var subject = java.util.stream.Stream.of(model, selectedYear == null ? null : selectedYear.toString())
                .filter(value -> !isBlank(value)).collect(java.util.stream.Collectors.joining(" "));
        var requestQuery = isBlank(query) ? null : query.trim();
        var effectiveQuery = requestQuery != null ? requestQuery
                : subject.isBlank() ? "Ford vehicles" : subject;
        var vehicleQuery = new VehicleSearchQuery(requestQuery, model, selectedYear,
                null, budget, null, null, null, 1);
        var vehiclePage = searchVehicles(vehicleQuery);
        var vehicles = vehiclePage.results().stream()
                .filter(vehicle -> selectedYear == null || Objects.equals(vehicle.year(), selectedYear))
                .filter(vehicle -> isBlank(model) || normalize(vehicle.model()).equals(normalize(model)))
                .limit(1).toList();
        var firstVehicle = vehicles.isEmpty() ? null : vehicles.getFirst();
        var matchedModel = isBlank(model) ? firstVehicle == null ? null : firstVehicle.model() : model;
        var matchedYear = selectedYear == null ? firstVehicle == null ? null : firstVehicle.year() : selectedYear;
        var offerQuery = java.util.stream.Stream.of(
                         requestQuery, isBlank(matchedModel) ? null : matchedModel,
                         matchedYear == null ? null : matchedYear.toString(), "financing")
                 .filter(value -> !isBlank(value)).collect(java.util.stream.Collectors.joining(" "));
        var asOf = LocalDate.now(java.time.ZoneOffset.UTC);
        var offerPage = isBlank(matchedModel)
                ? new CatalogSearchPage<OfferEvidence>("not_requested", 0, List.of())
                : searchOffers(new OfferQuery(requestQuery, matchedModel, matchedYear, "financing", null,
                        budget, null, null, null, termMonths, true, asOf, 1));
        var rawOffers = offerPage.results();
        var offerResults = rawOffers.stream().map(CatalogTools::compactOffer).toList();
        var requiredDisclosures = offerResults.stream()
                .filter(offer -> offer.validityDisclosure() != null)
                .map(offer -> (isBlank(offer.title()) ? "Esta oferta" : offer.title())
                        + ": " + offer.validityDisclosure())
                .distinct().toList();
        var vehicleSummary = firstVehicle == null ? null
                : (firstVehicle.model() + " " + firstVehicle.year() + ": "
                        + java.util.Optional.ofNullable(priceDisplay(firstVehicle.price(),
                                isBlank(firstVehicle.currency()) ? "MXN" : firstVehicle.currency()))
                                .orElse("precio no disponible")).trim();
        var offerSummary = offerResults.isEmpty() ? null : offerResults.getFirst().buyerSummary();
        if (offerSummary != null && budget != null && firstOfferCashPrice(offerResults) != null
                && firstOfferCashPrice(offerResults).compareTo(budget) > 0) {
            offerSummary += " Excede el presupuesto de " + priceDisplay(budget, "MXN") + ".";
        }
        var answerSummary = (vehicleSummary == null
                ? "No se encontró el vehículo o variante exactos."
                : vehicleSummary + ".") + " " + (offerSummary == null
                ? "No se encontraron ofertas de financiamiento."
                : offerSummary);
        return new CombinedSearch(effectiveQuery, answerSummary,
                new VehicleSelection(vehicles, vehicleSummary, vehicles.size(), vehiclePage.semanticSearch()),
                new FinancingSelection(offerQuery, offerResults.size(), requiredDisclosures, offerResults,
                        offerPage.semanticSearch()));
    }

    private static BigDecimal firstOfferCashPrice(List<OfferResult> offers) {
        return offers.isEmpty() ? null : offers.getFirst().cashPrice();
    }

    @Tool(name = "get_page_links", description = "Devuelve enlaces almacenados de una página Ford conocida. Use solo una URL obtenida del catálogo; no construya URLs.")
    public PageLinkSearch getPageLinks(
            @ToolParam(description = "URL exacta obtenida previamente del catálogo") String sourceUrl,
            @ToolParam(description = "Desplazamiento de paginación", required = false) Integer offset,
            @ToolParam(description = "Resultados por página, máximo 200", required = false) Integer limit,
            @ToolParam(description = "Incluir rutas de imágenes y assets", required = false) Boolean includeAssets) {
        var page = catalog.pageLinks(sourceUrl, offset == null ? 0 : offset,
                limit == null ? 20 : limit, Boolean.TRUE.equals(includeAssets));
        var links = page.links().stream().map(link -> new PageLinkItem(
                isBlank(link.targetUrl()) ? link.href() : link.targetUrl(),
                link.anchorText(), link.linkType(), link.internal())).toList();
        return new PageLinkSearch(sourceUrl, page.knownSource(), page.title(), page.totalLinks(), page.offset(), page.limit(), links);
    }

    @Tool(name = "get_vehicle_provenance", description = "Devuelve procedencia de catálogo para un identificador de documento obtenido previamente; uso diagnóstico.")
    public Optional<DocumentProvenance> getVehicleProvenance(
            @ToolParam(description = "Identificador exacto de documento devuelto por una herramienta") String documentId) {
        return catalog.provenance(documentId);
    }

    @Tool(name = "get_catalog_status", description = "Devuelve conteos y última sincronización del catálogo. No representa inventario disponible en el distribuidor.")
    public CatalogStatus getCatalogStatus() {
        var status = catalog.status();
        return new CatalogStatus(status.modelCount(), status.activeModelYearCount(), status.variantCount(),
                status.pageCount(), status.documentCount(), status.embeddedDocumentCount(),
                status.pendingEmbeddingCount(), status.latestPageCrawlAt(), status.latestSyncStatus(),
                status.latestSyncStartedAt(), status.latestSyncFinishedAt(), status.pageCount(),
                status.activeModelYearCount(), status.embeddedDocumentCount());
    }

    @Tool(name = "compare_vehicles", description = "Compara dos a cinco modelos Ford con año opcional usando únicamente hechos publicados. Los datos faltantes no prueban que una característica no exista.")
    public List<VehicleEvidence> compareVehicles(
            @ToolParam(description = "Dos a cinco modelos Ford exactos") List<VehicleTarget> vehicles) {
        requireCount(vehicles, 2, 5, "vehicles");
        return vehicles.stream().map(target -> catalog.find(new VehicleLookup(target.model(), target.year(), null))
                .orElseThrow(() -> new IllegalArgumentException(
                        target.model() + " " + target.year() + " was not found")))
                .map(this::enrich)
                .toList();
    }

    @Tool(name = "compare_trims", description = "Compara dos a cinco versiones exactas del mismo modelo y año. El resumen devuelve hasta cinco diferencias respaldadas por fuentes por par; use detail=full para todas las diferencias. Las diferencias vacías indican que no hay hechos emparejados en el catálogo, no que las versiones sean idénticas.")
    public TrimComparison compareTrims(
            @ToolParam(description = "Modelo Ford exacto") String model,
            @ToolParam(description = "Año exacto") Integer year,
            @ToolParam(description = "Dos a cinco versiones exactas") List<String> trims,
            @ToolParam(description = "summary (predeterminado) o full", required = false) String detail) {
        requireCount(trims, 2, 5, "trims");
        var found = trims.stream().map(trim -> lookup(model, year, trim))
                .filter(VehicleComparison::found).map(VehicleComparison::evidence)
                .collect(java.util.stream.Collectors.toMap(vehicle -> normalize(vehicle.trim()),
                        java.util.function.Function.identity(), (first, ignored) -> first,
                        java.util.LinkedHashMap::new)).values().stream()
                .sorted(java.util.Comparator.comparing(VehicleEvidence::trim,
                        java.util.Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))).toList();
        var missing = trims.stream().filter(requested -> found.stream()
                .noneMatch(vehicle -> normalizeTrimForModel(model, vehicle.trim())
                        .equals(normalizeTrimForModel(model, requested)))).toList();
        var pairs = new ArrayList<TrimPairResult>();
        for (int left = 0; left < found.size(); left++) {
            for (int right = left + 1; right < found.size(); right++) {
                var a = found.get(left);
                var b = found.get(right);
                var allDifferences = sharedDifferences(a.facts(), b.facts(), true).stream()
                        .map(difference -> new TrimFactDifference(normalize(difference.name()), difference.name(),
                                new TrimFactSide(a.trim(), difference.left().value(), difference.left().sourceUrl()),
                                new TrimFactSide(b.trim(), difference.right().value(), difference.right().sourceUrl())))
                        .sorted(java.util.Comparator.comparingInt(fact -> trimFactPriority(fact.name())))
                        .toList();
                var fullDetail = "full".equalsIgnoreCase(detail);
                var selectedDifferences = fullDetail ? allDifferences : allDifferences.stream().limit(5).toList();
                var priceDifference = difference(a.price(), b.price());
                var hasPriceChange = priceDifference != null && priceDifference.signum() != 0;
                pairs.add(new TrimPairResult(a.trim(), b.trim(),
                        selectedDifferences.isEmpty() && !hasPriceChange
                                ? "no_source_backed_differences" : "source_backed_differences_found",
                        priceDifference, selectedDifferences,
                        allDifferences.size() > selectedDifferences.size()
                                ? allDifferences.size() - selectedDifferences.size() : null));
            }
        }
        var modelPageUrl = found.stream().map(VehicleEvidence::modelPageUrl)
                .filter(value -> !isBlank(value)).findFirst().orElse(null);
        var technicalSheetUrl = found.stream().map(VehicleEvidence::technicalSheetUrl)
                .filter(value -> !isBlank(value)).findFirst().orElse(null);
        var trimResults = found.stream().map(vehicle -> new TrimResult(vehicle.trim(), vehicle.price(),
                vehicle.currency(), vehicle.priceSourceUrl(), vehicle.comparisonUrl())).toList();
        return new TrimComparison(model, year, modelPageUrl, technicalSheetUrl, trimResults, pairs, missing,
                "Only source-backed variant facts are included; absent values are unknown.");
    }

    /** Compatibility helper for internal callers; the registered tool uses the explicit detail argument. */
    public TrimComparison compareTrims(String model, Integer year, List<String> trims) {
        return compareTrims(model, year, trims, "summary");
    }

    private static int trimFactPriority(String name) {
        if (name.matches("(?i).*(motor|potencia|horsepower|torque|transmisi[oó]n|combustible|h[ií]brid).*")) return 0;
        if (name.matches("(?i).*(seguridad|airbag|freno|asistencia|c[aá]mara|adapta|control crucero).*")) return 1;
        if (name.matches("(?i).*(pasajero|carga|cajuela|dimensi|arrastre|tracci[oó]n|bater[ií]a).*")) return 2;
        return 3;
    }

    @Tool(name = "compare_model_years", description = "Compara dos a cinco años modelo del mismo nombre Ford. Use solo hechos observados; una ausencia de datos no demuestra que algo se haya eliminado.")
    public ModelYearComparison compareModelYears(
            @ToolParam(description = "Nombre exacto del modelo Ford") String model,
            @ToolParam(description = "Dos a cinco años modelo exactos") List<Integer> years) {
        requireCount(years, 2, 5, "years");
        var found = years.stream().map(year -> lookup(model, year, null))
                .filter(VehicleComparison::found).map(VehicleComparison::evidence)
                .collect(java.util.stream.Collectors.toMap(VehicleEvidence::year,
                        java.util.function.Function.identity(), (first, ignored) -> first,
                        java.util.LinkedHashMap::new)).values().stream()
                .sorted(java.util.Comparator.comparing(VehicleEvidence::year)).toList();
        var missing = years.stream().filter(year -> found.stream().noneMatch(vehicle -> vehicle.year().equals(year)))
                .toList();
        var snapshots = found.stream().map(vehicle -> new ModelYearSnapshot(vehicle,
                catalog.promotions(new PromotionQuery(vehicle.model(), vehicle.year(), null, true, 20))
                        .results()))
                .toList();
        var pairs = new ArrayList<ModelYearPair>();
        for (int left = 0; left < found.size(); left++) {
            for (int right = left + 1; right < found.size(); right++) {
                var older = found.get(left);
                var newer = found.get(right);
                if (older.year() > newer.year()) {
                    var swap = older;
                    older = newer;
                    newer = swap;
                }
                pairs.add(new ModelYearPair(older.year(), newer.year(), difference(older.price(), newer.price()),
                        sharedDifferences(older.facts(), newer.facts(), false)));
            }
        }
        return new ModelYearComparison(model, years, snapshots, pairs, missing,
                "Only source-backed values present for both model years are compared; missing values are unknown.");
    }

    @Tool(name = "get_variant_comparisons", description = "Consulta comparativos oficiales Ford frente a otra marca para modelo y versión exactos. Si no hay filas, informe que el comparativo oficial no está disponible.")
    public List<VariantComparisonEvidence> getVariantComparisons(
            @ToolParam(description = "Modelo base Ford sin versión") String model,
            @ToolParam(description = "Año exacto del modelo") Integer year,
            @ToolParam(description = "Versión Ford exacta") String trim,
            @ToolParam(description = "Desplazamiento de paginación", required = false) Integer offset,
            @ToolParam(description = "Máximo de resultados entre 1 y 50", required = false) Integer limit) {
        if (isBlank(model) || isBlank(trim)) {
            throw new IllegalArgumentException("model and trim are required");
        }
        return catalog.variantComparisons(model, year, trim, offset == null ? 0 : offset,
                limit == null ? 3 : limit);
    }

    private VehicleComparison lookup(String model, Integer year, String trim) {
        var result = catalog.find(new VehicleLookup(model, year, trim)).map(this::enrich);
        return new VehicleComparison(model, year, trim, result.orElse(null), result.isPresent());
    }

    private static List<SharedFactDifference> sharedDifferences(List<VehicleFact> left,
                                                                 List<VehicleFact> right,
                                                                 boolean filterInvalidComparisonValues) {
        Map<String, VehicleFact> rightByName = new HashMap<>();
        right.forEach(fact -> rightByName.put(factKey(fact), fact));
        return left.stream().filter(fact -> !filterInvalidComparisonValues || !fact.name().equalsIgnoreCase("Precio"))
                .filter(fact -> comparable(fact.value(), filterInvalidComparisonValues))
                .filter(fact -> rightByName.containsKey(factKey(fact)))
                .map(fact -> Map.entry(fact, rightByName.get(factKey(fact))))
                .filter(entry -> comparable(entry.getValue().value(), filterInvalidComparisonValues)
                        && valueKind(entry.getKey().value()).equals(valueKind(entry.getValue().value()))
                        && !isInvalidQuantitativeBoolean(entry.getKey())
                        && !isInvalidQuantitativeBoolean(entry.getValue())
                        && !normalizeComparisonValue(entry.getKey().value())
                                .equals(normalizeComparisonValue(entry.getValue().value())))
                .map(entry -> new SharedFactDifference(entry.getKey().name(),
                        side(entry.getKey()), side(entry.getValue())))
                .toList();
    }

    private static boolean comparable(String value, boolean trimComparison) {
        if (value == null || value.isBlank() || value.contains("|")) return false;
        return !value.trim().matches(trimComparison
                ? "(?i)(?:[-—]|n/?a)"
                : "(?i)(?:[-—]|n/?a|no disponible)");
    }

    private static String valueKind(String value) {
        if (value == null || value.isBlank() || value.trim().matches("(?i)(?:[-—]|n/?a)")) return "missing";
        if (value.trim().matches("(?i)(?:s[ií]|no)")) return "boolean";
        if (value.trim().matches("[\\s$€£¥\\d.,%+-]+")) return "numeric";
        return "text";
    }

    private static boolean isInvalidQuantitativeBoolean(VehicleFact fact) {
        return fact.name() != null
                && fact.name().matches("(?i).*(precio|rendimiento|consumo|econom[ií]a|potencia|torque|capacidad|arrastre|velocidad|kilometraje|autonom[ií]a).*")
                && valueKind(fact.value()).equals("boolean");
    }

    private static FactSide side(VehicleFact fact) {
        return new FactSide(fact.type(), fact.value(), fact.unit(), fact.sourceUrl(), fact.observedAt());
    }

    private static String factKey(VehicleFact fact) {
        return normalize(fact.type()) + ":" + normalize(fact.name());
    }

    private static BigDecimal difference(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : right.subtract(left);
    }

    private static String normalize(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(value.trim().toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String normalizeComparisonValue(String value) {
        // Cosmetic source spelling is not an equipment change. Preserve decimal points,
        // signs, separators, and technical punctuation: 2.0 L must not become 20 L.
        return java.text.Normalizer.normalize(value.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replaceAll("[\\s\\p{Zs}]+", " ").strip();
    }

    /**
     * The published feed groups pickups under "Camiones" and leaves body_type empty. Accept the
     * buyer-facing body style without passing it as an unpublished filter, which would hide all
     * otherwise valid candidates.
     */
    private static SearchFilters publishedVehicleFilters(String category, String bodyType) {
        if (normalize(category).contains("pickup") || normalize(bodyType).contains("pickup")) {
            return new SearchFilters("Camiones", null);
        }
        return new SearchFilters(category, bodyType);
    }

    private record SearchFilters(String category, String bodyType) {
    }

    private static String normalizeTrimForModel(String model, String trim) {
        var modelKey = normalize(model);
        var trimKey = normalize(trim);
        return trimKey.startsWith(modelKey) && trimKey.length() > modelKey.length()
                ? trimKey.substring(modelKey.length()) : trimKey;
    }

    private static void requireCount(List<?> values, int min, int max, String name) {
        if (values == null || values.size() < min || values.size() > max
                || values.stream().anyMatch(value -> value == null || value.toString().isBlank())) {
            throw new IllegalArgumentException(name + " must contain " + min + " to " + max + " exact values");
        }
    }

    @Tool(name = "list_promotions", description = "Consulta promociones Ford publicadas y su vigencia. No convierta una promoción vencida o sin evidencia en una oferta vigente.")
    public PromotionSearch listPromotions(
            @ToolParam(description = "Modelo Ford obligatorio para filtrar promociones de ese vehículo", required = true) String model,
            @ToolParam(description = "Año exacto; solo úselo si el mismo año aparece en query", required = false) Integer modelYear,
            @ToolParam(description = "Fecha ISO-8601; úsela solo si la misma fecha aparece en query", required = false) LocalDate asOf,
            @ToolParam(description = "Mostrar solo promociones vigentes en la fecha indicada", required = false) Boolean activeOnly,
            @ToolParam(description = "Desplazamiento de paginación", required = false) Integer offset,
            @ToolParam(description = "Máximo de resultados, entre 1 y 20", required = false) Integer limit,
            @ToolParam(description = "summary o full", required = false) String detail,
            @ToolParam(description = "Consulta opcional; incluya el año solo si el cliente lo pidió", required = false) String query) {
        if (isBlank(model)) {
            throw new IllegalArgumentException("model is required");
        }
        if (detail != null && !detail.equalsIgnoreCase("summary") && !detail.equalsIgnoreCase("full")) {
            throw new IllegalArgumentException("detail must be summary or full");
        }
        var page = catalog.promotions(new PromotionQuery(model.trim(), explicitYear(query, modelYear),
                explicitDate(query, asOf),
                Boolean.TRUE.equals(activeOnly), limit == null ? 3 : limit, offset == null ? 0 : offset));
        return new PromotionSearch(page.totalCount(), page.offset(), page.limit(), page.hasMore(),
                page.results().stream().map(promotion -> compactPromotion(promotion,
                        "full".equalsIgnoreCase(detail))).toList());
    }

    private static PromotionResult compactPromotion(PromotionEvidence promotion, boolean fullDetail) {
        var conditions = promotion.conditions();
        var conditionsOmitted = !fullDetail && conditions != null && conditions.length() > 280;
        return new PromotionResult(promotion.id(), promotion.model(), promotion.modelYear(), promotion.title(),
                promotion.discountAmount(), promotion.discountPercent(), promotion.cashPrice(), promotion.currency(),
                fullDetail ? promotion.description() : null,
                conditionsOmitted ? null : conditions, conditionsOmitted ? true : null,
                promotion.validFrom(), promotion.validUntil(), promotion.validityStatus(), promotion.sourceUrl(),
                promotion.observedAt(), promotion.modelUrl(), promotion.benefits(), promotion.termConflict());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireVehicleSearchCriteria(String query, String model, Integer modelYear,
            BigDecimal minPrice, BigDecimal maxPrice, SearchFilters filters, String fuelType) {
        if (isBlank(query) && isBlank(model) && modelYear == null && minPrice == null && maxPrice == null
                && isBlank(filters.category()) && isBlank(filters.bodyType()) && isBlank(fuelType)) {
            throw new IllegalArgumentException("query, model, or an explicit filter is required");
        }
    }

    private static Integer explicitYear(String query, Integer modelYear) {
        if (modelYear == null || isBlank(query)) return null;
        return java.util.regex.Pattern.compile("(?<!\\d)" + modelYear + "(?!\\d)")
                .matcher(query).find() ? modelYear : null;
    }

    private static LocalDate explicitDate(String query, LocalDate asOf) {
        return asOf != null && !isBlank(query) && query.contains(asOf.toString()) ? asOf : null;
    }

    public record CombinedSearch(String query, String answerSummary, VehicleSelection vehicles,
                                 FinancingSelection offers) { }

    public record VehicleSearchResult(String query, String semanticSearch, int resultCount,
                                       List<VehicleEvidence> results, String rankingMode,
                                       com.ford.service.catalog.application.CatalogPopularityRecommendationService.MostSoldEligibleModel mostSoldEligibleModel,
                                       String periodDisclosure, String rankingStatus, String selectedSalesPeriod) {
        public VehicleSearchResult { results = List.copyOf(results); }
    }

    public record VehicleSelection(List<VehicleEvidence> results, String summary, int resultCount,
                                   String semanticSearch) {
        public VehicleSelection { results = List.copyOf(results); }
    }

    public record FinancingSelection(String query, int resultCount, List<String> requiredDisclosures,
                                     List<OfferResult> results, String semanticSearch) {
        public FinancingSelection {
            requiredDisclosures = List.copyOf(requiredDisclosures);
            results = List.copyOf(results);
        }
    }

    public record OfferSearch(String query, String semanticSearch, int resultCount, int totalResultCount,
                              List<String> requiredDisclosures, List<OfferResult> results) {
        public OfferSearch {
            requiredDisclosures = List.copyOf(requiredDisclosures);
            results = List.copyOf(results);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OfferResult(String offerId, String modelName, Integer modelYear, String title, String offerType, String provider,
                              BigDecimal cashPrice, String cashPriceDisplay, BigDecimal monthlyPayment,
                              String monthlyPaymentDisplay, String buyerSummary, BigDecimal downPaymentAmount,
                              String downPaymentDisplay, BigDecimal downPaymentPercent, Integer termMonths,
                              Integer monthsWithoutInterest, BigDecimal annualInterestRate, BigDecimal catPercent,
                              String currency, String validityStatus, LocalDate validFrom, LocalDate validUntil,
                              boolean requiresCreditApproval, String conditions, String validityDisclosure,
                              String sourceUrl, String modelUrl, Instant observedAt, JsonNode benefits, JsonNode termConflict,
                              BigDecimal taxRatePercent, BigDecimal openingCommissionRatePercent,
                              BigDecimal maximumDownPaymentPercent, BigDecimal lifeInsurancePrice) { }

    public record PromotionSearch(long totalCount, int offset, int limit, boolean hasMore,
                                  List<PromotionResult> results) {
        public PromotionSearch { results = List.copyOf(results); }
    }

    public record PromotionResult(String id, String model, Integer year, String title, BigDecimal discountAmount,
                                  BigDecimal discountPercent, BigDecimal cashPrice, String currency,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String description,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String conditions,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Boolean conditionsOmitted,
                                  LocalDate validFrom, LocalDate validUntil, String validityStatus,
                                  String sourceUrl, Instant observedAt,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String modelUrl,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode benefits,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode termConflict) { }

    public record PageLinkSearch(String sourceUrl, boolean knownSource,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String title,
                                 long totalLinks, int offset, int limit, List<PageLinkItem> links) {
        public PageLinkSearch { links = List.copyOf(links); }
    }

    public record PageLinkItem(String url, String text, String type, boolean internal) { }

    public record ModelYearOptions(String model, String status, List<VehicleEvidence> options) {
        public ModelYearOptions { options = List.copyOf(options); }
    }

    public record UnavailableModelYear(String model, Integer requestedYear, String status,
                                       List<Integer> availableModelYears,
                                       List<VehicleEvidence> options) {
        public UnavailableModelYear {
            availableModelYears = List.copyOf(availableModelYears);
            options = List.copyOf(options);
        }
    }

    public record AmbiguousVehicleTrim(String model, Integer modelYear,
                                       List<com.ford.service.ai.application.VehicleTrimCandidate> choices) {
        public AmbiguousVehicleTrim {
            if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
            if (modelYear == null) throw new IllegalArgumentException("model year is required");
            choices = List.copyOf(choices == null ? List.of() : choices);
            if (choices.isEmpty()) throw new IllegalArgumentException("trim choices are required");
        }
    }

    public record VehicleTarget(String model, Integer year) { }

    public record VehicleComparison(String requestedModel, Integer requestedYear, String requestedTrim,
                                    VehicleEvidence evidence, boolean found) { }

    public record TrimComparison(String model, Integer year, String modelPageUrl, String technicalSheetUrl,
                                 List<TrimResult> trims, List<TrimPairResult> comparisons,
                                 List<String> missingTrims, String note) {
        public TrimComparison { trims = List.copyOf(trims); comparisons = List.copyOf(comparisons); missingTrims = List.copyOf(missingTrims); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrimResult(String trim, BigDecimal price, String currency, String priceSourceUrl,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String comparisonUrl) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrimPairResult(String leftTrim, String rightTrim, String status, BigDecimal priceDifferenceMxn,
                                 List<TrimFactDifference> differences, Integer differencesOmitted) {
        public TrimPairResult { differences = List.copyOf(differences); }
    }

    public record TrimFactDifference(String normalizedName, String name, TrimFactSide left, TrimFactSide right) { }

    public record TrimFactSide(String trim, String value, String sourceUrl) { }

    public record ModelYearSnapshot(VehicleEvidence vehicle, List<PromotionEvidence> activePromotions) { }

    public record ModelYearComparison(String model, List<Integer> requestedYears,
                                      List<ModelYearSnapshot> years, List<ModelYearPair> yearPairs,
                                      List<Integer> missingYears, String note) { }

    public record ModelYearPair(Integer olderYear, Integer newerYear, BigDecimal priceDifferenceMxn,
                                List<SharedFactDifference> sharedFactDifferences) { }

    public record SharedFactDifference(String name, FactSide left, FactSide right) { }

    public record FactSide(String type, String value, String unit, String sourceUrl, Instant observedAt) { }
}
