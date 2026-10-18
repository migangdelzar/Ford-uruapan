package com.ford.service.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
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
    private final ObjectMapper objectMapper;
    private final Map<String, String> templates;

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
        var normalized = normalize(userMessage);
        boolean priceRequested = PRICE_QUERY.matcher(normalized).find();
        boolean promotionRequested = PROMOTION_QUERY.matcher(normalized).find();
        if (COMPARISON_QUERY.matcher(normalized).find()) return Optional.empty();
        if (!priceRequested && !promotionRequested) return Optional.empty();

        var payloads = toolPayloads == null ? Map.<String, String>of() : toolPayloads;
        var price = priceRequested ? vehiclePrice(payloads) : Optional.<String>empty();
        var promotion = promotionRequested ? promotion(payloads) : Optional.<String>empty();

        if (priceRequested && promotionRequested) {
            var priceSection = price.orElse(unconfirmedPrice("el modelo consultado", ""));
            var promotionSection = promotion.orElse(unconfirmedPromotion("el modelo consultado", ""));
            return Optional.of(fill(templates.get("combined"), Map.of(
                    "price_section", priceSection,
                    "promotion_section", promotionSection)));
        }
        if (priceRequested) return Optional.of(price.orElse(unconfirmedPrice("el modelo consultado", "")));
        return Optional.of(promotion.orElse(unconfirmedPromotion("el modelo consultado", "")));
    }

    private Optional<String> vehiclePrice(Map<String, String> payloads) {
        var vehicles = new ArrayList<JsonNode>();
        addVehicleResults(vehicles, payloads.get("get_vehicle"));
        addVehicleResults(vehicles, payloads.get("search_vehicles"));
        for (var vehicle : vehicles) {
            var model = text(vehicle, "model", "modelName", "model_name");
            var year = text(vehicle, "year", "modelYear", "model_year");
            var price = text(vehicle, "priceDisplay", "price_display");
            if (price.isBlank()) price = money(vehicle.get("price"), text(vehicle, "currency"));
            if (price.isBlank()) continue;
            var vehicleLabel = safeText((model.isBlank() ? "Vehículo Ford" : model)
                    + (year.isBlank() ? "" : " " + year));
            var priceUrl = safeFordUrl(text(vehicle, "priceSourceUrl", "price_source_url"));
            var modelUrl = safeFordUrl(text(vehicle, "modelPageUrl", "model_page_url", "sourceUrl", "source_url"));
            var links = links("Ver precio", priceUrl, "Ver modelo", modelUrl);
            return Optional.of(fill(templates.get("price"), Map.of(
                    "vehicle", vehicleLabel,
                    "price", safeText(price),
                    "price_link", links)));
        }
        return Optional.empty();
    }

    private Optional<String> promotion(Map<String, String> payloads) {
        var result = parsePayload(payloads.get("list_promotions"));
        if (result.isEmpty()) return Optional.empty();
        var root = result.get();
        var offers = root.isArray() ? root : root.path("results");
        if (!offers.isArray() || offers.isEmpty()) return Optional.empty();

        var blocks = new ArrayList<String>();
        for (var offer : offers) {
            var model = text(offer, "model", "modelName", "model_name");
            var year = text(offer, "year", "modelYear", "model_year");
            var vehicle = safeText((model.isBlank() ? "Ford" : model)
                    + (year.isBlank() ? "" : " " + year));
            var title = text(offer, "title");
            var benefits = benefits(offer);
            var validity = validity(offer);
            var conditions = safeText(text(offer, "conditions", "termsAndConditions", "terms_and_conditions"));
            var sourceUrl = safeFordUrl(text(offer, "sourceUrl", "source_url"));
            var modelUrl = safeFordUrl(text(offer, "modelPageUrl", "modelUrl", "model_page_url", "model_url"));
            blocks.add(fill(templates.get("promotion"), Map.of(
                    "vehicle", vehicle,
                    "title", safeText(title.isBlank() ? "Promoción publicada" : title),
                    "benefits", benefits,
                    "validity", validity,
                    "conditions", conditions.isBlank() ? "" : "Condiciones: " + limit(conditions, 500),
                    "promotion_links", links("Ver promoción", sourceUrl, "Ver modelo", modelUrl))));
            if (blocks.size() == 3) break;
        }
        return Optional.of(String.join("\n\n", blocks));
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
        for (var entry : toolPayloads.entrySet()) {
            parsePayload(entry.getValue()).ifPresent(root -> collectLinks(root, entry.getKey(), links));
        }
        var seenUrls = new LinkedHashSet<String>();
        var usable = links.stream()
                .filter(link -> seenUrls.add(link.url()))
                .filter(link -> !answer.contains(link.url()))
                .limit(8).toList();
        if (usable.isEmpty()) return answer;
        var block = usable.stream().map(link -> "🔗 " + link.label() + ": " + link.url())
                .collect(java.util.stream.Collectors.joining("\n"));
        return answer.strip() + "\n\n" + fill(templates.get("source-links"), Map.of("source_links", block));
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

    private static void collectLinks(JsonNode node, String toolName, Set<SourceLink> target) {
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

    private static void addSource(Set<SourceLink> target, String label, String rawUrl) {
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
        return discount.isBlank() ? "*Beneficio:* La fuente no detalló el beneficio."
                : "*Beneficio publicado:* " + discount;
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
            var format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX"));
            format.setCurrency(Currency.getInstance(currencyCode.isBlank() ? "MXN" : currencyCode));
            format.setMaximumFractionDigits(0);
            format.setMinimumFractionDigits(0);
            return format.format(amount);
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

    private static String safeFordUrl(String value) {
        try {
            var uri = URI.create(value);
            var host = uri.getHost();
            if ("https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (isHostOrSubdomain(host, "forduruapan.mx") || isHostOrSubdomain(host, "ford.mx"))) {
                return new URI("https", null, host, -1, uri.getPath(), null, null).toASCIIString();
            }
        } catch (IllegalArgumentException | java.net.URISyntaxException ignored) {
            // Missing or malformed source URLs are omitted from the answer.
        }
        return "";
    }

    private static boolean isHostOrSubdomain(String host, String domain) {
        return host.equalsIgnoreCase(domain) || host.toLowerCase(Locale.ROOT).endsWith("." + domain);
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
