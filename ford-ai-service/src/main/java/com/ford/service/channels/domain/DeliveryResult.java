package com.ford.service.channels.domain;

public record DeliveryResult(boolean accepted, String providerMessageId, String error) {
}
