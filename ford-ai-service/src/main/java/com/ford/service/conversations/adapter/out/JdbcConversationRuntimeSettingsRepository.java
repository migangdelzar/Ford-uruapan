package com.ford.service.conversations.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.application.ConversationRuntimeSettings;
import com.ford.service.conversations.application.ConversationRuntimeSettingsSnapshot;
import com.ford.service.conversations.application.port.out.ConversationRuntimeSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcConversationRuntimeSettingsRepository implements ConversationRuntimeSettingsRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcConversationRuntimeSettingsRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ConversationRuntimeSettingsSnapshot> find(UUID tenantId) {
        return jdbc.sql("""
                select tenant_id, config_json::text as config_json, revision, updated_by, updated_at
                  from tenant_runtime_configuration
                 where tenant_id = :tenantId
                """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new ConversationRuntimeSettingsSnapshot(
                        rs.getObject("tenant_id", UUID.class), rs.getLong("revision"),
                        readSettings(rs.getString("config_json")), rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("updated_by", UUID.class)))
                .optional();
    }

    @Override
    public ConversationRuntimeSettingsSnapshot update(UUID tenantId, UUID actorId, long expectedRevision,
                                                     ConversationRuntimeSettings settings) {
        var updatedAt = Instant.now();
        var result = jdbc.sql("""
                insert into tenant_runtime_configuration (tenant_id, config_json, revision, updated_by, updated_at)
                select :tenantId, cast(:configJson as jsonb), :nextRevision, :actorId, :updatedAt
                 where :expectedRevision = 0
                on conflict (tenant_id) do update
                   set config_json = excluded.config_json,
                       revision = tenant_runtime_configuration.revision + 1,
                       updated_by = excluded.updated_by,
                       updated_at = excluded.updated_at
                 where tenant_runtime_configuration.revision = :expectedRevision
                returning tenant_id, config_json::text as config_json, revision, updated_by, updated_at
                """)
                .param("tenantId", tenantId)
                .param("configJson", writeSettings(settings))
                .param("nextRevision", expectedRevision + 1)
                .param("actorId", actorId)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("expectedRevision", expectedRevision)
                .query((rs, rowNum) -> new ConversationRuntimeSettingsSnapshot(
                        rs.getObject("tenant_id", UUID.class), rs.getLong("revision"),
                        readSettings(rs.getString("config_json")), rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("updated_by", UUID.class)))
                .optional();
        return result.orElseThrow(() -> new IllegalStateException("runtime settings revision is stale"));
    }

    private String writeSettings(ConversationRuntimeSettings settings) {
        try {
            return objectMapper.writeValueAsString(new StoredSettings(1, settings));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize runtime settings", exception);
        }
    }

    private ConversationRuntimeSettings readSettings(String json) {
        try {
            var stored = objectMapper.readValue(json, StoredSettings.class);
            if (stored.schemaVersion() != 1 || stored.settings() == null) {
                throw new IllegalArgumentException("unsupported runtime settings schema version");
            }
            return stored.settings();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("stored runtime settings are invalid", exception);
        }
    }

    private record StoredSettings(int schemaVersion, ConversationRuntimeSettings settings) { }
}
