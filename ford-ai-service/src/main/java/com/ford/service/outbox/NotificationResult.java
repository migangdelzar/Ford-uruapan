package com.ford.service.outbox;

public record NotificationResult(boolean accepted, String providerMessageId, String error, boolean permanent) {
    public NotificationResult(boolean accepted, String providerMessageId, String error) {
        this(accepted, providerMessageId, error, false);
    }

    public static NotificationResult delivered(String providerMessageId) {
        return new NotificationResult(true, providerMessageId, null);
    }

    public static NotificationResult rejected(String error) {
        return new NotificationResult(false, null, error, false);
    }

    public static NotificationResult permanentFailure(String error) {
        return new NotificationResult(false, null, error, true);
    }
}
