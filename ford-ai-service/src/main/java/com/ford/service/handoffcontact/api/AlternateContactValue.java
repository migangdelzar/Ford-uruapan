package com.ford.service.handoffcontact.api;

import java.time.Instant;
import java.util.Objects;

/** A normalized alternate contact with minimal, value-free consent evidence. */
public record AlternateContactValue(ContactType type, String normalizedValue, Instant consentedAt,
                                    String consentMessageId, String policyVersion) {
    public AlternateContactValue {
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(normalizedValue, "normalizedValue is required");
        Objects.requireNonNull(consentedAt, "consentedAt is required");
        if (normalizedValue.isBlank() || consentMessageId == null || consentMessageId.isBlank()
                || policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("alternate contact consent evidence is incomplete");
        }
    }

    @Override
    public String toString() {
        return "AlternateContactValue[type=" + type + ", value=redacted, consentedAt=" + consentedAt
                + ", consentMessageId=" + consentMessageId + ", policyVersion=" + policyVersion + "]";
    }

    public enum ContactType { PHONE, EMAIL }
}
