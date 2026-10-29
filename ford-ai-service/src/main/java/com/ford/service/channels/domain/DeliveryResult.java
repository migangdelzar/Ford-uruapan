package com.ford.service.channels.domain;

public record DeliveryResult(boolean accepted, String providerMessageId, String error, boolean permanent) {
    public DeliveryResult(boolean accepted, String providerMessageId, String error) {
        this(accepted, providerMessageId, error, false);
    }
}
