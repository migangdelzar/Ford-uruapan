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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandoffAuthorizationTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    @Test
    void rejectsClaimForTenantOutsideOperatorClaims() {
        var repository = new InMemoryHandoffRepository();
        var ticket = repository.seed(TENANT_A, UUID.randomUUID());
        HandoffService service = new DefaultHandoffService(repository, event -> { });

        assertThatThrownBy(() -> service.claim(ticket.id(), 0,
                principal(UUID.randomUUID(), TENANT_B)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsStaleVersionWithoutChangingTheTicket() {
        var repository = new InMemoryHandoffRepository();
        var ticket = repository.seed(TENANT_A, UUID.randomUUID());
        HandoffService service = new DefaultHandoffService(repository, event -> { });

        assertThatIllegalStateException()
                .isThrownBy(() -> service.claim(ticket.id(), 7,
                        principal(UUID.randomUUID(), TENANT_A)))
                .withMessageContaining("stale");
        assertThat(repository.findRequired(ticket.id()).version()).isZero();
    }

    @Test
    void derivesTenantFromConversationAndPublishesAuditableAction() {
        var repository = new InMemoryHandoffRepository();
        var conversationId = UUID.randomUUID();
        repository.conversationTenants.put(conversationId, TENANT_A);
        var events = new ArrayList<Object>();
        HandoffService service = new DefaultHandoffService(repository, events::add);

        var created = service.request(
                new HandoffRequest(TENANT_B, conversationId, null, "CUSTOMER_REQUEST"),
                principal(UUID.randomUUID(), TENANT_A));

        assertThat(created.tenantId()).isEqualTo(TENANT_A);
        assertThat(repository.auditActions).containsExactly("HANDOFF_REQUESTED");
        assertThat(events).hasSize(1);
    }

    @Test
    void claimPauseAndResumeRequireExpectedVersionAndOperatorClaim() {
        var repository = new InMemoryHandoffRepository();
        var conversationId = UUID.randomUUID();
        var ticket = repository.seed(TENANT_A, conversationId);
        HandoffService service = new DefaultHandoffService(repository, event -> { });
        var principal = principal(UUID.randomUUID(), TENANT_A);

        var claimed = service.claim(ticket.id(), 0, principal);
        var paused = service.pauseBot(conversationId, claimed.version(), principal);
        var resumed = service.resumeBot(conversationId, paused.version(), principal);

        assertThat(claimed.status()).isEqualTo(HandoffStatus.HUMAN_ACTIVE);
        assertThat(paused.version()).isEqualTo(2);
        assertThat(resumed.status()).isEqualTo(HandoffStatus.CLOSED);
        assertThat(resumed.version()).isEqualTo(3);
    }

    private static OperatorPrincipal principal(UUID operatorId, UUID tenantId) {
        return new OperatorPrincipal(operatorId, java.util.Set.of(tenantId), java.util.Set.of("ROLE_OPERATOR"));
    }

    private static final class InMemoryHandoffRepository implements HandoffRepository {
        private final Map<UUID, HandoffTicket> tickets = new HashMap<>();
        private final Map<UUID, UUID> conversationTenants = new HashMap<>();
        private final List<String> auditActions = new ArrayList<>();

        HandoffTicket seed(UUID tenantId, UUID conversationId) {
            conversationTenants.put(conversationId, tenantId);
            var ticket = new HandoffTicket(UUID.randomUUID(), tenantId, conversationId, null,
                    HandoffStatus.HUMAN_PENDING, null, "CUSTOMER_REQUEST", 0,
                    Instant.now(), null, null);
            tickets.put(ticket.id(), ticket);
            return ticket;
        }

        @Override
        public HandoffTicket save(HandoffTicket ticket) {
            tickets.put(ticket.id(), ticket);
            conversationTenants.put(ticket.conversationId(), ticket.tenantId());
            return ticket;
        }

        @Override
        public HandoffTicket findRequired(UUID id) {
            return Optional.ofNullable(tickets.get(id)).orElseThrow();
        }

        @Override
        public HandoffTicket findByConversation(UUID conversationId) {
            return tickets.values().stream()
                    .filter(ticket -> ticket.conversationId().equals(conversationId))
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
            if (current == null || !current.tenantId().equals(tenantId)
                    || current.version() != expectedVersion || current.status() != HandoffStatus.HUMAN_PENDING) {
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
                    newState(HandoffStatus.HUMAN_ACTIVE, operatorId, claimedAt, null));
        }

        @Override
        public Optional<HandoffTicket> resume(UUID conversationId, UUID tenantId, UUID operatorId,
                                              long expectedVersion, Instant closedAt) {
            return transition(conversationId, tenantId, expectedVersion,
                    newState(HandoffStatus.CLOSED, null, null, closedAt));
        }

        private Optional<HandoffTicket> transition(UUID conversationId, UUID tenantId, long expectedVersion,
                                                   java.util.function.Function<HandoffTicket, HandoffTicket> transition) {
            var current = findByConversation(conversationId);
            if (!current.tenantId().equals(tenantId) || current.version() != expectedVersion) {
                return Optional.empty();
            }
            return Optional.of(save(transition.apply(current)));
        }

        private java.util.function.Function<HandoffTicket, HandoffTicket> newState(HandoffStatus status,
                                                                                     UUID operatorId,
                                                                                     Instant claimedAt,
                                                                                     Instant closedAt) {
            return current -> new HandoffTicket(current.id(), current.tenantId(), current.conversationId(),
                    current.leadId(), status, operatorId == null ? current.assignedOperatorId() : operatorId,
                    current.reason(), current.version() + 1, current.createdAt(),
                    claimedAt == null ? current.claimedAt() : claimedAt, closedAt);
        }

        @Override
        public void recordAudit(String action, HandoffTicket ticket, OperatorPrincipal principal) {
            auditActions.add(action);
        }
    }
}
