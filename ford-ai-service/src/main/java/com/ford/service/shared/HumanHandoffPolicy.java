package com.ford.service.shared;

public final class HumanHandoffPolicy {

    public static final String BOT_FALLBACK =
            "No pude generar una respuesta en este momento. Intenta de nuevo en unos segundos.";
    private static final String HUMAN_FALLBACK =
            "En este momento te comunicaré con un asesor humano.";

    private final boolean enabled;

    public HumanHandoffPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public String fallbackText() {
        return enabled ? HUMAN_FALLBACK : BOT_FALLBACK;
    }

    public boolean shouldCreateHandoff(boolean requested) {
        return enabled && requested;
    }
}
