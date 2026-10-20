package com.ford.service.conversations.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.api.SendMessageRequest;
import com.ford.service.conversations.application.port.out.IdempotencyRepository;
import com.ford.service.conversations.application.port.out.OperatorConversationCommandRepository;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class DefaultConversationCommandService implements ConversationCommandService {

    private static final String OPERATION = "conversation.message.create";
    private final OperatorConversationCommandRepository conversations;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;

    public DefaultConversationCommandService(OperatorConversationCommandRepository conversations,
                                             IdempotencyRepository idempotency,
                                             ObjectMapper objectMapper) {
        this.conversations = Objects.requireNonNull(conversations);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional
    public CommandReceipt sendMessage(AuthenticatedOperator operator, UUID tenantId, UUID conversationId,
                                      long expectedVersion, String idempotencyKey,
                                      SendMessageRequest request) {
        requireOperator(operator, tenantId, idempotencyKey, request);
        var normalizedKey = idempotencyKey.trim();
        var hash = requestHash(conversationId, expectedVersion, request);
        var existing = idempotency.find(tenantId, operator.subject(), OPERATION, normalizedKey);
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), hash);
        }
        if (!idempotency.reserve(tenantId, operator.subject(), OPERATION, normalizedKey, hash, Instant.now())) {
            return idempotency.find(tenantId, operator.subject(), OPERATION, normalizedKey)
                    .map(stored -> replayOrReject(stored, hash))
                    .orElseThrow(() -> new IllegalStateException("idempotency command is already being processed"));
        }

        var target = conversations.find(tenantId, conversationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("conversation not found"));
        if (!target.bindingEnabled()) {
            throw new IllegalStateException("conversation provider binding is disabled");
        }
        if (target.version() != expectedVersion) {
            throw new IllegalStateException("conversation version is stale");
        }
        if (target.state() == com.ford.service.conversations.domain.ConversationState.CLOSED
                || target.state() == com.ford.service.conversations.domain.ConversationState.HUMAN_PENDING) {
            throw new IllegalStateException("conversation state does not accept operator messages");
        }
        var messageId = UUID.randomUUID();
        var receipt = conversations.appendMessage(new OperatorConversationCommandRepository.CommandMessage(
                tenantId, conversationId, operatorId(operator.subject()),
                messageId, "operator:" + normalizedKey, request.content().trim(), request.internalNote(), expectedVersion));
        complete(tenantId, operator.subject(), normalizedKey, hash, receipt);
        return receipt;
    }

    private void complete(UUID tenantId, String actorId, String key, String hash, CommandReceipt receipt) {
        try {
            idempotency.complete(tenantId, actorId, OPERATION, key, objectMapper.writeValueAsString(receipt), Instant.now());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot persist command receipt", exception);
        }
    }

    private CommandReceipt replayOrReject(IdempotencyRepository.StoredCommand stored, String expectedHash) {
        if (!MessageDigest.isEqual(stored.requestHash().getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("idempotency key was reused with a different request");
        }
        if (stored.responseJson() == null || stored.responseJson().isBlank()) {
            throw new IllegalStateException("idempotency command is already being processed");
        }
        try {
            return objectMapper.readValue(stored.responseJson(), CommandReceipt.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored command receipt is invalid", exception);
        }
    }

    private static void requireOperator(AuthenticatedOperator operator, UUID tenantId, String key,
                                        SendMessageRequest request) {
        if (operator == null || tenantId == null || !operator.canAccess(tenantId)) {
            throw new SecurityException("operator is not authorized for tenant");
        }
        if (key == null || key.isBlank() || key.length() > 240) {
            throw new IllegalArgumentException("Idempotency-Key is required and must not exceed 240 characters");
        }
        Objects.requireNonNull(request, "message request is required");
    }

    private static String requestHash(UUID conversationId, long expectedVersion, SendMessageRequest request) {
        var value = conversationId + "|" + expectedVersion + "|" + request.content().trim() + "|" + request.internalNote();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static UUID operatorId(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8));
        }
    }
}
