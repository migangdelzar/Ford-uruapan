package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import com.ford.service.conversations.domain.AdvisorResponseDisposition.Reason;
import com.ford.service.conversations.domain.AdvisorResponseDisposition.Route;

import java.util.Objects;
import java.util.Set;

/** Pure routing policy. Callers derive signals and consecutive substantive failures from trusted backend state. */
public final class AdvisorResponseRoutingPolicy {
    private static final int FAILURE_HANDOFF_THRESHOLD = 2;

    public AdvisorResponseDisposition decide(Signals input) {
        Objects.requireNonNull(input, "routing signals are required");
        for (var signal : Signal.values()) {
            if (signal.reason.requiredRoute() == Route.MANDATORY_HANDOFF && input.signals().contains(signal)) {
                return disposition(signal.reason);
            }
        }
        if (input.consecutiveFailures() >= FAILURE_HANDOFF_THRESHOLD) return disposition(Reason.REPEATED_FAILURE);
        for (var signal : Signal.values()) {
            if (input.signals().contains(signal)) return disposition(signal.reason);
        }
        return disposition(Reason.VERIFIED_ROUTINE);
    }

    private static AdvisorResponseDisposition disposition(Reason reason) {
        return new AdvisorResponseDisposition(reason.requiredRoute(), reason);
    }

    public record Signals(Set<Signal> signals, int consecutiveFailures) {
        public Signals {
            signals = Set.copyOf(Objects.requireNonNull(signals, "signal set is required"));
            if (consecutiveFailures < 0) throw new IllegalArgumentException("failure count cannot be negative");
        }
    }

    public enum Signal {
        HUMAN_REQUEST(Reason.HUMAN_REQUEST), SERIOUS_COMPLAINT(Reason.SERIOUS_COMPLAINT),
        SENSITIVE_DOCUMENT(Reason.SENSITIVE_DOCUMENT), INVENTORY_PROMISE(Reason.INVENTORY_PROMISE),
        DISCOUNT_COMMITMENT(Reason.DISCOUNT_COMMITMENT), FINANCE_COMMITMENT(Reason.FINANCE_COMMITMENT),
        RESPONSE_REVIEW(Reason.RESPONSE_REVIEW), MODEL_REQUESTED_REVIEW(Reason.MODEL_REQUESTED_REVIEW);

        private final Reason reason;
        Signal(Reason reason) { this.reason = reason; }
    }
}
