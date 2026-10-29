package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.domain.ShadowDraft;
import java.util.List;
import java.util.UUID;

public interface ShadowDraftRepository {
    void save(ShadowDraft draft);
    List<ShadowDraft> list(UUID tenantId, UUID conversationId, int limit);
    boolean review(UUID tenantId, UUID conversationId, UUID draftId, UUID actorId, String decision, String note);
}
