package com.ford.service.leads.domain;

import java.time.Instant;

/** A confirmed conversational fact. The source quote is intentionally not persisted. */
public record LeadJourneyFact(String value, String sourceMessageId, Instant updatedAt) {
}
