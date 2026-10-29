package com.ford.service.catalog.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.catalog.application.ModelPopularity;
import com.ford.service.catalog.application.ModelPopularity.SourceState;
import com.ford.service.catalog.application.ModelPopularityIndex;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Bundled sidecar only: no database, network, catalog imports, or implicit fuzzy aliases. */
@org.springframework.modulith.NamedInterface
public final class ClasspathModelPopularityIndex implements ModelPopularityIndex {
    private static final String DATA = "catalog/inegi-ford-model-popularity.json";
    private final Map<String, ModelPopularity> aliases;
    private final List<ModelPopularity> cumulative;
    private final List<ModelPopularity> ytd2026;

    public ClasspathModelPopularityIndex() {
        this(new ClassPathResource(DATA), new ObjectMapper());
    }

    /** Validates eagerly so a future startup bean fails closed on an invalid reviewed dataset. */
    public ClasspathModelPopularityIndex(Resource resource, ObjectMapper mapper) {
        final JsonNode root;
        try (var input = resource.getInputStream()) {
            root = mapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot load INEGI popularity resource: " + resource, exception);
        }
        if (root == null || !root.isObject() || !"2026-10-28".equals(text(root, "version"))) {
            throw new IllegalArgumentException("Unsupported INEGI popularity dataset version");
        }
        var rows = root.path("models");
        if (!rows.isArray() || rows.size() != 20) {
            throw new IllegalArgumentException("INEGI popularity dataset must contain exactly 20 model families");
        }
        var models = new ArrayList<ModelPopularity>();
        var names = new HashSet<String>();
        var byAlias = new HashMap<String, ModelPopularity>();
        for (var row : rows) {
            var model = readModel(root, row);
            if (!names.add(normalize(model.modelFamily()))) {
                throw new IllegalArgumentException("Duplicate model family: " + model.modelFamily());
            }
            if (!model.catalogModelAliases().contains(model.modelFamily())) {
                throw new IllegalArgumentException("Canonical family must be an explicit alias: " + model.modelFamily());
            }
            for (var alias : model.catalogModelAliases()) {
                var key = normalize(alias);
                if (key.isEmpty() || byAlias.putIfAbsent(key, model) != null) {
                    throw new IllegalArgumentException("Duplicate/empty normalized catalog alias: " + alias);
                }
            }
            models.add(model);
        }
        // BigInteger avoids overflow allowing corrupted huge unit values to pass the total check.
        var total = models.stream().map(m -> java.math.BigInteger.valueOf(m.cumulativeUnits()))
                .reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add);
        if (!total.equals(java.math.BigInteger.valueOf(144980))) {
            throw new IllegalArgumentException("INEGI cumulative total must be 144980, was " + total);
        }
        aliases = Map.copyOf(byAlias);
        cumulative = models.stream().sorted(Comparator.comparingLong(ModelPopularity::cumulativeUnits).reversed()
                .thenComparing(ModelPopularity::modelFamily)).toList();
        // Nulls sort last. A missing marker is never converted into a factual zero-valued metric.
        ytd2026 = models.stream().sorted(Comparator.comparing(ModelPopularity::ytd2026Units,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ModelPopularity::modelFamily)).toList();
    }

    @Override
    public Optional<ModelPopularity> findForCatalogModel(String catalogModel) {
        return catalogModel == null ? Optional.empty() : Optional.ofNullable(aliases.get(normalize(catalogModel)));
    }

    @Override
    public List<ModelPopularity> cumulativeRankedModels() {
        return cumulative;
    }

    @Override
    public List<ModelPopularity> ytd2026RankedModels() {
        return ytd2026;
    }

    private static ModelPopularity readModel(JsonNode root, JsonNode row) {
        var aliasNodes = row.path("catalogModelAliases");
        if (!aliasNodes.isArray()) {
            throw new IllegalArgumentException("catalogModelAliases must be an array");
        }
        var catalogAliases = new ArrayList<String>();
        for (var alias : aliasNodes) {
            if (!alias.isTextual()) {
                throw new IllegalArgumentException("Catalog aliases must be text");
            }
            catalogAliases.add(alias.textValue());
        }
        var share = row.path("cumulativeSharePercent");
        if (!share.isNumber()) {
            throw new IllegalArgumentException("cumulativeSharePercent must be numeric");
        }
        return new ModelPopularity(text(row, "modelFamily"), catalogAliases,
                units(row, "cumulativeUnits", false), share.decimalValue(), state(row, "cumulativeStatus"),
                units(row, "ytd2025Units", true), state(row, "ytd2025Status"),
                units(row, "ytd2026Units", true), state(row, "ytd2026Status"),
                text(row, "origin"), text(root, "geography"), date(root, "periodStart"), date(root, "periodEnd"),
                date(root, "ytd2025Start"), date(root, "ytd2025End"),
                date(root, "ytd2026Start"), date(root, "ytd2026End"),
                text(root, "sourceLabel"), text(root, "sourceUrl"), date(root, "retrievalDate"));
    }

    private static String normalize(String name) {
        // Only case, spacing and hyphen variants: never strip arbitrary punctuation or trim names.
        return name.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}\\p{Pd}]", "");
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static LocalDate date(JsonNode node, String field) {
        try {
            return LocalDate.parse(text(node, field));
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid date: " + field, exception);
        }
    }

    private static SourceState state(JsonNode node, String field) {
        return SourceState.valueOf(text(node, field));
    }

    private static Long units(JsonNode node, String field, boolean nullable) {
        var value = node.path(field);
        if (nullable && value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative integral units or explicit null");
        }
        return value.longValue();
    }
}
