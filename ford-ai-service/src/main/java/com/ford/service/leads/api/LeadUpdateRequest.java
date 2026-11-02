package com.ford.service.leads.api;

public record LeadUpdateRequest(String name, String email, String preferredVehicle,
                                String budgetRange, String phone, String temperature,
                                String temperatureSource) {
}
