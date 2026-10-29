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
    void rendersOnlyApprovedNonContactFactsAndPendingQuestion() {
        var journey = new LeadJourneySnapshot(UUID.randomUUID(), UUID.randomUUID(), null, null, null, null,
                Map.of(LeadJourneyFactKey.PAYMENT_METHOD, new LeadJourneyFact("financiamiento", "provider-1", Instant.EPOCH),
                        LeadJourneyFactKey.DOWN_PAYMENT, new LeadJourneyFact("20 mil pesos", "provider-2", Instant.EPOCH),
                        LeadJourneyFactKey.CUSTOMER_NAME, new LeadJourneyFact("María Elena", "provider-3", Instant.EPOCH),
                        LeadJourneyFactKey.BUDGET_RANGE, new LeadJourneyFact("hasta 500 mil pesos", "provider-4", Instant.EPOCH),
                        LeadJourneyFactKey.VEHICLE_TRIM, new LeadJourneyFact("XLT", "provider-5", Instant.EPOCH),
                         LeadJourneyFactKey.APPOINTMENT_INTEREST, new LeadJourneyFact("sí", "provider-6", Instant.EPOCH),
                         LeadJourneyFactKey.FINANCE_INTEREST, new LeadJourneyFact("interés en financiamiento", "provider-8", Instant.EPOCH),
                         LeadJourneyFactKey.VEHICLE_USE, new LeadJourneyFact("555-123-4567", "provider-7", Instant.EPOCH)),
                "vehicle_trim", 0, null, 0, Instant.EPOCH);

        var formatted = new LeadJourneyContextFormatter().format(journey);

        assertThat(formatted).contains("BUDGET_RANGE: hasta 500 mil pesos", "VEHICLE_TRIM: XLT",
                "APPOINTMENT_INTEREST: sí", "Pregunta pendiente (no repetir si ya fue respondida): vehicle_trim");
        assertThat(formatted).doesNotContain("PAYMENT_METHOD", "DOWN_PAYMENT", "CUSTOMER_NAME", "financiamiento",
                "FINANCE_INTEREST", "interés en financiamiento", "20 mil pesos", "María Elena", "555-123-4567", "provider-");
    }

    @Test
    void omitsUnsafePendingQuestionKey() {
        var journey = new LeadJourneySnapshot(UUID.randomUUID(), UUID.randomUUID(), null, null, null, null,
                Map.of(), "555-123-4567", 0, null, 0, Instant.EPOCH);

        var formatted = new LeadJourneyContextFormatter().format(journey);

        assertThat(formatted).doesNotContain("Pregunta pendiente", "555-123-4567");
    }

    @Test
    void omitsPendingPromptsAboutWeeklyAndBiweeklyPayments() {
        for (var pendingKey : Map.of("weekly", "cuota semanal", "biweekly", "cuota quincenal").values()) {
            var journey = new LeadJourneySnapshot(UUID.randomUUID(), UUID.randomUUID(), null, null, null, null,
                    Map.of(), pendingKey, 0, null, 0, Instant.EPOCH);

            var formatted = new LeadJourneyContextFormatter().format(journey);

            assertThat(formatted).doesNotContain(pendingKey, "Pregunta pendiente");
        }
    }
}
