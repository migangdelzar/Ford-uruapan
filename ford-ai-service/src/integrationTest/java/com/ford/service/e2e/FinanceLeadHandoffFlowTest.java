package com.ford.service.e2e;

import com.ford.service.finance.api.FinanceEstimateRequest;
import com.ford.service.finance.application.DeterministicFinanceCalculator;
import com.ford.service.finance.application.FinanceService;
import com.ford.service.finance.domain.FinanceTerms;
import com.ford.service.handoff.application.DefaultHandoffService;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import com.ford.service.leads.application.ConversationSignals;
import com.ford.service.leads.application.DefaultLeadScoringPolicy;
import com.ford.service.leads.domain.LeadProfile;
import com.ford.service.shared.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceLeadHandoffFlowTest {

    private static final Currency MXN = Currency.getInstance("MXN");

    @Test
    void calculatesNonBindingFinanceScoresHotLeadAndClaimsHumanHandoff() {
        var finance = new FinanceService(new DeterministicFinanceCalculator());
        var estimate = finance.estimate(new FinanceEstimateRequest(money("589000"), money("150000"), 36),
                new FinanceTerms(new BigDecimal("0.12"), money("15000"), money("0")));
        var score = new DefaultLeadScoringPolicy().score(
                new LeadProfile("Ana", "4521234567", "ana@example.com", "Ranger", "$500k-$650k", "$150k", null, false, true),
                new ConversationSignals(true, true, true, true, true));
        var repository = new MemoryHandoffRepository();
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        repository.conversationTenants.put(conversationId, tenantId);
        var principal = new OperatorPrincipal(UUID.randomUUID(), Set.of(tenantId), Set.of("ROLE_OPERATOR"));
        var handoff = new DefaultHandoffService(repository, event -> { }, (tenant, conversation, operator, handoffId) -> { });
        var ticket = handoff.request(new HandoffRequest(UUID.randomUUID(), conversationId, null, score.band().name()), principal);
        var claimed = handoff.claim(ticket.id(), 0, principal);

        assertThat(estimate.binding()).isFalse();
        assertThat(estimate.monthlyPayment().currency()).isEqualTo(MXN);
        assertThat(score.band().name()).isEqualTo("HOT");
        assertThat(claimed.status()).isEqualTo(HandoffStatus.HUMAN_ACTIVE);
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount).setScale(2), MXN);
    }

    private static final class MemoryHandoffRepository implements HandoffRepository {
        private final java.util.Map<UUID, HandoffTicket> tickets = new java.util.HashMap<>();
        private final java.util.Map<UUID, UUID> conversationTenants = new java.util.HashMap<>();

        @Override public HandoffTicket save(HandoffTicket ticket) { tickets.put(ticket.id(), ticket); return ticket; }
        @Override public HandoffTicket findRequired(UUID id) { return tickets.get(id); }
        @Override public HandoffTicket findByConversation(UUID conversationId) {
            return tickets.values().stream().filter(ticket -> ticket.conversationId().equals(conversationId)).findFirst().orElseThrow();
        }
        @Override public List<HandoffTicket> list(UUID tenantId) {
            return tickets.values().stream().filter(ticket -> ticket.tenantId().equals(tenantId)).toList();
        }
        @Override public UUID findConversationTenant(UUID conversationId) { return conversationTenants.get(conversationId); }
        @Override public Optional<HandoffTicket> claim(UUID ticketId, UUID tenantId, UUID operatorId, long expectedVersion, Instant claimedAt) {
            var current = tickets.get(ticketId);
            if (current == null || !current.tenantId().equals(tenantId) || current.version() != expectedVersion
                    || current.status() != HandoffStatus.HUMAN_PENDING) return Optional.empty();
            var claimed = new HandoffTicket(current.id(), current.tenantId(), current.conversationId(), current.leadId(),
                    HandoffStatus.HUMAN_ACTIVE, operatorId, current.reason(), current.version() + 1,
                    current.createdAt(), claimedAt, current.closedAt());
            return Optional.of(save(claimed));
        }
        @Override public Optional<HandoffTicket> pause(UUID conversationId, UUID tenantId, UUID operatorId, long expectedVersion, Instant claimedAt) { return Optional.empty(); }
        @Override public Optional<HandoffTicket> resume(UUID conversationId, UUID tenantId, UUID operatorId, long expectedVersion, Instant closedAt) { return Optional.empty(); }
        @Override public void recordAudit(String action, HandoffTicket ticket, OperatorPrincipal principal) { }
    }
}
