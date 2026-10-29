package com.ford.service.shared;

import java.util.UUID;
import java.util.function.Function;

public final class HumanHandoffPolicy {

    public static final String BOT_FALLBACK =
            "No pude generar una respuesta en este momento. Intente de nuevo en unos segundos.";
    private final boolean enabled;
    private final Function<UUID, Boolean> runtimeEnabled;

    public HumanHandoffPolicy(boolean enabled) {
        this.enabled = enabled;
        this.runtimeEnabled = ignored -> enabled;
    }

    public HumanHandoffPolicy(boolean enabled, Function<UUID, Boolean> runtimeEnabled) {
        this.enabled = enabled;
        this.runtimeEnabled = runtimeEnabled == null ? ignored -> enabled : runtimeEnabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean enabled(UUID tenantId) {
        try {
            return Boolean.TRUE.equals(runtimeEnabled.apply(tenantId));
        } catch (RuntimeException exception) {
            return enabled;
        }
    }

    public String fallbackText() {
        return BOT_FALLBACK;
    }

    public boolean shouldCreateHandoff(boolean requested) {
        return enabled && requested;
    }

    public boolean shouldCreateHandoff(UUID tenantId, boolean requested) {
        return enabled(tenantId) && requested;
    }
}
