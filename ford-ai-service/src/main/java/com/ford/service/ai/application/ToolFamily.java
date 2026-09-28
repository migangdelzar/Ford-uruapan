package com.ford.service.ai.application;

public enum ToolFamily {
    NONE, VEHICLE_SEARCH, VEHICLE_DETAILS, OFFERS, FINANCE_ESTIMATE, KNOWLEDGE_SEARCH, APPOINTMENT;

    public boolean requiresVolatileInformation() {
        return switch (this) {
            case VEHICLE_SEARCH, VEHICLE_DETAILS, OFFERS, KNOWLEDGE_SEARCH -> true;
            case NONE, FINANCE_ESTIMATE, APPOINTMENT -> false;
        };
    }
}
