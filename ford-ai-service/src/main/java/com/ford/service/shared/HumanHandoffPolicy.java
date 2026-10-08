package com.ford.service.shared;

public final class HumanHandoffPolicy {

    public static final String BOT_FALLBACK =
            "No pude generar una respuesta en este momento. Intenta de nuevo en unos segundos.";
    private final boolean enabled;

    public HumanHandoffPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public String fallbackText() {
        return BOT_FALLBACK;
    }

    public boolean shouldCreateHandoff(boolean requested) {
        return enabled && requested;
    }
}
