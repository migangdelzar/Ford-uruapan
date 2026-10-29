package com.ford.service.conversations.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.api.ConversationMemoryRestartReceipt;
import com.ford.service.conversations.api.ConversationMemoryRestarted;
import com.ford.service.conversations.api.ConversationMemoryRestartRequest;
import com.ford.service.conversations.application.port.out.ConversationMemoryKeyRepository;
import com.ford.service.conversations.application.port.out.ConversationTurnLeasePort;
import com.ford.service.conversations.application.port.out.IdempotencyRepository;
import com.ford.service.conversations.application.port.out.OperatorConversationCommandRepository;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ConversationMemoryRestartService {

    private static final String OPERATION = "conversation.memory.restart";
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    private final OperatorConversationCommandRepository conversations;
    private final ConversationMemoryKeyRepository keys;
    private final ConversationTurnLeasePort leases;
    private final ConversationMemoryService memory;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public ConversationMemoryRestartService(OperatorConversationCommandRepository conversations,
                                            ConversationMemoryKeyRepository keys,
                                            ConversationTurnLeasePort leases,
                                            ConversationMemoryService memory,
                                            IdempotencyRepository idempotency,
                                            ObjectMapper objectMapper,
                                            ApplicationEventPublisher events) {
        this.conversations = conversations;
        this.keys = keys;
        this.leases = leases;
        this.memory = memory;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Transactional
    public ConversationMemoryRestartReceipt restart(AuthenticatedOperator operator, UUID tenantId,
                                                     UUID conversationId, long expectedVersion,
                                                     String idempotencyKey) {
        return restart(operator, tenantId, conversationId, expectedVersion, idempotencyKey,
                ConversationMemoryRestartRequest.RestartReason.LEGACY_CLIENT_UNSPECIFIED);
    }

    @Transactional
    public ConversationMemoryRestartReceipt restart(AuthenticatedOperator operator, UUID tenantId,
                                                     UUID conversationId, long expectedVersion,
                                                     String idempotencyKey,
                                                     ConversationMemoryRestartRequest.RestartReason reason) {
        requireOperator(operator, tenantId, idempotencyKey);
        if (reason == null) reason = ConversationMemoryRestartRequest.RestartReason.LEGACY_CLIENT_UNSPECIFIED;
        var normalizedKey = idempotencyKey.trim();
        var hash = requestHash(conversationId, expectedVersion, reason);
        var previous = idempotency.find(tenantId, operator.subject(), OPERATION, normalizedKey);
        if (previous.isPresent()) {
            return replayOrReject(previous.get(), hash);
        }
        if (!idempotency.reserve(tenantId, operator.subject(), OPERATION, normalizedKey, hash, Instant.now())) {
            return idempotency.find(tenantId, operator.subject(), OPERATION, normalizedKey)
                    .map(stored -> replayOrReject(stored, hash))
                    .orElseThrow(() -> new IllegalStateException("idempotency command is already being processed"));
        }

        var target = conversations.find(tenantId, conversationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("conversation not found"));
        if (target.version() != expectedVersion) {
            throw new IllegalStateException("conversation version is stale");
        }
        if (target.state() != ConversationState.BOT_ACTIVE && target.state() != ConversationState.LEAD_CAPTURE) {
            throw new IllegalStateException("conversation is not bot-owned");
        }

        var leaseOwner = "memory-restart:" + UUID.randomUUID();
        if (!leases.tryAcquire(tenantId, conversationId, leaseOwner, LEASE_DURATION)) {
            throw new IllegalStateException("conversation bot turn is active; retry after it finishes");
        }
        try {
            if (!conversations.bumpVersionForMemoryRestart(tenantId, conversationId, expectedVersion)) {
                throw new IllegalStateException("conversation version is stale or state changed");
            }
            var key = keys.find(tenantId, conversationId)
                    .orElseThrow(() -> new java.util.NoSuchElementException("conversation not found"));
            var resetAt = Instant.now();
            memory.resetContext(key, UUID.nameUUIDFromBytes(operator.subject().getBytes(StandardCharsets.UTF_8)), resetAt);
            var receipt = new ConversationMemoryRestartReceipt(conversationId, expectedVersion + 1, resetAt);
            complete(tenantId, operator.subject(), normalizedKey, hash, receipt);
            events.publishEvent(new ConversationMemoryRestarted(tenantId, conversationId,
                    receipt.conversationVersion(), resetAt, operator.subject(), reason));
            if (reason == ConversationMemoryRestartRequest.RestartReason.LEGACY_CLIENT_UNSPECIFIED) {
                org.slf4j.LoggerFactory.getLogger(ConversationMemoryRestartService.class)
                        .info("Conversation memory restart reason omitted: conversationId={} actorId={}",
                                conversationId, operator.subject());
            }
            return receipt;
        } finally {
            leases.release(tenantId, conversationId, leaseOwner);
        }
    }

    private void complete(UUID tenantId, String actor, String key, String hash,
                          ConversationMemoryRestartReceipt receipt) {
        try {
            idempotency.complete(tenantId, actor, OPERATION, key, objectMapper.writeValueAsString(receipt), Instant.now());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot persist restart receipt", exception);
        }
    }

    private ConversationMemoryRestartReceipt replayOrReject(IdempotencyRepository.StoredCommand stored,
                                                            String expectedHash) {
        if (!MessageDigest.isEqual(stored.requestHash().getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("idempotency key was reused with a different request");
        }
        if (stored.responseJson() == null || stored.responseJson().isBlank()) {
            throw new IllegalStateException("idempotency command is already being processed");
        }
        try {
            return objectMapper.readValue(stored.responseJson(), ConversationMemoryRestartReceipt.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored restart receipt is invalid", exception);
        }
    }

    private static void requireOperator(AuthenticatedOperator operator, UUID tenantId, String idempotencyKey) {
        if (operator == null || tenantId == null || !operator.canAccess(tenantId)
                || (!operator.hasAuthority("SCOPE_operator:write") && !operator.hasRole("ADMIN"))) {
            throw new SecurityException("operator is not authorized for tenant");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 240) {
            throw new IllegalArgumentException("Idempotency-Key is required and must not exceed 240 characters");
        }
    }

    private static String requestHash(UUID conversationId, long expectedVersion,
                                      ConversationMemoryRestartRequest.RestartReason reason) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((conversationId + "|" + expectedVersion + "|" + reason.name()).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
