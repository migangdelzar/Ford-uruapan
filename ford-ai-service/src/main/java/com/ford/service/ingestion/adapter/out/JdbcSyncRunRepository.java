package com.ford.service.ingestion.adapter.out;

import com.ford.service.ingestion.application.port.out.SyncRunRepository;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.ingestion.domain.SyncResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcSyncRunRepository implements SyncRunRepository {
    private final JdbcClient jdbc;

    public JdbcSyncRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void start(UUID syncRunId, SyncRequest request) {
        jdbc.sql("""
                insert into sync_run (id, tenant_id, source_system, status, started_at)
                values (:id, :tenantId, 'ford-ingestion', 'RUNNING', :startedAt)
                """)
                .param("id", syncRunId)
                .param("tenantId", request.tenantId())
                .param("startedAt", request.requestedAt())
                .update();
    }

    @Override
    public void complete(UUID syncRunId, SyncResult result) {
        jdbc.sql("""
                update sync_run set status = :status, completed_at = :completedAt,
                       records_seen = :seen, records_applied = :applied,
                       records_quarantined = :quarantined, error_message = null
                where id = :id
                """)
                .param("id", syncRunId)
                .param("status", result.status().name())
                .param("completedAt", Instant.now())
                .param("seen", result.recordsSeen())
                .param("applied", result.recordsApplied())
                .param("quarantined", result.recordsQuarantined())
                .update();
    }

    @Override
    public void fail(UUID syncRunId, String message) {
        jdbc.sql("update sync_run set status = 'FAILED', completed_at = :completedAt, error_message = :message where id = :id")
                .param("id", syncRunId)
                .param("completedAt", Instant.now())
                .param("message", message)
                .update();
    }
}
