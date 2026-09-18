package com.ford.service.ai.adapter.in.tool;

import com.ford.service.catalog.application.VehicleCatalog;

import java.util.Objects;

public final class VehicleTools {

    private final VehicleCatalog catalog;

    public VehicleTools(VehicleCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    public VehicleCatalog catalog() {
        return catalog;
    }
}
