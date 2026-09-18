package com.ford.service.handoff;

import com.ford.service.handoff.application.DefaultHandoffService;
import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HandoffServiceTest {

    @Test
    void createsAndClaimsPendingTicketWithOptimisticVersion() {
        var repository = new InMemoryHandoffRepository();
        HandoffService service = new DefaultHandoffService(repository);
        var conversationId = UUID.randomUUID();
        var ticket = service.request(new HandoffRequest(
                UUID.randomUUID(), conversationId, null, "HOT_LEAD"));

        var operatorId = UUID.randomUUID();
        var claimed = service.claim(ticket.id(), operatorId);

        assertThat(claimed.status()).isEqualTo(HandoffStatus.HUMAN_ACTIVE);
        assertThat(claimed.assignedOperatorId()).isEqualTo(operatorId);
        assertThat(claimed.version()).isEqualTo(1);
    }

    @Test
    void doesNotAllowSecondOperatorToClaimAnActiveTicket() {
        var repository = new InMemoryHandoffRepository();
        HandoffService service = new DefaultHandoffService(repository);
        var ticket = service.request(new HandoffRequest(
                UUID.randomUUID(), UUID.randomUUID(), null, "CUSTOMER_REQUEST"));
        service.claim(ticket.id(), UUID.randomUUID());

        assertThatIllegalStateException()
                .isThrownBy(() -> service.claim(ticket.id(), UUID.randomUUID()));
    }

    private static final class InMemoryHandoffRepository implements HandoffRepository {
        private final List<HandoffTicket> tickets = new ArrayList<>();

        @Override
        public HandoffTicket save(HandoffTicket ticket) {
            tickets.removeIf(current -> current.id().equals(ticket.id()));
            tickets.add(ticket);
            return ticket;
        }

        @Override
        public HandoffTicket findRequired(UUID id) {
            return tickets.stream().filter(ticket -> ticket.id().equals(id)).findFirst()
                    .orElseThrow();
        }

        @Override
        public HandoffTicket findByConversation(UUID conversationId) {
            return tickets.stream().filter(ticket -> ticket.conversationId().equals(conversationId)).findFirst()
                    .orElseThrow();
        }

        @Override
        public List<HandoffTicket> list(UUID tenantId) {
            return tickets.stream().filter(ticket -> ticket.tenantId().equals(tenantId)).toList();
        }
    }
}
