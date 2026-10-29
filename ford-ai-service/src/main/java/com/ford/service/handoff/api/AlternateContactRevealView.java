package com.ford.service.handoff.api;

import com.ford.service.handoffcontact.api.AlternateContactValue;

import java.util.UUID;

public record AlternateContactRevealView(UUID handoffId, AlternateContactValue.ContactType type, String value) {
    public static AlternateContactRevealView from(UUID handoffId, AlternateContactValue contact) {
        return new AlternateContactRevealView(handoffId, contact.type(), contact.normalizedValue());
    }

    @Override
    public String toString() {
        return "AlternateContactRevealView[handoffId=" + handoffId + ", type=" + type + ", value=redacted]";
    }
}
