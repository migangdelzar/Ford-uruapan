package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.JdbcCustomerMemoryFactRepository;
import com.ford.service.conversations.domain.CustomerMemoryIdentity;
import com.ford.service.conversations.domain.CustomerShoppingFact;
import com.ford.service.conversations.domain.CustomerShoppingFactState;
import com.ford.service.conversations.domain.CustomerShoppingFactType;
import com.ford.service.database.FordPostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMemoryFactIntegrationTest {

    @Test
    void upsertsBySourceOrderKeepsDeclinesUntilReopenAndForgetBlocksOldFacts() {
        var postgres = new FordPostgresContainer();
        postgres.start();
        try {
            var jdbc = JdbcClient.create(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            createSchema(jdbc);
            var tenant = UUID.randomUUID();
            var identityId = UUID.randomUUID();
            var conversation = UUID.randomUUID();
            jdbc.sql("insert into customer_memory_identity(id, tenant_id, channel, created_at) values (:id,:tenant,'WHATSAPP',now())")
                    .param("id", identityId).param("tenant", tenant).update();
            var repository = new JdbcCustomerMemoryFactRepository(jdbc);
            var now = Instant.parse("2026-10-20T12:00:00Z");
            var first = fact(identityId, conversation, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    now, "Ford Bronco", CustomerShoppingFactState.ACTIVE, now.plusSeconds(90L * 86400));

            assertThat(repository.upsert(first).changed()).isTrue();
            assertThat(repository.upsert(first).changed()).isFalse();
            var newer = fact(identityId, conversation, UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    now.plusSeconds(1), "Ford Escape", CustomerShoppingFactState.ACTIVE,
                    now.plusSeconds(1 + 90L * 86400));
            assertThat(repository.upsert(newer).changed()).isTrue();
            assertThat(repository.upsert(first).changed()).isFalse();
            assertThat(repository.findCurrent(identityId, now.plusSeconds(2), 10))
                    .singleElement().satisfies(current -> assertThat(current.value()).isEqualTo("Ford Escape"));

            var decline = fact(identityId, conversation, UUID.randomUUID(), now.plusSeconds(3),
                    "Ford Escape", CustomerShoppingFactState.DECLINED, null);
            repository.upsert(decline);
            repository.reopen(identityId, CustomerShoppingFactType.VEHICLE_MODEL, "escape",
                    UUID.randomUUID(), now.plusSeconds(4));
            assertThat(repository.findCurrent(identityId, now.plusSeconds(5), 10)).isEmpty();

            var beforeForget = fact(identityId, conversation, UUID.randomUUID(), now.plusSeconds(5),
                    "Ford Escape", CustomerShoppingFactState.ACTIVE, now.plusSeconds(5 + 90L * 86400));
            repository.upsert(beforeForget);
            var forgetAuditId = UUID.randomUUID();
            var forgotten = repository.forget(identityId, now.plusSeconds(6), forgetAuditId, "operator-42");
            assertThat(forgotten.removedFactCount()).isEqualTo(1);
            assertThat(jdbc.sql("select actor_id from customer_memory_fact_event where id=:id")
                    .param("id", forgetAuditId).query(String.class).single()).isEqualTo("operator-42");
            assertThat(repository.upsert(beforeForget).changed()).isFalse();
            var postForget = fact(identityId, conversation, UUID.randomUUID(), now.plusSeconds(7),
                    "Ford Bronco", CustomerShoppingFactState.ACTIVE, now.plusSeconds(7 + 90L * 86400));
            assertThat(repository.upsert(postForget).changed()).isTrue();
        } finally {
            postgres.stop();
        }
    }

    private static CustomerShoppingFact fact(UUID identityId, UUID conversationId, UUID receiptId,
                                             Instant receivedAt, String value,
                                             CustomerShoppingFactState state, Instant expiresAt) {
        return new CustomerShoppingFact(identityId, CustomerShoppingFactType.VEHICLE_MODEL, "escape", value,
                state, conversationId, receiptId, receivedAt, 1, receivedAt, receivedAt, expiresAt, 0);
    }

    private static void createSchema(JdbcClient jdbc) {
        jdbc.sql("create table customer_memory_identity(id uuid primary key, tenant_id uuid not null, channel varchar(30) not null, facts_forget_before timestamptz, created_at timestamptz not null)").update();
        jdbc.sql("""
                create table customer_memory_fact (
                  customer_identity_id uuid not null references customer_memory_identity(id) on delete cascade,
                  fact_type varchar(40) not null, subject_key varchar(120) not null, fact_value varchar(160) not null,
                  state varchar(20) not null, source_conversation_id uuid not null, source_receipt_id uuid not null,
                  source_received_at timestamptz not null, source_version integer not null, stated_at timestamptz not null,
                  updated_at timestamptz not null, expires_at timestamptz, version bigint not null default 0,
                  primary key(customer_identity_id, fact_type, subject_key),
                  unique(customer_identity_id, source_receipt_id, fact_type, subject_key))
                """).update();
        jdbc.sql("""
                create table customer_memory_fact_event (
                  id uuid primary key, customer_identity_id uuid not null references customer_memory_identity(id) on delete cascade,
                  fact_type varchar(40) not null, subject_key varchar(120) not null, prior_state varchar(20),
                  new_state varchar(20) not null, source_receipt_id uuid not null, source_type varchar(30) not null,
                   occurred_at timestamptz not null, actor_id varchar(200),
                  unique(customer_identity_id, source_receipt_id, fact_type, subject_key, source_type))
                """).update();
    }
}
