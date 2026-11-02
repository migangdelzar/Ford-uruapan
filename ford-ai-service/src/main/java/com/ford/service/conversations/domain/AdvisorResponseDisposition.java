package com.ford.service.conversations.domain;

import java.util.Objects;

/** Server-owned routing outcome; a model suggestion cannot authorize a send or handback. */
public record AdvisorResponseDisposition(Route route, Reason reason) {
    public AdvisorResponseDisposition {
        Objects.requireNonNull(route, "response route is required");
        Objects.requireNonNull(reason, "response reason is required");
        if (reason.requiredRoute() != route) throw new IllegalArgumentException("response route contradicts its reason");
    }

    public enum Route { AUTOMATIC, REVIEW, MANDATORY_HANDOFF }

    public enum Reason {
        VERIFIED_ROUTINE,
        HUMAN_REQUEST, SERIOUS_COMPLAINT, SENSITIVE_DOCUMENT, REPEATED_FAILURE,
        INVENTORY_PROMISE, DISCOUNT_COMMITMENT, FINANCE_COMMITMENT, RESPONSE_REVIEW, MODEL_REQUESTED_REVIEW;

        public Route requiredRoute() {
            return switch (this) {
                case VERIFIED_ROUTINE -> Route.AUTOMATIC;
                case HUMAN_REQUEST, SERIOUS_COMPLAINT, SENSITIVE_DOCUMENT, REPEATED_FAILURE -> Route.MANDATORY_HANDOFF;
                default -> Route.REVIEW;
            };
        }
    }
}
