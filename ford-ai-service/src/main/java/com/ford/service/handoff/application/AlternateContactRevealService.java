package com.ford.service.handoff.application;

import com.ford.service.handoff.api.AlternateContactRevealView;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class AlternateContactRevealService {
    private final HandoffRepository repository;

    public AlternateContactRevealService(HandoffRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional
    public AlternateContactRevealView reveal(UUID handoffId, OperatorPrincipal principal) {
        if (principal == null || !(principal.authorities().contains("SCOPE_handoff:manage")
                || principal.authorities().contains("ROLE_ADMIN"))) {
            throw new SecurityException("alternate contact reveal authority is required");
        }
        if (handoffId == null) throw new IllegalArgumentException("handoff id is required");
        var contact = repository.findAlternateContact(principal.tenantIds(), handoffId)
                .orElseThrow(() -> new NoSuchElementException("alternate contact is unavailable"));
        repository.recordAlternateContactReveal(handoffId, principal, Instant.now());
        return AlternateContactRevealView.from(handoffId, contact);
    }
}
