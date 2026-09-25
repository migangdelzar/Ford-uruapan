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
