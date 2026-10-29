package com.ford.service.leads;

import com.ford.service.leads.application.LeadJourneyPolicy;
import com.ford.service.leads.domain.LeadJourneyFact;
import com.ford.service.leads.domain.LeadJourneyFactKey;
import com.ford.service.leads.domain.LeadJourneyProposal;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.leads.domain.LeadJourneyTopic;
import com.ford.service.leads.domain.ProposedLeadJourneyFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeadJourneyPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");
    private final LeadJourneyPolicy policy = new LeadJourneyPolicy();

    @Test
    void capturesFactOnlyWhenEvidenceIsAnExactQuoteFromCurrentMessage() {
        var current = emptyJourney();
        var proposal = proposal(LeadJourneyTopic.VEHICLE_DISCOVERY,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.VEHICLE_MODEL, "Ranger", "Busco una Ranger"));

        var updated = policy.apply(current, proposal, "Busco una Ranger", false, "in-1", NOW);

        assertThat(updated.facts()).containsKey(LeadJourneyFactKey.VEHICLE_MODEL);
        assertThat(updated.facts().get(LeadJourneyFactKey.VEHICLE_MODEL).value()).isEqualTo("Ranger");
    }

    @Test
    void rejectsMissingOrAlteredEvidence() {
        var current = emptyJourney();
        var proposal = proposal(LeadJourneyTopic.VEHICLE_DISCOVERY,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.VEHICLE_MODEL, "Ranger", "Quiero una Ranger"));

        var updated = policy.apply(current, proposal, "Me interesa una Bronco", false, "in-1", NOW);

        assertThat(updated.facts()).isEmpty();
    }

    @Test
    void doesNotCaptureFactsFromConfirmedSensitiveTurn() {
        var proposal = proposal(LeadJourneyTopic.FINANCING_TRADE_IN,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.FINANCE_INTEREST, "sí", "Quiero financiar"));

        var updated = policy.apply(emptyJourney(), proposal, "Quiero financiar", true, "in-1", NOW);

        assertThat(updated.facts()).isEmpty();
        assertThat(updated.currentTopic()).isEqualTo(LeadJourneyTopic.FINANCING_TRADE_IN);
    }

    @Test
    void rejectsContactLikeDigitRunsEvenWhenQuoted() {
        var proposal = proposal(LeadJourneyTopic.VEHICLE_DISCOVERY,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.BUDGET_RANGE, "5551234567", "Mi presupuesto es 5551234567"));

        var updated = policy.apply(emptyJourney(), proposal, "Mi presupuesto es 5551234567", false, "in-1", NOW);

        assertThat(updated.facts()).isEmpty();
    }

    @Test
    void topicSwitchPausesPreviousTopicAndVisitsNewTopic() {
        var current = journey(LeadJourneyTopic.VEHICLE_DISCOVERY);
        var proposal = new LeadJourneyProposal(LeadJourneyTopic.PROMOTIONS_PRICING, List.of(), null);

        var updated = policy.apply(current, proposal, "¿Qué promociones tienen?", false, "in-1", NOW);

        assertThat(updated.currentTopic()).isEqualTo(LeadJourneyTopic.PROMOTIONS_PRICING);
        assertThat(updated.pausedTopics()).contains(LeadJourneyTopic.VEHICLE_DISCOVERY);
        assertThat(updated.visitedTopics()).contains(LeadJourneyTopic.PROMOTIONS_PRICING);
    }

    @Test
    void correctionReplacesOnlyMatchingFact() {
        var original = new LeadJourneyFact("Bronco", "Bronco", NOW);
        var other = new LeadJourneyFact("nuevo", "nuevo", NOW);
        var current = new LeadJourneySnapshot(UUID.randomUUID(), UUID.randomUUID(), null,
                LeadJourneyTopic.VEHICLE_DISCOVERY, Set.of(LeadJourneyTopic.VEHICLE_DISCOVERY), Set.of(),
                Map.of(LeadJourneyFactKey.VEHICLE_MODEL, original, LeadJourneyFactKey.NEW_OR_USED, other),
                null, 0, null, 2, NOW);
        var proposal = proposal(LeadJourneyTopic.VEHICLE_DISCOVERY,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.VEHICLE_MODEL, "Ranger", "Mejor una Ranger"));

        var updated = policy.apply(current, proposal, "Mejor una Ranger", false, "in-2", NOW);

        assertThat(updated.facts()).containsEntry(LeadJourneyFactKey.VEHICLE_MODEL,
                new LeadJourneyFact("Ranger", "in-2", NOW));
        assertThat(updated.facts().get(LeadJourneyFactKey.NEW_OR_USED)).isEqualTo(other);
    }

    @Test
    void absentProposalLeavesJourneyUnchanged() {
        var current = journey(LeadJourneyTopic.VEHICLE_DISCOVERY);

        var updated = policy.apply(current, null, "¿Qué promociones tienen?", false, "in-1", NOW);

        assertThat(updated).isEqualTo(current);
    }

    @Test
    void rejectsPaymentDownPaymentAndNameFactsEvenWhenExactlyQuoted() {
        var proposal = proposal(LeadJourneyTopic.FINANCING_TRADE_IN,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.PAYMENT_METHOD, "financiamiento", "Quiero financiar"),
                new ProposedLeadJourneyFact(LeadJourneyFactKey.DOWN_PAYMENT, "20 mil pesos", "mi enganche sería de 20 mil pesos"),
                new ProposedLeadJourneyFact(LeadJourneyFactKey.CUSTOMER_NAME, "María Elena", "Me llamo María Elena"));

        var updated = policy.apply(emptyJourney(), proposal,
                "Quiero financiar; mi enganche sería de 20 mil pesos. Me llamo María Elena", false, "in-packet", NOW);

        assertThat(updated.facts()).isEmpty();
    }

    @Test
    void rejectsInferredPaymentDownPaymentAndNameEvenWhenUnrelatedEvidenceIsQuoted() {
        var proposal = proposal(LeadJourneyTopic.FINANCING_TRADE_IN,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.PAYMENT_METHOD, "financiamiento", "Quiero una Ranger"),
                new ProposedLeadJourneyFact(LeadJourneyFactKey.DOWN_PAYMENT, "20 mil pesos", "Quiero una Ranger"),
                new ProposedLeadJourneyFact(LeadJourneyFactKey.CUSTOMER_NAME, "María", "Quiero una Ranger"));

        var updated = policy.apply(emptyJourney(), proposal, "Quiero una Ranger", false, "in-1", NOW);

        assertThat(updated.facts()).isEmpty();
    }

    @Test
    void unsupportedProposalCannotOverwritePreviouslyAcceptedFact() {
        var accepted = new LeadJourneyFact("contado", "in-1", NOW);
        var current = new LeadJourneySnapshot(UUID.randomUUID(), UUID.randomUUID(), null,
                LeadJourneyTopic.FINANCING_TRADE_IN, Set.of(LeadJourneyTopic.FINANCING_TRADE_IN), Set.of(),
                Map.of(LeadJourneyFactKey.PAYMENT_METHOD, accepted), null, 0, "in-1", 1, NOW);
        var proposal = proposal(LeadJourneyTopic.FINANCING_TRADE_IN,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.PAYMENT_METHOD, "financiamiento", "Me interesa una Ranger"));

        var updated = policy.apply(current, proposal, "Me interesa una Ranger", false, "in-2", NOW.plusSeconds(1));

        assertThat(updated.facts().get(LeadJourneyFactKey.PAYMENT_METHOD)).isEqualTo(accepted);
    }

    @Test
    void capturesAppointmentAndTrimOnlyFromExactUserQuotes() {
        var proposal = proposal(LeadJourneyTopic.VEHICLE_DISCOVERY,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.VEHICLE_TRIM, "XLT", "Busco la versión XLT"),
                new ProposedLeadJourneyFact(LeadJourneyFactKey.APPOINTMENT_INTEREST, "sí", "Quiero agendar una cita"));

        var updated = policy.apply(emptyJourney(), proposal,
                "Busco la versión XLT. Quiero agendar una cita", false, "in-3", NOW);

        assertThat(updated.facts()).containsKeys(LeadJourneyFactKey.VEHICLE_TRIM, LeadJourneyFactKey.APPOINTMENT_INTEREST);
    }

    @Test
    void capturesOptionalPurchasePriceRangeButNotPaymentOrInstallmentBudget() {
        var priceProposal = proposal(LeadJourneyTopic.PROMOTIONS_PRICING,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.BUDGET_RANGE, "hasta 500 mil pesos", "Mi presupuesto para comprar es hasta 500 mil pesos"));
        var price = policy.apply(emptyJourney(), priceProposal,
                "Mi presupuesto para comprar es hasta 500 mil pesos", false, "in-4", NOW);
        assertThat(price.facts()).containsKey(LeadJourneyFactKey.BUDGET_RANGE);

        var paymentProposal = proposal(LeadJourneyTopic.FINANCING_TRADE_IN,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.BUDGET_RANGE, "10 mil al mes", "Puedo pagar 10 mil al mes"));
        var payment = policy.apply(emptyJourney(), paymentProposal,
                "Puedo pagar 10 mil al mes", false, "in-5", NOW);
        assertThat(payment.facts()).doesNotContainKey(LeadJourneyFactKey.BUDGET_RANGE);
    }

    @Test
    void rejectsBudgetValueNotPresentInExactPurchasePriceEvidence() {
        var evidence = "Mi presupuesto para comprar es hasta 500 mil pesos";
        var proposal = proposal(LeadJourneyTopic.PROMOTIONS_PRICING,
                new ProposedLeadJourneyFact(LeadJourneyFactKey.BUDGET_RANGE, "hasta 300 mil pesos", evidence));

        var updated = policy.apply(emptyJourney(), proposal, evidence, false, "in-mismatch", NOW);

        assertThat(updated.facts()).doesNotContainKey(LeadJourneyFactKey.BUDGET_RANGE);
    }

    @Test
    void rejectsBudgetAmountsWithMonthlyWeeklyOrBiweeklyCadence() {
        for (var cadence : List.of("al mes", "por semana", "cada quincena")) {
            var evidence = "Mi presupuesto es 10 mil " + cadence;
            var proposal = proposal(LeadJourneyTopic.PROMOTIONS_PRICING,
                    new ProposedLeadJourneyFact(LeadJourneyFactKey.BUDGET_RANGE, "10 mil " + cadence, evidence));

            var updated = policy.apply(emptyJourney(), proposal, evidence, false, "in-cadence", NOW);

            assertThat(updated.facts()).as("cadence: %s", cadence)
                    .doesNotContainKey(LeadJourneyFactKey.BUDGET_RANGE);
        }
    }

    @Test
    void rejectsEmailPhoneIdentityAndFinancialAccountData() {
        for (var value : List.of("ana@example.com", "5512345678", "CURP ABCD123456", "CLABE 123456789012345678")) {
            var proposal = proposal(LeadJourneyTopic.VEHICLE_DISCOVERY,
                    new ProposedLeadJourneyFact(LeadJourneyFactKey.BUDGET_RANGE, value, "Mi presupuesto es " + value));
            var updated = policy.apply(emptyJourney(), proposal, "Mi presupuesto es " + value, false, "in-6", NOW);
            assertThat(updated.facts()).isEmpty();
        }
    }

    private static LeadJourneyProposal proposal(LeadJourneyTopic topic, ProposedLeadJourneyFact... facts) {
        return new LeadJourneyProposal(topic, List.of(facts), null);
    }

    private static LeadJourneySnapshot journey(LeadJourneyTopic topic) {
        return new LeadJourneySnapshot(UUID.randomUUID(), UUID.randomUUID(), null, topic,
                Set.of(topic), Set.of(), Map.of(), null, 0, null, 0, NOW);
    }

    private static LeadJourneySnapshot emptyJourney() {
        return new LeadJourneySnapshot(UUID.randomUUID(), UUID.randomUUID(), null, null,
                Set.of(), Set.of(), Map.of(), null, 0, null, 0, NOW);
    }
}
