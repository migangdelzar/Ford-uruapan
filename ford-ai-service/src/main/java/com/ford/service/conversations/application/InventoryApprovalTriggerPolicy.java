package com.ford.service.conversations.application;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic stock-intent detector. It does not infer or assert availability. */
@Component
public final class InventoryApprovalTriggerPolicy {
    private static final Pattern INVENTORY_LANGUAGE = Pattern.compile(
            "\\b(stock|inventario|existencias?|unidades disponibles|disponible|disponibilidad|"
                    + "hay unidades|tienen unidades|en piso)\\b");

    public boolean isInventoryRequest(String message) {
        if (message == null || message.isBlank()) return false;
        var normalized = Normalizer.normalize(message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return INVENTORY_LANGUAGE.matcher(normalized).find();
    }
}
