package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.JdbcCustomerMemoryIdentityRepository;
import com.ford.service.database.FordPostgresContainer;
import com.ford.service.conversations.api.CustomerIdentityFingerprint;
import com.ford.service.conversations.application.port.out.CustomerMemoryIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMemoryIdentityIntegrationTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final byte[] KEY_V1 = "01234567890123456789012345678901".getBytes();
    private static final byte[] KEY_V2 = "abcdefghijklmnopqrstuvwxyzABCDEF".getBytes();

    @Test
    void linksDifferentProviderAccountConversationsAndResolvesRotatedFingerprint() {
        var database = new FordPostgresContainer();
        database.start();
        try {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = JdbcClient.create(dataSource);
        createSchema(jdbc);
        var repo = new JdbcCustomerMemoryIdentityRepository(jdbc);
        var fingerprint = new CustomerIdentityFingerprint();

        var v1 = fingerprint.fingerprint(TENANT, Channel.WHATSAPP, "523331733925", 1, KEY_V1);
        var identity = repo.resolveOrCreateIdentity(TENANT, Channel.WHATSAPP, List.of(
                new CustomerMemoryIdentityRepository.VersionedFingerprint(1, v1)));
        var firstConversation = UUID.randomUUID();
        var secondConversation = UUID.randomUUID();
        insertConversation(jdbc, firstConversation, "old-provider-account", "523331733925");
        insertConversation(jdbc, secondConversation, "new-provider-account", "523331733925");
        repo.linkConversation(TENANT, Channel.WHATSAPP, identity.id(), firstConversation);
        repo.linkConversation(TENANT, Channel.WHATSAPP, identity.id(), secondConversation);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> repo.linkConversation(TENANT, Channel.WHATSAPP,
                        repo.resolveOrCreateIdentity(TENANT, Channel.WHATSAPP, List.of(
                                new CustomerMemoryIdentityRepository.VersionedFingerprint(1,
                                        fingerprint.fingerprint(TENANT, Channel.WHATSAPP,
                                                "523331700000", 1, KEY_V1)))).id(), firstConversation));

        var v2 = fingerprint.fingerprint(TENANT, Channel.WHATSAPP, "523331733925", 2, KEY_V2);
        var rotatedIdentity = repo.resolveOrCreateIdentity(TENANT, Channel.WHATSAPP, List.of(
                new CustomerMemoryIdentityRepository.VersionedFingerprint(2, v2),
                new CustomerMemoryIdentityRepository.VersionedFingerprint(1, v1)));
        repo.linkConversation(TENANT, Channel.WHATSAPP, identity.id(), firstConversation);
        var otherTenantIdentity = repo.resolveOrCreateIdentity(UUID.randomUUID(), Channel.WHATSAPP,
                List.of(new CustomerMemoryIdentityRepository.VersionedFingerprint(1, v1)));

        assertThat(rotatedIdentity.id()).isEqualTo(identity.id());
        assertThat(otherTenantIdentity.id()).isNotEqualTo(identity.id());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> repo.resolveOrCreateIdentity(TENANT, Channel.WHATSAPP, List.of(
                        new CustomerMemoryIdentityRepository.VersionedFingerprint(2, v2),
                        new CustomerMemoryIdentityRepository.VersionedFingerprint(1,
                                fingerprint.fingerprint(TENANT, Channel.WHATSAPP, "523331700000", 1, KEY_V1)))));
        assertThat(jdbc.sql("select count(*) from customer_memory_identity_conversation where customer_identity_id=:id")
                .param("id", identity.id()).query(Long.class).single()).isEqualTo(2L);
        } finally {
            database.stop();
        }
    }

    private static void createSchema(JdbcClient jdbc) {
        jdbc.sql("drop table if exists customer_memory_identity_conversation").update();
        jdbc.sql("drop table if exists customer_memory_identity_fingerprint").update();
        jdbc.sql("drop table if exists customer_memory_identity").update();
        jdbc.sql("drop table if exists channel_binding").update();
        jdbc.sql("drop table if exists conversation").update();
        jdbc.sql("create table conversation(id uuid primary key, tenant_id uuid not null, channel varchar(30) not null, provider_account_id varchar(200) not null, participant_id varchar(200) not null)").update();
        jdbc.sql("create table channel_binding(id uuid primary key, tenant_id uuid not null, channel varchar(30) not null, provider_account_id varchar(200) not null)").update();
        jdbc.sql("create table customer_memory_identity(id uuid primary key, tenant_id uuid not null, channel varchar(30) not null, facts_forget_before timestamptz, created_at timestamptz not null, unique(id, tenant_id, channel))").update();
        jdbc.sql("create table customer_memory_identity_fingerprint(customer_identity_id uuid not null, tenant_id uuid not null, channel varchar(30) not null, key_version integer not null, participant_fingerprint char(64) not null, created_at timestamptz not null, primary key(tenant_id, channel, key_version, participant_fingerprint), foreign key(customer_identity_id, tenant_id, channel) references customer_memory_identity(id, tenant_id, channel) on delete cascade)").update();
        jdbc.sql("create table customer_memory_identity_conversation(tenant_id uuid not null, channel varchar(30) not null, customer_identity_id uuid not null, conversation_id uuid primary key references conversation(id) on delete cascade, linked_at timestamptz not null, foreign key(customer_identity_id, tenant_id, channel) references customer_memory_identity(id, tenant_id, channel) on delete cascade)").update();
    }

    private static void insertConversation(JdbcClient jdbc, UUID id, String providerAccount, String participant) {
        jdbc.sql("insert into channel_binding(id, tenant_id, channel, provider_account_id) values (:id,:tenant,'WHATSAPP',:provider) on conflict do nothing")
                .param("id", UUID.nameUUIDFromBytes(providerAccount.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("tenant", TENANT).param("provider", providerAccount).update();
        jdbc.sql("insert into conversation(id, tenant_id, channel, provider_account_id, participant_id) values (:id,:tenant,'WHATSAPP',:provider,:participant)")
                .param("id", id).param("tenant", TENANT).param("provider", providerAccount)
                .param("participant", participant).update();
    }
}
