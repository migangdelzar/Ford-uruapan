package com.ford.service.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.handoff.adapter.out.JdbcHandoffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcHandoffRepositoryDigestTest {
    private JdbcClient jdbc;
    private JdbcHandoffRepository repository;
    private UUID tenantId;

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        Class.forName("org.h2.Driver");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:handoff_digest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("drop table if exists handoff_ticket").update();
        jdbc.sql("""
                create table handoff_ticket (
                    id uuid primary key, tenant_id uuid not null, conversation_id uuid not null, lead_id uuid,
                    status varchar(32) not null, priority varchar(16) not null, assigned_operator_id uuid,
                    reason varchar(500) not null, idempotency_key varchar(240), version bigint not null,
                    created_at timestamp with time zone not null, claimed_at timestamp with time zone,
                    closed_at timestamp with time zone, packet varchar(2000)
                )
                """).update();
        tenantId = UUID.randomUUID();
        repository = new JdbcHandoffRepository(jdbc, new ObjectMapper());
    }

    @Test
    void readsOnlyTheSelectedTenantAndHalfOpenTimeWindowInCreatedOrder() {
        var start = Instant.parse("2026-10-17T20:00:00Z");
        var end = Instant.parse("2026-10-19T15:00:00Z");
        var later = insert(tenantId, Instant.parse("2026-10-19T14:00:00Z"));
        var earlier = insert(tenantId, Instant.parse("2026-10-18T22:00:00Z"));
        var atStart = insert(tenantId, start);
        insert(tenantId, end);
        insert(UUID.randomUUID(), Instant.parse("2026-10-19T10:00:00Z"));

        var result = repository.listCreatedBetween(tenantId, start, end);

        assertThat(result).extracting(ticket -> ticket.id()).containsExactly(atStart, earlier, later);
    }

    private UUID insert(UUID tenant, Instant createdAt) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                insert into handoff_ticket
                    (id, tenant_id, conversation_id, status, priority, reason, version, created_at, packet)
                values (:id, :tenantId, :conversationId, 'HUMAN_PENDING', 'NORMAL', 'CUSTOMER_REQUEST', 0,
                        :createdAt, '{}')
                """)
                .param("id", id).param("tenantId", tenant).param("conversationId", UUID.randomUUID())
                .param("createdAt", java.sql.Timestamp.from(createdAt)).update();
        return id;
    }
}
