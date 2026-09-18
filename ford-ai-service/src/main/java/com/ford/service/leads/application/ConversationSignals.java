package com.ford.service.leads.application;

public record ConversationSignals(
        boolean financingIntent,
        boolean inventoryIntent,
        boolean explicitHumanRequest,
        boolean purchaseTimeline,
        boolean priceOrOfferIntent) {
}
