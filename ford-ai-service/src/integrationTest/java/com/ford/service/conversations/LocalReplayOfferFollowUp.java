package com.ford.service.conversations;

import com.ford.service.ai.application.ToolFamily;

import java.text.Normalizer;
import java.util.Locale;

final class LocalReplayOfferFollowUp {
    private LocalReplayOfferFollowUp() { }

    static boolean requiresPromotionEvidence(String message, ToolFamily family) {
        if (family == ToolFamily.OFFERS) return true;
        if (message == null) return false;
        var normalized = Normalizer.normalize(message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return normalized.matches("(?s).*\\b(?:esa promocion|ese descuento|ese beneficio|"
                + "la promocion)\\b.*");
    }
}
