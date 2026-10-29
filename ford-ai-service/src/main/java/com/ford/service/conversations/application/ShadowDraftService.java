package com.ford.service.conversations.application;

import com.ford.service.conversations.application.port.out.ShadowDraftRepository;
import com.ford.service.conversations.application.port.out.ShadowDraftRecorder;
import com.ford.service.conversations.domain.ShadowDraft;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ShadowDraftService implements ShadowDraftRecorder {
    private static final int MAX_DRAFT_CHARS = 2000;
    private static final int MAX_NOTE_CHARS = 500;
    private static final Duration RETENTION = Duration.ofDays(7);
    private final ShadowDraftRepository repository;

    public ShadowDraftService(ShadowDraftRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public void record(UUID tenantId, UUID conversationId, UUID receiptId,
                       String inboundProviderMessageId, String text, Instant createdAt) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(receiptId);
        Objects.requireNonNull(inboundProviderMessageId);
        Objects.requireNonNull(createdAt);
        var bounded = Objects.requireNonNullElse(text, "");
        if (bounded.length() > MAX_DRAFT_CHARS) bounded = bounded.substring(0, MAX_DRAFT_CHARS);
        var key = tenantId + ":" + conversationId + ":" + inboundProviderMessageId;
        repository.save(new ShadowDraft(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                tenantId, conversationId, receiptId, inboundProviderMessageId, bounded,
                createdAt, createdAt.plus(RETENTION), "PENDING"));
    }

    public List<ShadowDraft> list(AuthenticatedOperator operator, UUID tenantId, UUID conversationId) {
        requireAccess(operator, tenantId, "SCOPE_operator:read");
        return repository.list(tenantId, conversationId, 50);
    }

    public void review(AuthenticatedOperator operator, UUID tenantId, UUID conversationId, UUID draftId,
                       String decision, String note) {
        requireAccess(operator, tenantId, "SCOPE_operator:write");
        if (!"ACCEPTED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new IllegalArgumentException("decision must be ACCEPTED or REJECTED");
        }
        if (note != null && note.length() > MAX_NOTE_CHARS) {
            throw new IllegalArgumentException("review note is too long");
        }
        var actorId = UUID.nameUUIDFromBytes(operator.subject().getBytes(StandardCharsets.UTF_8));
        if (!repository.review(tenantId, conversationId, draftId, actorId, decision, note == null ? "" : note)) {
            throw new IllegalArgumentException("draft not found, expired, or already reviewed");
        }
    }

    private static void requireAccess(AuthenticatedOperator operator, UUID tenantId, String authority) {
        if (operator == null || !operator.canAccess(tenantId)
                || (!operator.hasAuthority(authority) && !operator.hasRole("ADMIN"))) {
            throw new SecurityException("operator is not authorized for shadow review");
        }
    }
}
