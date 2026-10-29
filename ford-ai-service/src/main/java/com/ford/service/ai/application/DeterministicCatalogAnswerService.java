package com.ford.service.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.api.ResponseEvidenceStatus;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.PromotionEvidence;
import com.ford.service.catalog.api.PromotionPage;
import com.ford.service.catalog.api.PromotionQuery;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleFact;
import com.ford.service.catalog.api.VehicleLookup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Answers exact, unambiguous catalog price requests before answer-model generation. */
public class DeterministicCatalogAnswerService {
    private static final Pattern PRICE_REQUEST = Pattern.compile(
            "(?iu)\\b(precio|cuesta|cuánto cuesta|cuanto cuesta|costaría|costaria|price|how much)\\b");
    private static final Pattern PROMOTION_REQUEST = Pattern.compile(
            "(?iu)\\b(promoción|promocion|promociones|oferta|ofertas|bono|bonos|descuento|descuentos|promotion|offer|discount)\\b");
    private static final Pattern MOTOR_REQUEST = Pattern.compile("(?iu)\\b(motor|motorización|motorizacion)\\b");
    private static final String CLARIFICATION =
            "No pude confirmar un precio exacto para esa combinación. ¿Qué año y versión desea consultar?";

    private final CatalogPort catalog;
    private final CatalogFactResponseRenderer renderer;
    private final ObjectMapper objectMapper;
    private final FordCatalogSourceReferencePolicy sourceReferencePolicy = new FordCatalogSourceReferencePolicy();

