package com.ford.service.conversations;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Identifies narrowly phrased catalog fact questions missed by deterministic intent routing in replay. */
final class LocalReplayCatalogFactFollowUp {
    private static final Pattern QUESTION = Pattern.compile(
            "(?s).*\\b(?:que|cual|cuanto|como|donde|dice|indica|aparece|incluye|tiene|cuesta|muestra|sale)\\b"
                    + ".*\\b(?:dato|fuente|origen|precio|motor|ficha|seguridad|gasolina|hibrida|consumo|potencia|"
                    + "dimensiones|traccion|equipamiento)\\b.*");

    private LocalReplayCatalogFactFollowUp() { }

    static boolean matches(String message) {
        if (message == null) return false;
        var normalized = Normalizer.normalize(message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return QUESTION.matcher(normalized).matches();
    }
}
