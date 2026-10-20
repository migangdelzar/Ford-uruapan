package com.ford.service.conversations.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.api.CustomerMemoryForgetReceipt;
import com.ford.service.conversations.application.port.out.ConversationMemoryKeyRepository;
import com.ford.service.conversations.application.port.out.IdempotencyRepository;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class CustomerMemoryForgetService {
    private static final String OPERATION = "conversation.customer-memory.forget";
    private final CustomerMemoryService memory;
    private final ConversationMemoryKeyRepository keys;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper mapper;

    public CustomerMemoryForgetService(CustomerMemoryService memory, ConversationMemoryKeyRepository keys,
                                      IdempotencyRepository idempotency, ObjectMapper mapper) {
        this.memory = memory;
        this.keys = keys;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @Transactional
    public CustomerMemoryForgetReceipt forget(AuthenticatedOperator operator, UUID tenantId,
                                               UUID conversationId, String idempotencyKey) {
        if (operator == null || tenantId == null || !operator.canAccess(tenantId)
                || (!operator.hasAuthority("SCOPE_operator:write") && !operator.hasRole("ADMIN")))
            throw new SecurityException("operator is not authorized for tenant");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 240)
            throw new IllegalArgumentException("Idempotency-Key is required and must not exceed 240 characters");
        var key = idempotencyKey.trim();
        var hash = hash(conversationId);
        var previous = idempotency.find(tenantId, operator.subject(), OPERATION, key);
        if (previous.isPresent()) return replay(previous.get(), hash);
        if (!idempotency.reserve(tenantId, operator.subject(), OPERATION, key, hash, Instant.now()))
            return idempotency.find(tenantId, operator.subject(), OPERATION, key)
                    .map(stored -> replay(stored, hash))
                    .orElseThrow(() -> new IllegalStateException("idempotency command is already being processed"));
        var conversationKey = keys.find(tenantId, conversationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("conversation not found"));
        var forgottenAt = Instant.now();
        var result = memory.forget(tenantId, conversationKey.channel(), conversationId, forgottenAt,
                UUID.randomUUID(), operator.subject());
        var receipt = new CustomerMemoryForgetReceipt(conversationId, result.removedFactCount(), forgottenAt);
        try {
            idempotency.complete(tenantId, operator.subject(), OPERATION, key,
                    mapper.writeValueAsString(receipt), Instant.now());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot persist customer memory forget receipt", exception);
        }
        return receipt;
    }

    private CustomerMemoryForgetReceipt replay(IdempotencyRepository.StoredCommand stored, String hash) {
        if (!MessageDigest.isEqual(stored.requestHash().getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalStateException("idempotency key was reused with a different request");
        if (stored.responseJson() == null || stored.responseJson().isBlank())
            throw new IllegalStateException("idempotency command is already being processed");
        try { return mapper.readValue(stored.responseJson(), CustomerMemoryForgetReceipt.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("stored forget receipt is invalid", exception); }
    }

    private static String hash(UUID conversationId) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conversationId.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
