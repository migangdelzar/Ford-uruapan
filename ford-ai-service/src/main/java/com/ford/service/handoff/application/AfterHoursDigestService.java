package com.ford.service.handoff.application;

import com.ford.service.conversations.api.AfterHoursDigestWindowProvider;
import com.ford.service.handoff.api.AfterHoursDigest;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AfterHoursDigestService {
    private final HandoffRepository repository;
    private final AfterHoursDigestWindowProvider hoursPolicy;

    public AfterHoursDigestService(HandoffRepository repository, AfterHoursDigestWindowProvider hoursPolicy) {
        this.repository = Objects.requireNonNull(repository);
        this.hoursPolicy = Objects.requireNonNull(hoursPolicy);
    }

    @Transactional(readOnly = true)
    public AfterHoursDigest get(UUID tenantId, OperatorPrincipal principal) {
        requireTenantOperator(tenantId, principal);
        var window = hoursPolicy.getAfterHoursDigestWindow();
        var tickets = window.available()
                ? repository.listCreatedBetween(tenantId, window.windowStart(), window.windowEnd()).stream()
                        .filter(ticket -> ticket.tenantId().equals(tenantId))
                        .filter(ticket -> !ticket.createdAt().isBefore(window.windowStart())
                                && ticket.createdAt().isBefore(window.windowEnd()))
                        .filter(ticket -> hoursPolicy.isOutsideBusinessHours(ticket.createdAt()))
                        .toList()
                : List.<com.ford.service.handoff.domain.HandoffTicket>of();
        var items = tickets.stream().map(ticket -> new AfterHoursDigest.Item(
                ticket.id(), ticket.conversationId(), ticket.createdAt(), ticket.status(), ticket.reason(),
                ticket.packet().completeness(), ticket.packet().partial())).toList();
        var partial = tickets.stream().filter(ticket -> ticket.packet().partial()).count();
        return new AfterHoursDigest(tenantId, window.businessDate(), window.timeZone(), window.available(),
                window.availableAt(), window.windowStart(), window.windowEnd(), tickets.size(),
                tickets.size() - Math.toIntExact(partial), Math.toIntExact(partial), items);
    }

    private static void requireTenantOperator(UUID tenantId, OperatorPrincipal principal) {
        if (principal == null || !principal.isOperator()) {
            throw new SecurityException("Operator authority is required");
        }
        if (tenantId == null || !principal.canAccess(tenantId)) {
            throw new SecurityException("Operator is not authorized for tenant");
        }
    }
}
