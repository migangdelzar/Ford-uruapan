package com.ford.service.ai.adapter.out.persistence;

import com.ford.service.ai.application.AiTestSessionStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class JdbcAiTestSessionStore implements AiTestSessionStore {

    private final JdbcClient jdbc;

    public JdbcAiTestSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredMessage> recentMessages(UUID tenantId, String operatorSubject, UUID conversationId, int limit) {
        var owner = jdbc.sql("select tenant_id, operator_subject from ai_test_session where id = :id")
                .param("id", conversationId)
                .query((rs, row) -> new Owner(rs.getObject("tenant_id", UUID.class),
                        rs.getString("operator_subject")))
                .optional();
        if (owner.isEmpty()) {
            return List.of();
        }
        requireOwner(owner.get(), tenantId, operatorSubject);
        var rows = jdbc.sql("""
                        select role, content from (
                            select role, content, created_at, id
                            from ai_test_message where session_id = :id
                            order by created_at desc, id desc limit :limit
                        ) recent order by created_at asc, id asc
                        """)
                .param("id", conversationId)
                .param("limit", limit)
                .query((rs, row) -> new StoredMessage(rs.getString("role"), rs.getString("content")))
                .list();
        return List.copyOf(rows);
    }

    @Override
    @Transactional
    public void saveTurn(UUID tenantId, String operatorSubject, UUID conversationId, String userMessage,
                         String assistantMessage) {
        jdbc.sql("""
                        insert into ai_test_session (id, tenant_id, operator_subject)
                        values (:id, :tenant, :subject) on conflict (id) do nothing
                        """)
                .param("id", conversationId)
                .param("tenant", tenantId)
                .param("subject", operatorSubject)
                .update();
        var owner = jdbc.sql("select tenant_id, operator_subject from ai_test_session where id = :id for update")
                .param("id", conversationId)
                .query((rs, row) -> new Owner(rs.getObject("tenant_id", UUID.class),
                        rs.getString("operator_subject")))
                .single();
        requireOwner(owner, tenantId, operatorSubject);
        insertMessage(conversationId, "user", userMessage);
        insertMessage(conversationId, "assistant", assistantMessage);
        jdbc.sql("update ai_test_session set updated_at = current_timestamp where id = :id")
                .param("id", conversationId).update();
        jdbc.sql("""
                        delete from ai_test_message where session_id = :id and id not in (
                            select id from ai_test_message where session_id = :id order by id desc limit 40
                        )
                        """)
                .param("id", conversationId).update();
    }

    private void insertMessage(UUID conversationId, String role, String content) {
        jdbc.sql("insert into ai_test_message (session_id, role, content) values (:id, :role, :content)")
                .param("id", conversationId).param("role", role).param("content", content).update();
    }

    private static void requireOwner(Owner owner, UUID tenantId, String subject) {
        if (!owner.tenantId().equals(tenantId) || !owner.subject().equals(subject)) {
            throw new AccessDeniedException("AI test session is not owned by this operator");
        }
    }

    private record Owner(UUID tenantId, String subject) { }
}
