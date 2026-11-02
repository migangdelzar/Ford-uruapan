package com.ford.service.leads.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.leads.api.LeadUpdateRequest;
import com.ford.service.leads.api.LeadView;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class LeadCommandService {
    private static final String OPERATION = "lead.update";
    private final LeadCommandRepository repository;
    private final LeadIdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;

    public LeadCommandService(LeadCommandRepository repository, LeadIdempotencyRepository idempotency,
                              ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public LeadView update(AuthenticatedOperator operator, UUID tenantId, UUID leadId, long expectedVersion,
                           String idempotencyKey, LeadUpdateRequest request) {
        if (operator == null || tenantId == null || leadId == null || !operator.canAccess(tenantId)) {
            throw new SecurityException("operator is not authorized for tenant");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 240) {
            throw new IllegalArgumentException("Idempotency-Key is required and must not exceed 240 characters");
        }
        Objects.requireNonNull(request, "lead update request is required");
        validate(request);
        var key = idempotencyKey.trim();
        var hash = requestHash(leadId, expectedVersion, request);
        var existing = idempotency.find(tenantId, operator.subject(), OPERATION, key);
        if (existing.isPresent()) return replayOrReject(existing.get(), hash);
        if (!idempotency.reserve(tenantId, operator.subject(), OPERATION, key, hash, Instant.now())) {
            return idempotency.find(tenantId, operator.subject(), OPERATION, key)
                    .map(stored -> replayOrReject(stored, hash))
                    .orElseThrow(() -> new IllegalStateException("idempotency command is already being processed"));
        }
        var current = repository.find(tenantId, leadId).orElseThrow(() -> new NoSuchElementException("lead not found"));
        if (current.version() != expectedVersion) throw new IllegalStateException("lead version is stale");
        var updated = repository.update(tenantId, leadId, expectedVersion, request);
        try {
            idempotency.complete(tenantId, operator.subject(), OPERATION, key,
                    objectMapper.writeValueAsString(updated), Instant.now());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot persist lead receipt", exception);
        }
        return updated;
    }

    private static void validate(LeadUpdateRequest request) {
        if (request.temperature() != null && !request.temperature().matches("(?i)cold|warm|hot")) {
            throw new IllegalArgumentException("temperature must be cold, warm, or hot");
        }
        if (request.temperatureSource() != null && !request.temperatureSource().matches("(?i)AI|HUMAN")) {
            throw new IllegalArgumentException("temperatureSource must be AI or HUMAN");
        }
    }

    private static String requestHash(UUID leadId, long version, LeadUpdateRequest request) {
        var value = leadId + "|" + version + "|" + request;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private LeadView replayOrReject(LeadIdempotencyRepository.StoredCommand stored, String expectedHash) {
        if (!MessageDigest.isEqual(stored.requestHash().getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("idempotency key was reused with a different request");
        }
        if (stored.responseJson() == null || stored.responseJson().isBlank()) {
            throw new IllegalStateException("idempotency command is already being processed");
        }
        try {
            return objectMapper.readValue(stored.responseJson(), LeadView.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored lead receipt is invalid", exception);
        }
    }

    public interface LeadCommandRepository {
        Optional<LeadView> find(UUID tenantId, UUID leadId);

        LeadView update(UUID tenantId, UUID leadId, long expectedVersion, LeadUpdateRequest request);
    }

    public interface LeadIdempotencyRepository {
        Optional<StoredCommand> find(UUID tenantId, String actorId, String operation, String key);

        boolean reserve(UUID tenantId, String actorId, String operation, String key, String requestHash,
                        Instant createdAt);

        void complete(UUID tenantId, String actorId, String operation, String key, String responseJson,
                      Instant completedAt);

        record StoredCommand(String requestHash, String responseJson) { }
    }
}
