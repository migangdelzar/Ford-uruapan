package com.ford.service.conversations.application;

import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.conversations.application.port.out.ConversationReviewPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ConversationReviewService {
    private final ConversationReviewPort reviews;
    private final DecisionTraceRepository traces;

    public ConversationReviewService(ConversationReviewPort reviews) {
        this(reviews, emptyTraceRepository());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationReviewService(ConversationReviewPort reviews, DecisionTraceRepository traces) {
        this.reviews = Objects.requireNonNull(reviews);
        this.traces = Objects.requireNonNull(traces);
    }

    @Transactional(readOnly = true)
    public List<ReviewItem> list(UUID tenantId, String status, int limit) {
        if (!List.of("PENDING", "IN_PROGRESS", "RESOLVED").contains(status)) {
            throw new IllegalArgumentException("unsupported conversation review status");
        }
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return reviews.list(tenantId, status, limit);
    }

    @Transactional
    public void resolve(UUID tenantId, UUID reviewId, UUID operatorId) {
        if (!reviews.resolve(tenantId, reviewId, operatorId, Instant.now())) {
            throw new java.util.NoSuchElementException("pending conversation review not found");
        }
    }

    @Transactional(readOnly = true)
    public List<DecisionTraceEvent> trace(UUID tenantId, UUID conversationId, UUID receiptId) {
        return traces.list(Objects.requireNonNull(tenantId), Objects.requireNonNull(conversationId),
                Objects.requireNonNull(receiptId));
    }

    private static DecisionTraceRepository emptyTraceRepository() {
        return new DecisionTraceRepository() {
            @Override public void append(UUID tenantId, UUID conversationId, UUID receiptId,
                                         List<DecisionTraceEvent> events) { }
            @Override public List<DecisionTraceEvent> list(UUID tenantId, UUID conversationId, UUID receiptId) {
                return List.of();
            }
        };
    }


    public record ReviewItem(UUID id, UUID tenantId, UUID conversationId, UUID receiptId,
                             String reasonCode, String status, Instant createdAt, Instant updatedAt) { }
}
