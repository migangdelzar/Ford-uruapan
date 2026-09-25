package com.ford.service.ai.adapter.out;

import com.ford.service.ai.application.SystemPromptSnapshot;
import com.ford.service.ai.application.port.out.SystemPromptRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcSystemPromptRepository implements SystemPromptRepository {

    private final JdbcClient jdbc;

    public JdbcSystemPromptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SystemPromptSnapshot> find() {
        return jdbc.sql("""
                select content, version, updated_by, updated_at
                from ai_system_prompt
                where id = 1
                """).query((rs, rowNum) -> new SystemPromptSnapshot(
                rs.getString("content"),
                rs.getLong("version"),
                rs.getString("updated_by"),
                rs.getTimestamp("updated_at").toInstant())).optional();
    }

    @Override
    public Optional<SystemPromptSnapshot> replace(String content, long expectedVersion, String updatedBy) {
        var now = Timestamp.from(Instant.now());
        if (expectedVersion == 0) {
            var inserted = jdbc.sql("""
                    insert into ai_system_prompt (id, content, version, updated_by, updated_at)
                    values (1, :content, 1, :updatedBy, :updatedAt)
                    on conflict (id) do nothing
                    """)
                    .param("content", content)
                    .param("updatedBy", updatedBy)
                    .param("updatedAt", now)
                    .update();
            if (inserted == 1) {
                return find();
            }
        }

        var updated = jdbc.sql("""
                update ai_system_prompt
                set content = :content, version = version + 1,
                    updated_by = :updatedBy, updated_at = :updatedAt
                where id = 1 and version = :expectedVersion
                """)
                .param("content", content)
                .param("expectedVersion", expectedVersion)
                .param("updatedBy", updatedBy)
                .param("updatedAt", now)
                .update();
        return updated == 1 ? find() : Optional.empty();
    }
}
