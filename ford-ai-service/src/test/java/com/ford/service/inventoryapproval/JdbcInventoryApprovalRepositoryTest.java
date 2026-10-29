package com.ford.service.inventoryapproval;

import com.ford.service.inventoryapproval.adapter.out.JdbcInventoryApprovalRepository;
import com.ford.service.inventoryapproval.domain.InventoryApproval;
import com.ford.service.inventoryapproval.domain.InventoryApprovalStatus;
import com.ford.service.inventoryapproval.domain.InventoryOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcInventoryApprovalRepositoryTest {
    private JdbcClient jdbc;
    private JdbcInventoryApprovalRepository repository;
    private UUID tenantId;
    private UUID conversationId;
    private final Instant now = Instant.parse("2026-10-26T12:00:00Z");

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        Class.forName("org.h2.Driver");
        var source = new DriverManagerDataSource("jdbc:h2:mem:inventory_approval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = JdbcClient.create(source);
        jdbc.sql("drop table if exists inventory_approval_audit").update();
        jdbc.sql("drop table if exists inventory_approval").update();
        jdbc.sql("drop table if exists conversation").update();
        jdbc.sql("create table conversation (id uuid primary key, tenant_id uuid not null, unique(tenant_id, id))").update();
        jdbc.sql("""
                create table inventory_approval (
                    id uuid primary key, tenant_id uuid not null, conversation_id uuid not null,
                    model varchar(100) not null, model_year integer, trim varchar(160),
                    status varchar(32) not null, outcome varchar(32), verified_count integer,
                    verified_at timestamp with time zone, verification_expires_at timestamp with time zone,
                    draft_text text, approved_text text, verified_by uuid, approved_by uuid, rejected_by uuid,
                    idempotency_key varchar(240) not null, version bigint not null,
                    created_at timestamp with time zone not null, updated_at timestamp with time zone not null,
                    unique (tenant_id, id), unique (tenant_id, idempotency_key),
                    foreign key (tenant_id, conversation_id) references conversation(tenant_id, id)
                )
                """).update();
        jdbc.sql("""
                create table inventory_approval_audit (
                    id uuid primary key, tenant_id uuid not null, approval_id uuid not null,
                    actor_id uuid not null, action varchar(32) not null,
                    occurred_at timestamp with time zone not null, approval_version bigint not null,
                    foreign key (tenant_id, approval_id) references inventory_approval(tenant_id, id)
                )
                """).update();
        tenantId = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        jdbc.sql("insert into conversation(id, tenant_id) values (:id, :tenant)")
                .param("id", conversationId).param("tenant", tenantId).update();
        repository = new JdbcInventoryApprovalRepository(jdbc);
    }

    @Test
    void isolatesReadsByTenantAndReturnsExistingIdempotentRequest() {
        var pending = pending("request-1");
        var saved = repository.savePending(pending);
        var retry = InventoryApproval.pending(UUID.randomUUID(), tenantId, conversationId,
                "Ranger", 2027, "XLT", "request-1", now.plusSeconds(1));

        assertThat(repository.savePending(retry).id()).isEqualTo(saved.id());
        assertThat(repository.find(UUID.randomUUID(), saved.id())).isEmpty();
        assertThat(repository.find(tenantId, saved.id())).contains(saved);
        assertThat(repository.list(UUID.randomUUID(), null, 20)).isEmpty();
    }

    @Test
    void transitionsUseTenantStatusAndExpectedVersionAndAuditContainsOnlyMetadata() {
        var pending = repository.savePending(pending("request-2"));
        var draft = pending.verify(UUID.randomUUID(), InventoryOutcome.AVAILABLE, 1, now,
                now.plusSeconds(300), "Unidades confirmadas.");

        var transitioned = repository.transition(tenantId, pending.id(), 0,
                InventoryApprovalStatus.PENDING, draft).orElseThrow();
        assertThat(transitioned.status()).isEqualTo(InventoryApprovalStatus.DRAFT_READY);
        assertThat(repository.transition(tenantId, pending.id(), 0,
                InventoryApprovalStatus.PENDING, draft)).isEmpty();
        var otherTenant = UUID.randomUUID();
        var otherDraft = InventoryApproval.pending(pending.id(), otherTenant, conversationId,
                        "Ranger", 2027, "XLT", "other-key", now)
                .verify(UUID.randomUUID(), InventoryOutcome.AVAILABLE, 1, now,
                        now.plusSeconds(300), "Unidades confirmadas.");
        assertThat(repository.transition(otherTenant, pending.id(), 0,
                InventoryApprovalStatus.PENDING, otherDraft)).isEmpty();

        repository.recordAudit(tenantId, pending.id(), UUID.randomUUID(), "VERIFIED", now, 1);
        assertThat(jdbc.sql("select count(*) from inventory_approval_audit where tenant_id = :tenant and approval_id = :id")
                .param("tenant", tenantId).param("id", pending.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from information_schema.columns where table_name = 'INVENTORY_APPROVAL_AUDIT' "
                        + "and column_name in ('DRAFT_TEXT', 'APPROVED_TEXT')")
                .query(Integer.class).single()).isZero();
    }

    private InventoryApproval pending(String key) {
        return InventoryApproval.pending(UUID.randomUUID(), tenantId, conversationId,
                "Ranger", 2027, "XLT", key, now);
    }
}
