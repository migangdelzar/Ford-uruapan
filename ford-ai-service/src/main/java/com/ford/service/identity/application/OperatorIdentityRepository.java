package com.ford.service.identity.application;

import com.ford.service.identity.api.OperatorIdentity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OperatorIdentityRepository {

    Optional<OperatorIdentity> findActiveByEmail(String normalizedEmail);

    Optional<OperatorIdentity> findActiveById(UUID id);

    /** Atomically consumes one unexpired invite and creates its identity. */
    Optional<OperatorIdentity> acceptInvitation(String tokenHash, Instant acceptedAt);
}
