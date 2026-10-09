package com.ford.service.identity.application;

import com.ford.service.identity.api.OperatorIdentity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class OperatorIdentityService {

    private final OperatorIdentityRepository repository;
    private final Clock clock;

    public OperatorIdentityService(OperatorIdentityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Optional<OperatorIdentity> findActiveByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return repository.findActiveByEmail(OperatorIdentity.normalizeEmail(email))
                .filter(OperatorIdentity::enabled);
    }

    public Optional<OperatorIdentity> findActiveById(UUID id) {
        if (id == null) return Optional.empty();
        return repository.findActiveById(id).filter(OperatorIdentity::enabled);
    }

    public Optional<OperatorIdentity> acceptInvite(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        return repository.acceptInvitation(sha256(rawToken), clock.instant());
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
