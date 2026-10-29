package com.ford.service.followups.application;

import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.followups.domain.FollowUpConsent;

import java.text.Normalizer;
import java.util.Locale;

public final class WarmFollowUpPolicy {

    public boolean isOffer(LeadTemperature temperature, String assistantText) {
        if (temperature != LeadTemperature.WARM || assistantText == null) return false;
        var text = normalize(assistantText);
        var advisor = text.contains("asesor") || text.contains("asesora");
        var contact = text.contains("contact") || text.contains("llam") || text.contains("comuniqu")
                || text.contains("seguimiento");
        return advisor && contact && (text.contains("puedo") || text.contains("quieres")
                || text.contains("te gustaria") || text.contains("si quieres"));
    }

    public FollowUpConsent consent(String message) {
        var text = normalize(message).replaceAll("[^a-z0-9 ]", " ").strip().replaceAll("\\s+", " ");
        if (text.matches("^(no|no gracias|ahorita no|por ahora no|mejor no|prefiero que no|no me contactes|no me contacten|no me llames|no me llame|no me llamen)( .*)?$")) {
            return FollowUpConsent.DECLINE;
        }
        if (text.matches(".*\\b(no quiero|no me contact|no me llam|no por ahora|no ahora)\\b.*")) {
            return FollowUpConsent.NONE;
        }
        if (text.matches("^(si|claro|de acuerdo|adelante|por favor|esta bien|va)( .*)?$")) {
            return FollowUpConsent.ACCEPT;
        }
        return FollowUpConsent.NONE;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
