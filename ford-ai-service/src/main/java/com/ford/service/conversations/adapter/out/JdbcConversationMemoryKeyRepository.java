package com.ford.service.conversations.adapter.out;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.port.out.ConversationMemoryKeyRepository;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcConversationMemoryKeyRepository implements ConversationMemoryKeyRepository {

    private final JdbcClient jdbc;

    public JdbcConversationMemoryKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ConversationMemoryKey> find(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                select tenant_id, channel, provider_account_id, participant_id, id
                  from conversation
                 where tenant_id = :tenantId and id = :conversationId
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query((rs, rowNum) -> new ConversationMemoryKey(
                        rs.getObject("tenant_id", UUID.class), Channel.valueOf(rs.getString("channel")),
                        rs.getString("provider_account_id"), rs.getString("participant_id"),
                        rs.getObject("id", UUID.class)))
                .optional();
    }
}
