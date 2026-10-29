package com.ford.service.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders price and dealer-promotion replies from catalog tool results without LLM-generated facts. */
@Component
public class CatalogFactResponseRenderer {

    private static final String TEMPLATE_ROOT = "classpath:/response-templates/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z_]+)}}");
    private static final Pattern PRICE_QUERY = Pattern.compile(
            "\\b(precio|cuesta|cuánto cuesta|cuanto cuesta|costaría|costaria|price|how much)\\b");
    private static final Pattern PROMOTION_QUERY = Pattern.compile(
            "\\b(promoción|promocion|promociones|oferta|ofertas|bono|bonos|descuento|descuentos|promotion|offer|discount)\\b");
    private static final Pattern COMPARISON_QUERY = Pattern.compile(
            "\\b(compar\\w*|diferencia\\w*|versus|vs\\.?|frente a)\\b");
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s)\\]]+");
    private final ObjectMapper objectMapper;
    private final Map<String, String> templates;
    private final FordCatalogSourceReferencePolicy sourceReferencePolicy = new FordCatalogSourceReferencePolicy();

    public CatalogFactResponseRenderer(ResourceLoader resources, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.templates = Map.of(
                "price", read(resources, "vehicle-price.md"),
                "promotion", read(resources, "promotion.md"),
                "combined", read(resources, "price-and-promotion.md"),
                "price-unconfirmed", read(resources, "price-unconfirmed.md"),
                "promotion-unconfirmed", read(resources, "promotion-unconfirmed.md"),
                "source-links", read(resources, "source-links.md"));
    }

    public Optional<String> render(String userMessage, Map<String, String> toolPayloads) {
        return render(userMessage, toolPayloads, Set.of());
    }

    public Optional<String> render(String userMessage, Map<String, String> toolPayloads,
                                   Set<String> failedToolNames) {
        var normalized = normalize(userMessage);
        boolean priceRequested = PRICE_QUERY.matcher(normalized).find();
        boolean promotionRequested = PROMOTION_QUERY.matcher(normalized).find();
        if (COMPARISON_QUERY.matcher(normalized).find()) return Optional.empty();
        if (!priceRequested && !promotionRequested) return Optional.empty();

        var payloads = toolPayloads == null ? Map.<String, String>of() : toolPayloads;
        var failures = failedToolNames == null ? Set.<String>of() : failedToolNames;
        var price = priceRequested ? vehiclePrice(userMessage, payloads) : Optional.<String>empty();
        var promotion = promotionRequested
                ? promotion(userMessage, payloads, null, false, 3) : Optional.<String>empty();
        var priceFailed = priceRequested && price.isEmpty() && failures.stream().anyMatch(
                Set.of("get_vehicle", "search_vehicles", "search_vehicle_and_offers")::contains);
        var promotionFailed = promotionRequested && promotion.isEmpty() && failures.contains("list_promotions");

        if (priceRequested && promotionRequested) {
            var sections = new ArrayList<String>();
            if (!promotionFailed) sections.add(promotion.orElse(unconfirmedPromotion("el modelo consultado", "")));
            if (!priceFailed) sections.add(price.orElse(unconfirmedPrice("el modelo consultado", "")));
            if (sections.isEmpty()) return Optional.empty();
            if (sections.size() == 1) return Optional.of(sections.getFirst());
            return Optional.of(deduplicateSourceLinks(fill(templates.get("combined"), Map.of(
                    "promotion_section", sections.get(0),
                    "price_section", sections.get(1)))));
        }
        if (priceRequested) {
            if (priceFailed) return Optional.empty();
            return Optional.of(price.orElse(unconfirmedPrice("el modelo consultado", "")));
        }
        if (promotionFailed) return Optional.empty();
        return Optional.of(promotion.orElse(unconfirmedPromotion("el modelo consultado", "")));
    }

    /** Renders a sales price reply with any verified current offer before the base price. */
    public Optional<String> renderPriceWithOffers(String userMessage, Map<String, String> toolPayloads,
                                                  Set<String> failedToolNames, LocalDate asOf) {
        if (COMPARISON_QUERY.matcher(normalize(userMessage)).find()) return Optional.empty();
        var payloads = toolPayloads == null ? Map.<String, String>of() : toolPayloads;
        var failures = failedToolNames == null ? Set.<String>of() : failedToolNames;
        var price = vehiclePrice(userMessage, payloads);
        var promotion = promotion(userMessage, payloads, asOf, true, 1);
        var priceFailed = price.isEmpty() && failures.stream().anyMatch(
                Set.of("get_vehicle", "search_vehicles", "search_vehicle_and_offers")::contains);
        var promotionFailed = promotion.isEmpty() && failures.contains("list_promotions");
        if (priceFailed && promotionFailed) {
            return Optional.of("No pude confirmar la oferta ni el precio publicado de "
                    + requestedVehicle(userMessage) + " en este momento.");
        }
        var sections = new ArrayList<String>();
        if (promotion.isPresent() && !promotion.get().isBlank()) {
            sections.add(promotion.get());
        } else if (PROMOTION_QUERY.matcher(normalize(userMessage)).find() && !promotionFailed) {
            sections.add(unconfirmedPromotion(requestedVehicle(userMessage), ""));
        }
        if (price.isPresent()) {
            sections.add(price.get());
        } else if (priceFailed) {
            sections.add("No pude confirmar el precio publicado de " + requestedVehicle(userMessage)
                    + " en este momento.");
        } else {
            sections.add(unconfirmedPrice(requestedVehicle(userMessage), ""));
        }
        if (sections.size() == 1) return Optional.of(sections.getFirst());
        if (sections.size() == 2) {
            return Optional.of(deduplicateSourceLinks(fill(templates.get("combined"), Map.of(
                    "promotion_section", sections.get(0),
                    "price_section", sections.get(1)))));
        }
        return Optional.of(sections.getFirst());
    }

    private Optional<String> vehiclePrice(String userMessage, Map<String, String> payloads) {
        var vehicles = new ArrayList<JsonNode>();
        addVehicleResults(vehicles, payloads.get("get_vehicle"));
        addVehicleResults(vehicles, payloads.get("search_vehicles"));
        var requested = new VehicleEntityResolver().resolve(userMessage, "");
        if (requested.status() == VehicleEntityResolution.Status.AMBIGUOUS
                || requested.comparisonYears().size() > 1) return Optional.empty();
        var messageWithoutModel = normalize(userMessage);
        if (requested.model().isPresent()) {
            messageWithoutModel = messageWithoutModel.replace(normalize(requested.model().get()), " ");
        }
        var trimConstraints = new LinkedHashSet<String>();
        requested.trim().ifPresent(trimConstraints::add);
        // Also recognize exact evidence tokens (including XL, not XLT) and fuel/drive
        // qualifiers even when the requested variant is absent from the evidence.
        var trimWords = new LinkedHashSet<>(List.of("XL", "Diesel", "Diésel", "Gasolina", "4x2", "4x4", "AWD", "FWD", "RWD"));
        for (var vehicle : vehicles) {
            for (var word : text(vehicle, "trim", "trimName", "trim_name").split("\\s+")) {
                if (!word.isBlank()) trimWords.add(word);
            }
        }
        for (var word : trimWords) {
            if (containsEntity(messageWithoutModel, word)) trimConstraints.add(word);
        }
        var matches = vehicles.stream()
                .filter(vehicle -> requested.model().isEmpty() || normalize(text(vehicle, "model", "modelName", "model_name"))
                        .equals(normalize(requested.model().get())))
                .filter(vehicle -> requested.modelYear().isEmpty() || text(vehicle, "year", "modelYear", "model_year")
                        .equals(requested.modelYear().get().toString()))
                .filter(vehicle -> requested.trim().isEmpty()
                        || exactTrimName(text(vehicle, "trim", "trimName", "trim_name"))
                        .equals(exactTrimName(requested.trim().get())))
                .filter(vehicle -> trimConstraints.stream().allMatch(trim ->
                        containsEntity(text(vehicle, "trim", "trimName", "trim_name"), trim)))
                .filter(vehicle -> !publishedPrice(vehicle).isBlank())
                .toList();
        if (matches.isEmpty()) return Optional.empty();
        var blocks = new LinkedHashSet<String>();
        for (var vehicle : matches) {
            var model = text(vehicle, "model", "modelName", "model_name");
            var year = text(vehicle, "year", "modelYear", "model_year");
            var trim = text(vehicle, "trim", "trimName", "trim_name");
            var vehicleLabel = safeText((model.isBlank() ? "Vehículo Ford" : model)
                    + (year.isBlank() ? "" : " " + year));
            var priceUrl = safeFordUrl(text(vehicle, "priceSourceUrl", "price_source_url"));
            var modelUrl = safeFordUrl(text(vehicle, "modelPageUrl", "model_page_url", "sourceUrl", "source_url"));
            var priceLinkUrl = priceUrl.isBlank() ? modelUrl : priceUrl;
            var priceLinkLabel = priceUrl.isBlank() ? "Ver modelo" : "Ver precio";
            var links = links(priceLinkLabel, priceLinkUrl, "", "");
            blocks.add(fill(templates.get("price"), Map.of(
                    "vehicle", vehicleLabel,
                    "trim", trim.isBlank() ? "" : " " + safeText(trim),
                    "price", safeText(publishedPrice(vehicle)),
                    "price_link", links)));
        }
        if (trimConstraints.isEmpty() && matches.size() > 1) {
            var range = priceRange(matches);
            if (range.isPresent()) return range;
        }
        return Optional.of(String.join("\n\n", blocks));
    }

    private Optional<String> priceRange(List<JsonNode> vehicles) {
        var first = vehicles.getFirst();
        var model = text(first, "model", "modelName", "model_name");
        var year = text(first, "year", "modelYear", "model_year");
        JsonNode minimum = first;
        JsonNode maximum = first;
        BigDecimal lowest = null;
        BigDecimal highest = null;
        for (var vehicle : vehicles) {
            if (!text(vehicle, "model", "modelName", "model_name").equals(model)
                    || !text(vehicle, "year", "modelYear", "model_year").equals(year)
                    || !text(vehicle, "currency").equals(text(first, "currency"))) return Optional.empty();
            var matcher = Pattern.compile("(?:MXN\\s*)?\\$\\s*([0-9,]+(?:\\.[0-9]+)?)").matcher(publishedPrice(vehicle));
            if (!matcher.matches()) return Optional.empty();
            var amount = new BigDecimal(matcher.group(1).replace(",", ""));
            if (lowest == null || amount.compareTo(lowest) < 0) {
                lowest = amount;
                minimum = vehicle;
            }
            if (highest == null || amount.compareTo(highest) > 0) {
                highest = amount;
                maximum = vehicle;
            }
        }
        var sourceLinks = new LinkedHashSet<String>();
        for (var vehicle : List.of(minimum, maximum)) {
            var url = safeFordUrl(text(vehicle, "priceSourceUrl", "price_source_url"));
            if (!url.isBlank()) sourceLinks.add(links("Ver precio", url, "", ""));
        }
        return Optional.of(fill(templates.get("price"), Map.of(
                "vehicle", safeText(model + (year.isBlank() ? "" : " " + year)),
                "trim", "",
                "price", "desde " + safeText(publishedPrice(minimum)) + " hasta " + safeText(publishedPrice(maximum)),
                "price_link", String.join("\n", sourceLinks))));
    }

    private static boolean containsEntity(String text, String entity) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(entity)
                + "(?![\\p{L}\\p{N}])").matcher(text).find();
    }

    private static String exactTrimName(String trim) {
        var qualifiers = Set.of("diesel", "diésel", "gasolina", "4x2", "4x4", "awd", "fwd", "rwd");
        return java.util.Arrays.stream(normalize(trim).split("\\s+"))
                .filter(token -> !qualifiers.contains(token))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String publishedPrice(JsonNode vehicle) {
        var display = text(vehicle, "priceDisplay", "price_display");
        return display.isBlank() ? money(vehicle.get("price"), text(vehicle, "currency")) : display;
    }

    private Optional<String> promotion(String userMessage, Map<String, String> payloads,
                                       LocalDate asOf, boolean currentOnly, int maxOffers) {
        var result = parsePayload(payloads.get("list_promotions"));
        if (result.isEmpty()) return Optional.empty();
        var root = result.get();
        var offers = root.isArray() ? root : root.path("results");
        if (!offers.isArray()) return Optional.empty();
        if (offers.isEmpty()) {
            var totalCount = root.path("totalCount");
            if (!currentOnly && (root.isArray() || totalCount.canConvertToLong() && totalCount.asLong() == 0)) {
                return Optional.of(unconfirmedPromotion(requestedVehicle(userMessage), ""));
            }
            return Optional.empty();
        }

        var blocks = new ArrayList<String>();
        var requested = new VehicleEntityResolver().resolve(userMessage, "");
        for (var offer : offers) {
            var model = text(offer, "model", "modelName", "model_name");
            var year = text(offer, "year", "modelYear", "model_year");
            if (requested.model().isPresent() && (model.isBlank()
                    || !normalize(model).equals(normalize(requested.model().get())))) continue;
            if (requested.modelYear().isPresent() && !year.equals(requested.modelYear().get().toString())) continue;
            var eligibleTrim = text(offer, "trim", "trimName", "trim_name", "eligibleTrim", "eligible_trim");
            if (currentOnly && !isCurrentOffer(offer, asOf)) continue;
            var scopeCaveats = new ArrayList<String>();
            if (requested.trim().isPresent()) {
                if (eligibleTrim.isBlank()) {
                    scopeCaveats.add("La fuente no confirma si aplica a la versión "
                            + safeText(requested.trim().orElseThrow()) + ".");
                } else if (!exactTrimName(eligibleTrim).equals(exactTrimName(requested.trim().get()))) {
                    continue;
                }
            } else if (eligibleTrim.isBlank()) {
                scopeCaveats.add("La fuente no detalla las versiones aplicables.");
            }
            if (currentOnly) scopeCaveats.add("La elegibilidad depende de las condiciones de la oferta.");
            var vehicle = safeText((model.isBlank() ? "Ford" : model)
                    + (year.isBlank() ? "" : " " + year));
            var title = text(offer, "title");
            var benefits = benefits(offer);
            var validity = validity(offer);
            var conditions = safeText(text(offer, "conditions", "termsAndConditions", "terms_and_conditions"));
            var sourceUrl = safeFordUrl(text(offer, "sourceUrl", "source_url"));
            var modelUrl = safeFordUrl(text(offer, "modelPageUrl", "modelUrl", "model_page_url", "model_url"));
            var promotionLinkUrl = sourceUrl.isBlank() ? modelUrl : sourceUrl;
            if (currentOnly && promotionLinkUrl.isBlank()) continue;
            var promotionLinkLabel = sourceUrl.isBlank() ? "Ver modelo" : "Ver promoción";
            blocks.add(fill(templates.get("promotion"), Map.of(
                    "vehicle", vehicle,
                    "title", safeText(title.isBlank() ? "Promoción publicada" : title),
                    "benefits", benefits,
                    "validity", validity,
                    "conditions", conditions.isBlank() ? "" : "Condiciones: " + conditions,
                    "promotion_links", links(promotionLinkLabel, promotionLinkUrl, "", ""),
                    "scope_caveat", String.join(" ", scopeCaveats))));
            if (blocks.size() == maxOffers) break;
        }
        return blocks.isEmpty() ? Optional.empty() : Optional.of(String.join("\n\n", blocks));
    }

    private static boolean isCurrentOffer(JsonNode offer, LocalDate asOf) {
        if (asOf == null || !"active".equalsIgnoreCase(text(offer, "validityStatus", "validity_status"))) {
            return false;
        }
        if (hasTermConflict(offer.path("termConflict")) || hasTermConflict(offer.path("term_conflict"))) {
            return false;
        }
        var from = text(offer, "validFrom", "valid_from");
        var until = text(offer, "validUntil", "valid_until");
        try {
            if (!from.isBlank() && LocalDate.parse(from).isAfter(asOf)) return false;
            if (!until.isBlank() && LocalDate.parse(until).isBefore(asOf)) return false;
        } catch (java.time.format.DateTimeParseException exception) {
            return false;
        }
        var benefits = offer.path("benefits");
        return benefits.isTextual() && !benefits.asText().isBlank()
                || benefits.isArray() && !benefits.isEmpty()
                || offer.path("discountAmount").isNumber()
                || offer.path("cashPrice").isNumber();
    }

    private static boolean hasTermConflict(JsonNode conflict) {
        if (conflict == null || conflict.isMissingNode() || conflict.isNull()) return false;
        if (conflict.isBoolean()) return conflict.asBoolean();
        if (conflict.isNumber()) return conflict.asDouble() != 0.0;
        if (conflict.isTextual()) return !conflict.asText().isBlank();
        return !conflict.isEmpty();
    }

    private static String requestedVehicle(String userMessage) {
        var requested = new VehicleEntityResolver().resolve(userMessage, "");
        var model = requested.model().map(CatalogFactResponseRenderer::safeText).orElse("el modelo consultado");
        var year = requested.modelYear().map(Object::toString).orElse("");
        var trim = requested.trim().map(CatalogFactResponseRenderer::safeText).orElse("");
        return String.join(" ", List.of(model, year, trim).stream().filter(value -> !value.isBlank()).toList());
    }

    private static String deduplicateSourceLinks(String answer) {
        var seen = new LinkedHashSet<String>();
        var lines = new ArrayList<String>();
        for (var line : answer.split("\\R", -1)) {
            if (line.startsWith("🔗 ")) {
                var link = line.substring(line.indexOf(": ") + 2).strip();
                if (!seen.add(urlDeduplicationKey(link))) continue;
            }
            lines.add(line);
        }
        return String.join("\n", lines).replaceAll("\n{3,}", "\n\n").strip();
    }

    private void addVehicleResults(List<JsonNode> target, String payload) {
        var parsed = parsePayload(payload);
        if (parsed.isEmpty()) return;
        var root = parsed.get();
        var vehicle = root.path("vehicle");
        if (vehicle.isObject()) root = vehicle;
        if (root.has("results") && root.path("results").isArray()) {
            root.path("results").forEach(target::add);
        } else if (root.isObject()) {
            target.add(root);
        }
    }

    private Optional<JsonNode> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) return Optional.empty();
        try {
            var root = objectMapper.readTree(payload);
            if (root.has("structuredContent") && root.path("structuredContent").isObject()) {
                root = root.path("structuredContent");
            }
            if (root.path("content").isArray()) {
                for (var item : root.path("content")) {
                    if (item.path("text").isTextual()) {
                        var nested = item.path("text").asText();
                        if (!nested.isBlank() && nested.stripLeading().startsWith("{")) {
                            return Optional.of(objectMapper.readTree(nested));
                        }
                    }
                }
            }
            return Optional.of(root);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private String unconfirmedPrice(String vehicle, String url) {
        return fill(templates.get("price-unconfirmed"), Map.of(
                "vehicle", safeText(vehicle),
                "price_link", links("Ver modelo", url, "Ver modelo", "")));
    }

    private String unconfirmedPromotion(String vehicle, String url) {
        return fill(templates.get("promotion-unconfirmed"), Map.of(
                "vehicle", safeText(vehicle),
                "promotion_links", links("Ver promociones", url, "Ver modelo", "")));
    }

    /** Appends source links returned by approved catalog tools; never creates or guesses URLs. */
    public String appendSourceLinks(String answer, Map<String, String> toolPayloads) {
        if (answer == null || answer.isBlank() || toolPayloads == null || toolPayloads.isEmpty()) return answer;
        var links = new LinkedHashSet<SourceLink>();
        var hasComparisonEvidence = toolPayloads.keySet().stream()
                .anyMatch(name -> name.contains("comparison") || name.contains("variant_comparison") || name.equals("compare_trims"));
        for (var entry : toolPayloads.entrySet()) {
            if (hasComparisonEvidence && !(entry.getKey().contains("comparison")
                    || entry.getKey().contains("variant_comparison") || entry.getKey().equals("compare_trims"))) continue;
            parsePayload(entry.getValue()).ifPresent(root -> collectLinks(root, entry.getKey(), links));
        }
        var seenUrls = new LinkedHashSet<String>();
        var usable = links.stream()
                .filter(link -> seenUrls.add(urlDeduplicationKey(link.url())))
                .filter(link -> !containsSourceUrl(answer, link.url()))
                .limit(2).toList();
        if (usable.isEmpty()) return answer;
        var block = usable.stream().map(link -> "🔗 " + link.label() + ": " + link.url())
                .collect(java.util.stream.Collectors.joining("\n"));
        return answer.strip() + "\n\n" + fill(templates.get("source-links"), Map.of("source_links", block));
    }

    private boolean containsSourceUrl(String text, String sourceUrl) {
        var expected = urlDeduplicationKey(sourceUrl);
        var matcher = URL_IN_TEXT.matcher(text);
        while (matcher.find()) {
            var candidate = matcher.group().replaceAll("[.,!?;:]+$", "");
            var approved = safeFordUrl(candidate);
            if (!approved.isBlank() && urlDeduplicationKey(approved).equals(expected)) return true;
        }
        return false;
    }

    private static String urlDeduplicationKey(String url) {
        return url.replaceAll("/+$", "");
    }

    /** Appends only source URLs carried by retrieved knowledge evidence. */
    public String appendKnowledgeSourceLinks(String answer, List<String> sourceUrls) {
        if (answer == null || answer.isBlank() || sourceUrls == null || sourceUrls.isEmpty()) return answer;
        var links = new LinkedHashSet<SourceLink>();
        sourceUrls.forEach(url -> addSource(links, "Fuente aprobada", url));
        var usable = links.stream().filter(link -> !answer.contains(link.url())).limit(8).toList();
        if (usable.isEmpty()) return answer;
        var block = usable.stream().map(link -> "🔗 " + link.label() + ": " + link.url())
                .collect(java.util.stream.Collectors.joining("\n"));
        return answer.strip() + "\n\n" + fill(templates.get("source-links"), Map.of("source_links", block));
    }

    private void collectLinks(JsonNode node, String toolName, Set<SourceLink> target) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(child -> collectLinks(child, toolName, target));
            return;
        }
        if (!node.isObject()) return;
        for (var field = node.fields(); field.hasNext();) {
            var entry = field.next();
            var key = entry.getKey();
            var value = entry.getValue();
            var label = switch (key) {
                case "modelPageUrl", "model_page_url", "modelUrl", "model_url" -> "Ver modelo";
                case "technicalSheetUrl", "technical_sheet_url" -> "Ficha técnica";
                case "priceSourceUrl", "price_source_url" -> "Ver precio";
                case "comparisonUrl", "comparison_url" -> "Ver comparativo";
                case "sourceUrl", "source_url" -> toolName.equals("list_promotions")
                        ? "Ver promoción" : "Ver fuente";
                default -> null;
            };
            if (label != null && value.isTextual()) addSource(target, label, value.asText());
            if ((key.equals("comparisonUrls") || key.equals("comparison_urls")) && value.isArray()) {
                value.forEach(url -> { if (url.isTextual()) addSource(target, "Ver comparativo", url.asText()); });
            }
            if (key.equals("links") && value.isArray() && toolName.equals("get_page_links")) {
                value.forEach(link -> {
                    var url = text(link, "url", "href");
                    var linkLabel = text(link, "text", "title", "anchorText");
                    if (!url.isBlank()) addSource(target,
                            linkLabel.isBlank() ? "Página relacionada" : safeText(linkLabel), url);
                });
            }
            collectLinks(value, toolName, target);
        }
    }

    private void addSource(Set<SourceLink> target, String label, String rawUrl) {
        var url = safeFordUrl(rawUrl);
        if (!url.isBlank()) target.add(new SourceLink(limit(safeText(label), 60), url));
    }

    private record SourceLink(String label, String url) { }

    private String benefits(JsonNode offer) {
        var benefits = offer.path("benefits");
        if (benefits.isArray()) {
            var values = new ArrayList<String>();
            benefits.forEach(item -> {
                var value = item.isTextual() ? item.asText() : text(item, "description", "name");
                if (!value.isBlank()) values.add(limit(safeText(value), 180));
            });
            if (values.size() == 1) return "*Beneficio:* " + values.getFirst();
            if (!values.isEmpty()) return "*Beneficios:*\n" + values.stream()
                    .map(value -> "- " + value).collect(java.util.stream.Collectors.joining("\n"));
        } else if (benefits.isTextual() && !benefits.asText().isBlank()) {
            return "*Beneficio:* " + limit(safeText(benefits.asText()), 300);
        }
        var discount = money(offer.path("discountAmount"), text(offer, "currency"));
        if (!discount.isBlank()) return "*Beneficio publicado:* " + discount;
        var cashPrice = money(offer.path("cashPrice"), text(offer, "currency"));
        return cashPrice.isBlank() ? "*Beneficio:* La fuente no detalló el beneficio."
                : "*Precio promocional publicado:* " + cashPrice;
    }

    private static String validity(JsonNode offer) {
        var from = text(offer, "validFrom", "valid_from");
        var until = text(offer, "validUntil", "valid_until");
        return switch (text(offer, "validityStatus").toLowerCase(Locale.ROOT)) {
            case "active" -> dateRange("Vigente según la fuente", from, until);
            case "upcoming" -> dateRange("Aún no inicia", from, until);
            case "expired" -> dateRange("La vigencia terminó", from, until);
            default -> from.isBlank() && until.isBlank()
                    ? "La vigencia no está confirmada."
                    : dateRange("La vigencia no está confirmada; la fuente muestra", from, until);
        };
    }

    private static String dateRange(String label, String from, String until) {
        if (from.isBlank() && until.isBlank()) return label + ".";
        if (from.isBlank()) return label + " hasta " + safeText(until) + ".";
        if (until.isBlank()) return label + " desde " + safeText(from) + ".";
        return label + " del " + safeText(from) + " al " + safeText(until) + ".";
    }

    private static String money(JsonNode value, String currencyCode) {
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) return "";
        try {
            var amount = new BigDecimal(value.asText());
            var code = currencyCode.isBlank() ? "MXN" : currencyCode.toUpperCase(Locale.ROOT);
            var format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX"));
            format.setCurrency(Currency.getInstance(code));
            format.setMaximumFractionDigits(0);
            format.setMinimumFractionDigits(0);
            return format.format(amount) + " " + code;
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 1).stripTrailing() + "…";
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) return "";
        for (var name : names) {
            var value = node.get(name);
            if (value != null && value.isValueNode() && !value.isNull()) {
                var text = value.asText().strip();
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private static String links(String firstLabel, String firstUrl, String secondLabel, String secondUrl) {
        var values = new ArrayList<String>();
        if (!firstUrl.isBlank()) values.add("🔗 " + firstLabel + ": " + firstUrl);
        if (!secondUrl.isBlank() && !secondUrl.equals(firstUrl)) {
            values.add("🔗 " + secondLabel + ": " + secondUrl);
        }
        return String.join("\n", values);
    }

    private String safeFordUrl(String value) {
        return sourceReferencePolicy.safeReference(value).orElse("");
    }

    private static String safeText(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ").replace("{", "").replace("}", "")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("[", "\\[").replace("]", "\\]")
                .replace("`", "'").strip();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
    }

    private static String fill(String template, Map<String, String> values) {
        var matcher = PLACEHOLDER.matcher(template);
        var result = new StringBuffer();
        while (matcher.find()) {
            var value = values.get(matcher.group(1));
            if (value == null) throw new IllegalArgumentException("Missing response template value: " + matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString().strip();
    }

    private static String read(ResourceLoader resources, String fileName) {
        try (var input = resources.getResource(TEMPLATE_ROOT + fileName).getInputStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load response template: " + fileName, exception);
        }
    }
}
