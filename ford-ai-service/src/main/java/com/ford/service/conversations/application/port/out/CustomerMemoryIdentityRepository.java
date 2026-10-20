package com.ford.service.conversations.application.port.out;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.domain.CustomerMemoryIdentity;

import java.util.List;
import java.util.UUID;

public interface CustomerMemoryIdentityRepository {
    CustomerMemoryIdentity resolveOrCreateIdentity(UUID tenantId, Channel channel,
                                                    List<VersionedFingerprint> fingerprints);

    void linkConversation(UUID tenantId, Channel channel, UUID identityId, UUID conversationId);

    CustomerMemoryIdentity findIdentityByConversation(UUID tenantId, Channel channel, UUID conversationId);

    List<ConversationIdentitySource> nextConversationBatch(UUID afterConversationId, int limit);

    record VersionedFingerprint(int keyVersion, String fingerprint) { }

    record ConversationIdentitySource(UUID conversationId, UUID tenantId, Channel channel,
                                      String participantId) { }
}
