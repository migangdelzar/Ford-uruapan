package com.ford.service.conversations.application;

import com.ford.service.leads.domain.LeadJourneyFact;
import com.ford.service.leads.domain.LeadJourneyFactKey;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeadJourneyContextFormatterTest {
    @Test
    void includesPacketFactsWithoutExposingProvenanceAndOmitsSensitiveValues() {
        var journey = new LeadJourneySnapshot(UUID.randomUUID(), UUID.randomUUID(), null, null, null, null,
                Map.of(LeadJourneyFactKey.PAYMENT_METHOD, new LeadJourneyFact("financiamiento", "provider-1", Instant.EPOCH),
                        LeadJourneyFactKey.DOWN_PAYMENT, new LeadJourneyFact("20 mil pesos", "provider-2", Instant.EPOCH),
                        LeadJourneyFactKey.CUSTOMER_NAME, new LeadJourneyFact("María Elena", "provider-3", Instant.EPOCH),
                        LeadJourneyFactKey.BUDGET_RANGE, new LeadJourneyFact("1234567890", "provider-4", Instant.EPOCH)),
                null, 0, null, 0, Instant.EPOCH);

        var formatted = new LeadJourneyContextFormatter().format(journey);

        assertThat(formatted).contains("PAYMENT_METHOD: financiamiento", "DOWN_PAYMENT: 20 mil pesos",
                "CUSTOMER_NAME: María Elena");
        assertThat(formatted).doesNotContain("provider-", "1234567890");
    }
}
