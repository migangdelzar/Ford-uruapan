package com.ford.service.handoff;

import com.ford.service.conversations.api.BdcHandoffPacket;
import com.ford.service.conversations.application.DealershipHours;
import com.ford.service.conversations.application.DealershipHoursPolicy;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.application.AfterHoursDigestService;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import com.ford.service.handoff.domain.HandoffPriority;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AfterHoursDigestServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("e6261640-bb6a-49e1-924e-a6f18acac0a1");
    private static final Instant WINDOW_START = Instant.parse("2026-10-17T20:00:00Z");
    private static final Instant AVAILABLE_AT = Instant.parse("2026-10-19T14:45:00Z");

    @Test
    void rejectsOperatorsWithoutTheRequestedTenant() {
        var repository = mock(HandoffRepository.class);
        var service = service(repository, Instant.parse("2026-10-19T14:50:00Z"));
        var principal = principal(UUID.randomUUID(), "ROLE_OPERATOR");

        assertThatThrownBy(() -> service.get(TENANT_ID, principal)).isInstanceOf(SecurityException.class);
        verify(repository, never()).listCreatedBetween(eq(TENANT_ID), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hidesTicketDetailsUntilTheDigestAvailabilityTime() {
        var repository = mock(HandoffRepository.class);
        var service = service(repository, Instant.parse("2026-10-19T14:44:59Z"));

        var digest = service.get(TENANT_ID, principal(TENANT_ID, "SCOPE_operator:read"));

        assertThat(digest.available()).isFalse();
        assertThat(digest.availableAt()).isEqualTo(AVAILABLE_AT);
        assertThat(digest.items()).isEmpty();
        assertThat(digest.totalCount()).isZero();
        verify(repository, never()).listCreatedBetween(eq(TENANT_ID), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void includesOnlyAfterHoursTicketsAndReportsCompleteAndPartialPacketCounts() {
        var repository = mock(HandoffRepository.class);
        var service = service(repository, Instant.parse("2026-10-19T14:50:00Z"));
        var complete = ticket(Instant.parse("2026-10-18T22:00:00Z"), completePacket());
        var partial = ticket(Instant.parse("2026-10-19T14:46:00Z"), BdcHandoffPacket.empty());
        var duringBusinessHours = ticket(Instant.parse("2026-10-19T15:30:00Z"), completePacket());
        when(repository.listCreatedBetween(TENANT_ID, WINDOW_START, Instant.parse("2026-10-19T14:50:00Z")))
                .thenReturn(List.of(complete, partial, duringBusinessHours));

        var digest = service.get(TENANT_ID, principal(TENANT_ID, "ROLE_OPERATOR"));

        assertThat(digest.available()).isTrue();
        assertThat(digest.totalCount()).isEqualTo(2);
        assertThat(digest.completePacketCount()).isEqualTo(1);
        assertThat(digest.partialPacketCount()).isEqualTo(1);
        assertThat(digest.items()).extracting(item -> item.handoffId())
                .containsExactly(complete.id(), partial.id());
        assertThat(digest.items()).allSatisfy(item -> assertThat(item.packetCompleteness()).isBetween(0, 5));
        verify(repository).listCreatedBetween(TENANT_ID, WINDOW_START, Instant.parse("2026-10-19T14:50:00Z"));
    }

    private static AfterHoursDigestService service(HandoffRepository repository, Instant now) {
        return new AfterHoursDigestService(repository, policy(now));
    }

    private static DealershipHoursPolicy policy(Instant now) {
        var weekday = new DealershipHours(LocalTime.of(9, 0), LocalTime.of(19, 0));
        return new DealershipHoursPolicy(Clock.fixed(now, ZoneId.of("UTC")), ZoneId.of("America/Mexico_City"),
                Map.of(DayOfWeek.MONDAY, weekday, DayOfWeek.TUESDAY, weekday, DayOfWeek.WEDNESDAY, weekday,
                        DayOfWeek.THURSDAY, weekday, DayOfWeek.FRIDAY, weekday,
                        DayOfWeek.SATURDAY, new DealershipHours(LocalTime.of(9, 0), LocalTime.of(14, 0))),
                Map.of());
    }

    private static HandoffTicket ticket(Instant createdAt, BdcHandoffPacket packet) {
        return new HandoffTicket(UUID.randomUUID(), TENANT_ID, UUID.randomUUID(), UUID.randomUUID(),
                HandoffStatus.HUMAN_PENDING, HandoffPriority.NORMAL, null, "CUSTOMER_REQUEST", "idempotency",
                0, createdAt, null, null, packet);
    }

    private static BdcHandoffPacket completePacket() {
        return new BdcHandoffPacket(Optional.of("Ranger"), Optional.of("XLT"), Optional.of("familia"),
                Optional.of("este mes"), Optional.of("Ford Focus 2018"), Optional.of("$25,000-$30,000"),
                Optional.empty(), Optional.of("Cliente"));
    }

    private static OperatorPrincipal principal(UUID tenantId, String authority) {
        return new OperatorPrincipal(UUID.randomUUID(), Set.of(tenantId), Set.of(authority));
    }
}
