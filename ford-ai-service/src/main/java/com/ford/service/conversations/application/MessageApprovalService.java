package com.ford.service.conversations.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.api.MessageApprovalActionRequest;
import com.ford.service.conversations.api.MessageApprovalActionResult;
import com.ford.service.conversations.api.MessageApprovalView;
import com.ford.service.conversations.application.port.out.IdempotencyRepository;
import com.ford.service.conversations.application.port.out.MessageApprovalEffects;
import com.ford.service.conversations.application.port.out.MessageApprovalRepository;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.conversations.domain.MessageApproval.Action;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Transactional advisor approval orchestration; send validation and effects are installed adapters. */
@Service
public class MessageApprovalService {
    private static final String OPERATION = "conversation.message-approval.action";
    private final MessageApprovalRepository repository;
    private final MessageApprovalEffects effects;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper json;
    private final Clock clock;

    public MessageApprovalService(MessageApprovalRepository repository, MessageApprovalEffects effects,
                                   IdempotencyRepository idempotency, ObjectMapper json, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.effects = Objects.requireNonNull(effects);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public List<MessageApprovalView> list(AuthenticatedOperator operator, UUID tenantId, UUID conversationId) {
        requireAccess(operator, tenantId, false);
        Objects.requireNonNull(conversationId, "conversation is required");
        return repository.list(tenantId, conversationId).stream().map(approval -> {
            requireScope(approval, tenantId, conversationId);
            var allowed = approval.allowedActions().stream().filter(action -> canWrite(operator)
                    && (action != Action.TAKEOVER || canHandoff(operator))).collect(Collectors.toUnmodifiableSet());
            return new MessageApprovalView(approval.id(), tenantId, conversationId, approval.version(),
                    approval.conversationVersion(), approval.disposition().reason().name(),
                    approval.disposition().route().name(), approval.draftText(), allowed);
        }).toList();
    }

    @Transactional
    public MessageApprovalActionResult act(AuthenticatedOperator operator, UUID tenantId, UUID conversationId,
                                           UUID approvalId, long expectedConversationVersion, String key,
                                           MessageApprovalActionRequest request) {
        requireAccess(operator, tenantId, true);
        Objects.requireNonNull(conversationId, "conversation is required");
        Objects.requireNonNull(approvalId, "approval is required");
        Objects.requireNonNull(request, "approval action is required");
        if (request.action() == null || request.expectedApprovalVersion() < 0 || expectedConversationVersion < 0
                || (request.exactText() != null && request.exactText().length() > 2000)
                || key == null || !key.matches("[A-Za-z0-9._:-]{1,240}")) {
            throw new IllegalArgumentException("valid action, versions and Idempotency-Key are required");
        }
        if (request.action() == Action.TAKEOVER && !canHandoff(operator)) throw new SecurityException("handoff permission is required");
        var hash = requestHash(tenantId, conversationId, approvalId, expectedConversationVersion, request);
        var existing = idempotency.find(tenantId, operator.subject(), OPERATION, key);
        if (existing.isPresent()) return replay(existing.get(), hash, approvalId);
        if (!idempotency.reserve(tenantId, operator.subject(), OPERATION, key, hash, clock.instant())) {
            return replay(idempotency.find(tenantId, operator.subject(), OPERATION, key)
                    .orElseThrow(() -> new IllegalStateException("approval command is being processed")), hash, approvalId);
        }
        var target = repository.lockConversation(tenantId, conversationId)
                .orElseThrow(() -> new NoSuchElementException("conversation not found"));
        var approval = repository.findForUpdate(tenantId, conversationId, approvalId)
                .orElseThrow(() -> new NoSuchElementException("message approval not found"));
        requireScope(approval, tenantId, conversationId);
        if (!approval.id().equals(approvalId)) throw new SecurityException("approval identity mismatch");
        if (target.version() != expectedConversationVersion || !approval.sourceReceiptId().equals(target.latestReceiptId())) {
            throw new IllegalStateException("conversation or source receipt changed; reload current approval");
        }
        var permittedState = request.action() == Action.TAKEOVER
                ? Set.of("BOT_ACTIVE", "LEAD_CAPTURE", "HUMAN_PENDING") : Set.of("BOT_ACTIVE", "LEAD_CAPTURE");
        if (!permittedState.contains(target.state())) throw new IllegalStateException("conversation no longer accepts this approval action");
        var command = approval.prepare(request.action(), request.expectedApprovalVersion(), expectedConversationVersion, request.exactText());
        var effectKey = "message-approval:" + hash;
        final MessageApprovalActionResult result;
        if (request.action().sending()) {
            var receipt = effects.validateAndQueue(operator, approval, command.exactText(), effectKey);
            if (receipt == null || !receipt.accepted() || receipt.internalNote() || !"QUEUED".equals(receipt.deliveryStatus())
                    || !conversationId.equals(receipt.conversationId()) || receipt.messageId() == null) {
                throw new IllegalStateException("approved customer message was not queued");
            }
            result = new MessageApprovalActionResult(approvalId, "QUEUED", receipt.conversationVersion(), receipt.messageId());
        } else if (request.action() == Action.TAKEOVER) {
            var receipt = Objects.requireNonNull(effects.takeover(operator, approval, effectKey), "takeover receipt is required");
            result = new MessageApprovalActionResult(approvalId, receipt.status(), receipt.conversationVersion(), null);
        } else {
            result = new MessageApprovalActionResult(approvalId, "REJECTED", target.version(), null);
        }
        var completed = approval.complete(command, actorId(operator), result.conversationVersion(), result.messageId(), clock.instant());
        if (!repository.replace(approval, completed)) throw new IllegalStateException("approval changed; reload before retrying");
        repository.recordAudit(tenantId, approvalId, actorId(operator), new MessageApprovalRepository.AuditRecord(
                request.action().name(), completed.version(), completed.sourceReceiptId(), completed.outboundMessageId()), clock.instant());
        try {
            idempotency.complete(tenantId, operator.subject(), OPERATION, key, json.writeValueAsString(result), clock.instant());
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("approval receipt could not be persisted");
        }
        return result;
    }

    private MessageApprovalActionResult replay(IdempotencyRepository.StoredCommand stored, String hash, UUID approvalId) {
        if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8), stored.requestHash().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("Idempotency-Key was reused for a different approval command");
        }
        if (stored.responseJson() == null || stored.responseJson().isBlank()) throw new IllegalStateException("approval command is being processed");
        try {
            var result = json.readValue(stored.responseJson(), MessageApprovalActionResult.class);
            if (result == null || !approvalId.equals(result.approvalId())) throw new IllegalStateException("stored approval receipt is invalid");
            return result;
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("stored approval receipt is invalid");
        }
    }

    private String requestHash(UUID tenant, UUID conversation, UUID approval, long version, MessageApprovalActionRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(
                    new HashInput(tenant, conversation, approval, version, request))));
        } catch (JsonProcessingException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("approval command hash could not be generated");
        }
    }

    private static void requireScope(MessageApproval approval, UUID tenant, UUID conversation) {
        if (!tenant.equals(approval.tenantId()) || !conversation.equals(approval.conversationId())) {
            throw new SecurityException("approval does not belong to this conversation");
        }
    }
    private static void requireAccess(AuthenticatedOperator operator, UUID tenant, boolean write) {
        if (operator == null || !operator.canAccess(tenant) || (write ? !canWrite(operator)
                : !canWrite(operator) && !operator.hasAuthority("SCOPE_operator:read"))) {
            throw new SecurityException("operator is not authorized for message approval");
        }
    }
    private static boolean canWrite(AuthenticatedOperator operator) {
        return operator.hasRole("ADMIN") || operator.hasAuthority("SCOPE_operator:write");
    }
    private static boolean canHandoff(AuthenticatedOperator operator) {
        return operator.hasRole("ADMIN") || operator.hasAuthority("SCOPE_handoff:manage");
    }
    private static UUID actorId(AuthenticatedOperator operator) {
        try { return UUID.fromString(operator.subject()); }
        catch (IllegalArgumentException textSubject) { return UUID.nameUUIDFromBytes(operator.subject().getBytes(StandardCharsets.UTF_8)); }
    }
    private record HashInput(UUID tenantId, UUID conversationId, UUID approvalId, long conversationVersion,
                             MessageApprovalActionRequest request) { }
}
