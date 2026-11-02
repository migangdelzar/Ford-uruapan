package com.ford.service.leads.domain;

public record LeadProfile(
        String name,
        String phone,
        String email,
        String preferredVehicle,
        String budgetRange,
        String downPaymentRange,
        String employmentRange,
        boolean tradeInInterest,
        boolean consentGiven) {
}
