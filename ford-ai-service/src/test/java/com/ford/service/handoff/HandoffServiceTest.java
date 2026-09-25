package com.ford.service.handoff;

import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.application.DefaultHandoffService;
import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HandoffServiceTest {

    @Test
    void createsAndClaimsPendingTicketWithOptimisticVersion() {
        var repository = new InMemoryHandoffRepository();
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        repository.conversationTenants.put(conversationId, tenantId);
        HandoffService service = new DefaultHandoffService(repository, event -> { });
        var principal = principal(tenantId);
        var ticket = service.request(new HandoffRequest(UUID.randomUUID(), conversationId, null, "HOT_LEAD"), principal);

        var claimed = service.claim(ticket.id(), 0, principal);

        assertThat(claimed.status()).isEqualTo(HandoffStatus.HUMAN_ACTIVE);
        assertThat(claimed.assignedOperatorId()).isEqualTo(principal.operatorId());
        assertThat(claimed.version()).isEqualTo(1);
    }

    @Test
    void doesNotAllowSecondOperatorToClaimAnActiveTicket() {
        var repository = new InMemoryHandoffRepository();
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        repository.conversationTenants.put(conversationId, tenantId);
        HandoffService service = new DefaultHandoffService(repository, event -> { });
        var principal = principal(tenantId);
        var ticket = service.request(new HandoffRequest(tenantId, conversationId, null, "CUSTOMER_REQUEST"), principal);
        service.claim(ticket.id(), 0, principal);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.claim(ticket.id(), 0, principal));
    }

    private static OperatorPrincipal principal(UUID tenantId) {
        return new OperatorPrincipal(UUID.randomUUID(), Set.of(tenantId), Set.of("ROLE_OPERATOR"));
    }

    private static final class InMemoryHandoffRepository implements HandoffRepository {
        private final Map<UUID, HandoffTicket> tickets = new HashMap<>();
        private final Map<UUID, UUID> conversationTenants = new HashMap<>();

        @Override
        public HandoffTicket save(HandoffTicket ticket) {
            tickets.put(ticket.id(), ticket);
            return ticket;
        }

        @Override
        public HandoffTicket findRequired(UUID id) {
            return Optional.ofNullable(tickets.get(id)).orElseThrow();
        }

        @Override
        public HandoffTicket findByConversation(UUID conversationId) {
            return tickets.values().stream().filter(ticket -> ticket.conversationId().equals(conversationId))
                    .findFirst().orElseThrow();
        }

        @Override
        public List<HandoffTicket> list(UUID tenantId) {
            return tickets.values().stream().filter(ticket -> ticket.tenantId().equals(tenantId)).toList();
        }

        @Override
        public UUID findConversationTenant(UUID conversationId) {
            return Optional.ofNullable(conversationTenants.get(conversationId)).orElseThrow();
        }

        @Override
        public Optional<HandoffTicket> claim(UUID ticketId, UUID tenantId, UUID operatorId,
                                             long expectedVersion, Instant claimedAt) {
            var current = tickets.get(ticketId);
            if (current == null || !current.tenantId().equals(tenantId) || current.version() != expectedVersion
                    || current.status() != HandoffStatus.HUMAN_PENDING) {
                return Optional.empty();
            }
            return Optional.of(save(new HandoffTicket(current.id(), current.tenantId(), current.conversationId(),
                    current.leadId(), HandoffStatus.HUMAN_ACTIVE, operatorId, current.reason(), current.version() + 1,
                    current.createdAt(), claimedAt, current.closedAt())));
        }

        @Override
        public Optional<HandoffTicket> pause(UUID conversationId, UUID tenantId, UUID operatorId,
                                             long expectedVersion, Instant claimedAt) {
            return transition(conversationId, tenantId, expectedVersion,
                    ticket -> new HandoffTicket(ticket.id(), ticket.tenantId(), ticket.conversationId(), ticket.leadId(),
                            HandoffStatus.HUMAN_ACTIVE, operatorId, ticket.reason(), ticket.version() + 1,
                            ticket.createdAt(), ticket.claimedAt() == null ? claimedAt : ticket.claimedAt(), ticket.closedAt()));
        }

        @Override
        public Optional<HandoffTicket> resume(UUID conversationId, UUID tenantId, UUID operatorId,
                                              long expectedVersion, Instant closedAt) {
            return transition(conversationId, tenantId, expectedVersion,
                    ticket -> new HandoffTicket(ticket.id(), ticket.tenantId(), ticket.conversationId(), ticket.leadId(),
                            HandoffStatus.CLOSED, ticket.assignedOperatorId(), ticket.reason(), ticket.version() + 1,
                            ticket.createdAt(), ticket.claimedAt(), closedAt));
        }

        private Optional<HandoffTicket> transition(UUID conversationId, UUID tenantId, long expectedVersion,
                                                   java.util.function.Function<HandoffTicket, HandoffTicket> transition) {
            var current = findByConversation(conversationId);
            if (!current.tenantId().equals(tenantId) || current.version() != expectedVersion) {
                return Optional.empty();
            }
            return Optional.of(save(transition.apply(current)));
        }

        @Override
        public void recordAudit(String action, HandoffTicket ticket, OperatorPrincipal principal) {
        }
    }
}
