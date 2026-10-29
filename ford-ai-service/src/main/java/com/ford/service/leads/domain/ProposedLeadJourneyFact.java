package com.ford.service.leads.domain;

/** Model-proposed fact with transient evidence, validated before persistence. */
public record ProposedLeadJourneyFact(LeadJourneyFactKey key, String value, String exactUserEvidence) {
}