    public DeterministicCatalogAnswerService(CatalogPort catalog, CatalogFactResponseRenderer renderer,
                                             ObjectMapper objectMapper) {
        this.catalog = Objects.requireNonNull(catalog);
        this.renderer = Objects.requireNonNull(renderer);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public Optional<AssistantResponse> tryAnswer(AssistantRequest request) {
        Objects.requireNonNull(request, "assistant request is required");
        var priceRequested = PRICE_REQUEST.matcher(request.message()).find();
        var promotionRequested = PROMOTION_REQUEST.matcher(request.message()).find();
        var motorRequested = MOTOR_REQUEST.matcher(request.message()).find();
        if (!priceRequested && !promotionRequested) {
            return motorRequested ? answerExactTrimMotor(request) : Optional.empty();
        }
        var entities = request.decision().vehicleEntities();
        if (entities.status() != VehicleEntityResolution.Status.RESOLVED || entities.model().isEmpty()) {
            return Optional.empty();
        }

        var model = entities.model().orElseThrow();
        var payloads = new LinkedHashMap<String, String>();
        var sourceReferences = new LinkedHashSet<String>();
        var failedLookups = new LinkedHashSet<String>();
        int evidenceCount = 0;
        var asOf = LocalDate.now(ZoneOffset.UTC);
        if (priceRequested) {
            if (entities.modelYear().isEmpty()) return Optional.empty();
            var year = entities.modelYear().orElseThrow();
            try {
                var promotions = catalog.promotions(new PromotionQuery(model, year, asOf, true, 3));
                payloads.put("list_promotions", serialize(promotions));
                promotions.results().forEach(promotion -> addPromotionSources(sourceReferences, promotion));
                evidenceCount += promotions.results().size();
                recordToolTrace(request, "list_promotions", promotions.results().size());
            } catch (RuntimeException exception) {
                failedLookups.add("list_promotions");
                recordToolTrace(request, "list_promotions", null);
            }
            try {
                var vehicle = catalog.find(new VehicleLookup(model, year, entities.trim().orElse(null)));
                if (vehicle.isPresent() && vehicle.get().price() != null) {
                    var evidence = vehicle.get();
                    payloads.put("get_vehicle", serialize(Map.of("vehicle", evidence)));
                    addVehicleSources(sourceReferences, evidence);
                    evidenceCount++;
                    recordToolTrace(request, "get_vehicle", 1);
                } else {
                    recordToolTrace(request, "get_vehicle", 0);
                }
            } catch (RuntimeException exception) {
                failedLookups.add("get_vehicle");
                recordToolTrace(request, "get_vehicle", null);
            }
        } else if (promotionRequested) {
            var promotions = catalog.promotions(new PromotionQuery(model, entities.modelYear().orElse(null),
                    asOf, true, 3));
            payloads.put("list_promotions", serialize(promotions));
            promotions.results().forEach(promotion -> addPromotionSources(sourceReferences, promotion));
            evidenceCount += promotions.results().size();
            recordToolTrace(request, "list_promotions", promotions.results().size());
        }
        var content = (priceRequested
                ? renderer.renderPriceWithOffers(request.message(), payloads, failedLookups, asOf)
                : renderer.render(request.message(), payloads, failedLookups))
                .orElse(CLARIFICATION);
        sourceReferences.removeIf(source -> content.lines().noneMatch(line -> line.endsWith(source)));
        var evidenceStatus = evidenceCount > 0 ? ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED
                : failedLookups.isEmpty() ? ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED_EMPTY
                : ResponseEvidenceStatus.READ_ONLY_TOOL_FAILED;
        recordTrace(request, evidenceCount > 0 ? "returned"
                : failedLookups.isEmpty() ? "empty" : "failed", evidenceCount);
        return Optional.of(new AssistantResponse(content, false, "deterministic-catalog",
                List.copyOf(sourceReferences), null, null, evidenceStatus));
    }

    private Optional<AssistantResponse> answerExactTrimMotor(AssistantRequest request) {
        var entities = request.decision().vehicleEntities();
        if (entities.status() != VehicleEntityResolution.Status.RESOLVED || entities.model().isEmpty()
                || entities.modelYear().isEmpty() || entities.trim().isEmpty()) {
            return Optional.empty();
        }
        var model = entities.model().orElseThrow();
        var year = entities.modelYear().orElseThrow();
        var trim = entities.trim().orElseThrow();
        Optional<VehicleEvidence> exactVehicle;
        try {
            exactVehicle = catalog.find(new VehicleLookup(model, year, trim));
            recordToolTrace(request, "get_vehicle", exactVehicle.isPresent() ? 1 : 0);
        } catch (RuntimeException failure) {
            recordToolTrace(request, "get_vehicle", null);
            exactVehicle = Optional.empty();
        }

        VehicleFact motor = exactVehicle.flatMap(vehicle -> vehicle.facts().stream()
                .filter(fact -> normalize(fact.name()).equals("motor"))
                .filter(DeterministicCatalogAnswerService::hasUsableSource)
                .findFirst()).orElse(null);
        if (motor == null && exactVehicle.isPresent()) {
            try {
                var modelVehicle = catalog.find(new VehicleLookup(model, year, null));
                recordToolTrace(request, "get_vehicle", modelVehicle.isPresent() ? 1 : 0);
                motor = modelVehicle.flatMap(vehicle -> vehicle.facts().stream()
                        .filter(fact -> normalize(fact.name()).equals("motor"))
                        .filter(DeterministicCatalogAnswerService::hasUsableSource)
                        .findFirst()).orElse(null);
            } catch (RuntimeException failure) {
                recordToolTrace(request, "get_vehicle", null);
            }
        }

        var selectedMotor = motor == null ? null : motorForTrim(motor.value(), trim);
        var source = motor == null ? Optional.<String>empty() : sourceReferencePolicy.safeReference(motor.sourceUrl());
        var answer = selectedMotor == null || source.isEmpty()
                ? "No pude confirmar el motor exacto de " + model + " " + year + " " + trim + " en el catálogo."
                : "La " + model + " " + year + " " + trim + " tiene " + selectedMotor.strip()
                        + ". Fuente: " + source.orElseThrow();
        var confirmed = selectedMotor != null && source.isPresent();
        recordTrace(request, confirmed ? "returned" : "empty", confirmed ? 1 : 0);
        return Optional.of(new AssistantResponse(answer, false, "deterministic-catalog",
                source.stream().toList(), null, null, confirmed
                        ? com.ford.service.ai.api.ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED
                        : com.ford.service.ai.api.ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED_EMPTY));
    }

    private static boolean hasUsableSource(VehicleFact fact) {
        return fact.value() != null && !fact.value().isBlank()
                && fact.sourceUrl() != null && !fact.sourceUrl().isBlank() && fact.observedAt() != null;
    }

    private static String motorForTrim(String rawValue, String trim) {
        if (rawValue == null || rawValue.isBlank()) return null;
        var alternatives = rawValue.split("\\s*\\|\\s*");
        if (alternatives.length == 1) return rawValue;
        var normalizedTrim = normalize(trim);
        var fuel = normalizedTrim.contains("gasolina") ? "gasolina"
                : normalizedTrim.contains("diesel") ? "diesel" : null;
        if (fuel == null) return null;
        var matching = java.util.Arrays.stream(alternatives)
                .filter(alternative -> normalize(alternative).contains(fuel))
                .toList();
        return matching.size() == 1 ? matching.getFirst() : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private void addVehicleSources(LinkedHashSet<String> sources, VehicleEvidence vehicle) {
        addSource(sources, vehicle.priceSourceUrl());
        addSource(sources, vehicle.modelPageUrl());
    }

    private void addPromotionSources(LinkedHashSet<String> sources, PromotionEvidence promotion) {
        addSource(sources, promotion.sourceUrl());
        addSource(sources, promotion.modelUrl());
    }

    private void addSource(LinkedHashSet<String> sources, String rawUrl) {
        sourceReferencePolicy.safeReference(rawUrl).ifPresent(sources::add);
    }

    private static void recordTrace(AssistantRequest request, String outcome, int evidenceCount) {
        if (request.traceCollector() == null) return;
        request.traceCollector().record(new DecisionTraceEvent("answer", "deterministic_catalog_lookup",
                "deterministic-catalog", "none", null, outcome, evidenceCount, Instant.now()));
    }

    private static void recordToolTrace(AssistantRequest request, String toolName, Integer evidenceCount) {
        if (request.traceCollector() == null) return;
        var outcome = evidenceCount == null ? "failed" : evidenceCount > 0 ? "returned" : "empty";
        request.traceCollector().record(new DecisionTraceEvent("tool", "deterministic_catalog_query",
                "deterministic-catalog", "none", toolName, outcome,
                evidenceCount == null ? 0 : evidenceCount, Instant.now()));
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("catalog response could not be rendered", exception);
        }
    }

}
