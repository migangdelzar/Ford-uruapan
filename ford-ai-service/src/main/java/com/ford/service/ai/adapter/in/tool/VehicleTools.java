package com.ford.service.ai.adapter.in.tool;

import com.ford.service.catalog.application.VehicleCatalog;
import com.ford.service.catalog.api.CatalogQuery;
import com.ford.service.catalog.domain.VehicleSummary;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class VehicleTools {

    private final VehicleCatalog catalog;

    public VehicleTools(VehicleCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    public VehicleCatalog catalog() {
        return catalog;
    }

    @Tool(name = "vehicle_snapshot_search", description = "Busca modelos y versiones Ford vigentes para el tenant.")
    public List<VehicleSummary> search(String text, Integer modelYear, ToolContext context) {
        return catalog.search(new CatalogQuery(tenantId(context), text, modelYear, null));
    }

    private static UUID tenantId(ToolContext context) {
        if (context == null || context.getContext().get("tenantId") == null) {
            throw new IllegalArgumentException("tenantId tool context is required");
        }
        return UUID.fromString(context.getContext().get("tenantId").toString());
    }
}
