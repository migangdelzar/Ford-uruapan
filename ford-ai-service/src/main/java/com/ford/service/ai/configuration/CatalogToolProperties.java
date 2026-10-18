package com.ford.service.ai.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "ford.catalog.tools")
public class CatalogToolProperties {

    private static final Set<String> DEFAULT_READ_ONLY_TOOLS = Set.of(
            "search_vehicles", "search_vehicle_and_offers", "get_vehicle", "compare_vehicles", "search_offers",
            "list_promotions", "get_vehicle_provenance", "get_catalog_status", "get_page_links",
            "compare_trims", "compare_model_years", "get_variant_comparisons");

    private boolean enabled;
    private Set<String> readOnlyToolNames = DEFAULT_READ_ONLY_TOOLS;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<String> readOnlyToolNames() { return Set.copyOf(readOnlyToolNames); }

    public void setReadOnlyToolNames(Set<String> names) {
        this.readOnlyToolNames = names == null ? Set.of() : names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
