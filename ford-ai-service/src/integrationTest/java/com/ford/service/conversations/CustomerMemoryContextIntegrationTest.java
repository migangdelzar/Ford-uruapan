package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.JdbcCustomerMemoryFactRepository;
import com.ford.service.conversations.adapter.out.JdbcCustomerMemoryIdentityRepository;
import com.ford.service.conversations.adapter.out.JdbcConversationMemoryKeyRepository;
import com.ford.service.conversations.adapter.out.JdbcIdempotencyRepository;
import com.ford.service.conversations.api.CustomerIdentityFingerprint;
import com.ford.service.conversations.application.CustomerMemoryFactExtractor;
import com.ford.service.conversations.application.CustomerMemoryProperties;
import com.ford.service.conversations.application.CustomerMemoryService;
import com.ford.service.conversations.application.CustomerMemoryForgetService;
import com.ford.service.conversations.application.CustomerMemoryUpdatePolicy;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.CustomerShoppingFact;
import com.ford.service.conversations.domain.CustomerShoppingFactState;
import com.ford.service.conversations.domain.CustomerShoppingFactType;
import com.ford.service.database.FordPostgresContainer;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMemoryContextIntegrationTest {

    private static final String HMAC_KEY = "01234567890123456789012345678901";

    @Test
    void linksDistinctAccountThreadsToOneTenantIdentityAndReturnsOnlyRelevantFacts() {
        var postgres = new FordPostgresContainer();
        postgres.start();
        try {
            var dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = JdbcClient.create(dataSource);
            applySchema(dataSource);
            var tenant = UUID.randomUUID();
            var firstConversation = UUID.randomUUID();
            var secondConversation = UUID.randomUUID();
            var now = Instant.now();
            var firstKey = new ConversationMemoryKey(tenant, Channel.WHATSAPP, "account-old",
                    "523331733925", firstConversation);
            var secondKey = new ConversationMemoryKey(tenant, Channel.WHATSAPP, "account-new",
                    "523331733925", secondConversation);
            insertConversation(jdbc, firstKey);
            insertConversation(jdbc, secondKey);

            var properties = new CustomerMemoryProperties();
            properties.setEnabled(true);
            properties.setHmacKey(HMAC_KEY);
            properties.setHmacKeyVersion(1);
            var identities = new JdbcCustomerMemoryIdentityRepository(jdbc);
            var facts = new JdbcCustomerMemoryFactRepository(jdbc);
            var service = new CustomerMemoryService(properties, new CustomerIdentityFingerprint(),
                    new CustomerMemoryFactExtractor(), new CustomerMemoryUpdatePolicy(), identities, facts);
            var identity = service.resolveIdentity(firstKey);
            facts.upsert(new CustomerShoppingFact(identity.id(), CustomerShoppingFactType.VEHICLE_MODEL,
                    "bronco", "Ford Bronco", CustomerShoppingFactState.DECLINED, firstConversation,
                    UUID.randomUUID(), now, 1, now, now, null, 0));
            facts.upsert(new CustomerShoppingFact(identity.id(), CustomerShoppingFactType.SHOPPING_BUDGET,
                    "budget", "850000", CustomerShoppingFactState.ACTIVE, firstConversation,
                    UUID.randomUUID(), now, 1, now, now, now.plusSeconds(90L * 86400), 0));

            var firstFacts = service.relevantFacts(firstKey, "¿Qué versiones de Bronco hay?", now.plusSeconds(1), 3);
            var secondFacts = service.relevantFacts(secondKey, "¿Qué versiones de Bronco hay?", now.plusSeconds(1), 3);
            var unrelatedFacts = service.relevantFacts(secondKey, "Hola", now.plusSeconds(1), 3);
            var otherTenant = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP, "account-new",
                    "523331733925", UUID.randomUUID());

            assertThat(firstFacts).singleElement().satisfies(fact -> {
                assertThat(fact.type()).isEqualTo(CustomerShoppingFactType.VEHICLE_MODEL);
                assertThat(fact.state()).isEqualTo(CustomerShoppingFactState.DECLINED);
            });
            assertThat(secondFacts).containsExactlyElementsOf(firstFacts);
            assertThat(unrelatedFacts).isEmpty();
            assertThat(service.relevantFacts(otherTenant, "¿Qué versiones de Bronco hay?", now, 3)).isEmpty();
            assertThat(jdbc.sql("select count(*) from customer_memory_identity_conversation where customer_identity_id=:id")
                    .param("id", identity.id()).query(Long.class).single()).isEqualTo(2L);
        } finally {
            postgres.stop();
        }
    }

    @Test
    void forgettingSharedFactsKeepsTranscriptAndAuditsOperatorActor() {
        var postgres = new FordPostgresContainer();
        postgres.start();
        try {
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = JdbcClient.create(dataSource);
            applySchema(dataSource);
            var tenant = UUID.randomUUID();
            var conversation = UUID.randomUUID();
            var key = new ConversationMemoryKey(tenant, Channel.WHATSAPP, "account-forget",
                    "523331733925", conversation);
            insertConversation(jdbc, key);
            var properties = new CustomerMemoryProperties();
            properties.setEnabled(true);
            properties.setHmacKey(HMAC_KEY);
            properties.setHmacKeyVersion(1);
            var identities = new JdbcCustomerMemoryIdentityRepository(jdbc);
            var facts = new JdbcCustomerMemoryFactRepository(jdbc);
            var service = new CustomerMemoryService(properties, new CustomerIdentityFingerprint(),
                    new CustomerMemoryFactExtractor(), new CustomerMemoryUpdatePolicy(), identities, facts);
            var identity = service.resolveIdentity(key);
            identities.linkConversation(tenant, Channel.WHATSAPP, identity.id(), conversation);
            var now = Instant.now();
            facts.upsert(new CustomerShoppingFact(identity.id(), CustomerShoppingFactType.VEHICLE_MODEL, "bronco",
                    "Ford Bronco", CustomerShoppingFactState.ACTIVE, conversation, UUID.randomUUID(), now,
                    1, now, now, now.plusSeconds(90L * 86400), 0));
            facts.upsert(new CustomerShoppingFact(identity.id(), CustomerShoppingFactType.PROMOTION_DECLINE, "promo-1",
                    "Promoción extendida", CustomerShoppingFactState.DECLINED, conversation, UUID.randomUUID(), now,
                    1, now, now, null, 0));
            jdbc.sql("insert into conversation_message(id, conversation_id, direction, provider_message_id, content, received_at) values (:id,:conversation,'INBOUND','provider-1','Me interesa Bronco',:at)")
                    .param("id", UUID.randomUUID()).param("conversation", conversation).param("at", java.sql.Timestamp.from(now)).update();
            var transcriptBefore = jdbc.sql("select count(*) from conversation_message where conversation_id=:id")
                    .param("id", conversation).query(Long.class).single();
            var auditId = UUID.randomUUID();

            var result = service.forget(tenant, Channel.WHATSAPP, conversation, now.plusSeconds(1), auditId, "operator-42");

            assertThat(result.removedFactCount()).isEqualTo(2);
            assertThat(facts.findCurrent(identity.id(), now.plusSeconds(2), 10)).isEmpty();
            assertThat(facts.upsert(new CustomerShoppingFact(identity.id(), CustomerShoppingFactType.VEHICLE_MODEL,
                    "bronco", "Ford Bronco", CustomerShoppingFactState.ACTIVE, conversation, UUID.randomUUID(), now,
                    1, now, now, now.plusSeconds(90L * 86400), 0)).changed()).isFalse();
            assertThat(jdbc.sql("select count(*) from conversation_message where conversation_id=:id")
                    .param("id", conversation).query(Long.class).single()).isEqualTo(transcriptBefore);
            assertThat(jdbc.sql("select actor_id from customer_memory_fact_event where id=:id")
                    .param("id", auditId).query(String.class).single()).isEqualTo("operator-42");
        } finally {
            postgres.stop();
        }
    }

    @Test
    void adminForgetIsIdempotentAcrossJdbcRepositoriesAndPreservesTranscript() {
        var postgres = new FordPostgresContainer();
        postgres.start();
        try {
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = JdbcClient.create(dataSource);
            applySchema(dataSource);
            var tenant = UUID.randomUUID();
            var conversation = UUID.randomUUID();
            var key = new ConversationMemoryKey(tenant, Channel.WHATSAPP, "account-admin-forget",
                    "523331733925", conversation);
            insertConversation(jdbc, key);
            var properties = new CustomerMemoryProperties();
            properties.setEnabled(true);
            properties.setHmacKey(HMAC_KEY);
            properties.setHmacKeyVersion(1);
            var identities = new JdbcCustomerMemoryIdentityRepository(jdbc);
            var facts = new JdbcCustomerMemoryFactRepository(jdbc);
            var memory = new CustomerMemoryService(properties, new CustomerIdentityFingerprint(),
                    new CustomerMemoryFactExtractor(), new CustomerMemoryUpdatePolicy(), identities, facts);
            var identity = memory.resolveIdentity(key);
            identities.linkConversation(tenant, Channel.WHATSAPP, identity.id(), conversation);
            var now = Instant.now();
            facts.upsert(new CustomerShoppingFact(identity.id(), CustomerShoppingFactType.VEHICLE_MODEL, "bronco",
                    "Ford Bronco", CustomerShoppingFactState.ACTIVE, conversation, UUID.randomUUID(), now,
                    1, now, now, now.plusSeconds(90L * 86400), 0));
            facts.upsert(new CustomerShoppingFact(identity.id(), CustomerShoppingFactType.PROMOTION_DECLINE, "promo-1",
                    "Promoción extendida", CustomerShoppingFactState.DECLINED, conversation, UUID.randomUUID(), now,
                    1, now, now, null, 0));
            jdbc.sql("insert into conversation_message(id, conversation_id, direction, provider_message_id, content, received_at) values (:id,:conversation,'INBOUND','admin-forget-message','Transcript stays',:at)")
                    .param("id", UUID.randomUUID()).param("conversation", conversation).param("at", java.sql.Timestamp.from(now)).update();
            var operator = new AuthenticatedOperator("admin-1", Set.of(), Set.of("ADMIN"), Set.of(tenant));
            var service = new CustomerMemoryForgetService(memory, new JdbcConversationMemoryKeyRepository(jdbc),
                    new JdbcIdempotencyRepository(jdbc), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

            var first = service.forget(operator, tenant, conversation, "admin-forget-key");
            var replay = service.forget(operator, tenant, conversation, "admin-forget-key");

            assertThat(first.removedFactCount()).isEqualTo(2);
            assertThat(replay).isEqualTo(first);
            assertThat(facts.findCurrent(identity.id(), now.plusSeconds(1), 10)).isEmpty();
            assertThat(jdbc.sql("select count(*) from conversation_message where conversation_id=:id")
                    .param("id", conversation).query(Long.class).single()).isEqualTo(1L);
            assertThat(jdbc.sql("select count(*) from operator_idempotency where tenant_id=:tenant and actor_id='admin-1' and operation='conversation.customer-memory.forget' and idempotency_key='admin-forget-key'")
                    .param("tenant", tenant).query(Long.class).single()).isEqualTo(1L);
        } finally {
            postgres.stop();
        }
    }

    private static void applySchema(DriverManagerDataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            var database = liquibase.database.DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                    new liquibase.database.jvm.JdbcConnection(connection));
            for (var change : new String[]{"001-platform.yaml", "002-channel-delivery.yaml", "009-operator-commands.yaml",
                    "003-channel-message.yaml", "006-meta-webhook-routing.yaml",
                    "007-conversation-memory.yaml", "028-customer-memory-profile.yaml"}) {
                new liquibase.Liquibase("db/changelog/changes/" + change,
                        new liquibase.resource.ClassLoaderResourceAccessor(), database)
                        .update(new liquibase.Contexts(), new liquibase.LabelExpression());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("test schema setup failed", exception);
        }
    }

    private static void insertConversation(JdbcClient jdbc, ConversationMemoryKey key) {
        jdbc.sql("insert into channel_binding(id, webhook_key_hash, tenant_id, channel, provider_account_id, secret_reference, created_at, updated_at) values (:id,:hash,:tenant,'WHATSAPP',:account,'test-secret',now(),now())")
                .param("id", UUID.randomUUID()).param("hash", UUID.randomUUID().toString())
                .param("tenant", key.tenantId()).param("account", key.providerAccountId()).update();
        jdbc.sql("insert into conversation(id, tenant_id, channel, provider_conversation_id, state, created_at, updated_at, provider_account_id, participant_id) values (:id,:tenant,'WHATSAPP',:providerConversation,'BOT_ACTIVE',now(),now(),:account,:participant)")
                .param("id", key.conversationId()).param("tenant", key.tenantId())
                .param("providerConversation", UUID.randomUUID().toString())
                .param("account", key.providerAccountId()).param("participant", key.participantId()).update();
    }
}
