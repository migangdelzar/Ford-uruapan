package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.application.ConversationReviewService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

@NamedInterface
public interface ConversationReviewPort {
    List<ConversationReviewService.ReviewItem> list(UUID tenantId, String status, int limit);

    boolean resolve(UUID tenantId, UUID reviewId, UUID operatorId, Instant reviewedAt);
}
