package com.ford.service.conversations.adapter.out;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.port.out.CustomerMemoryIdentityRepository;
import com.ford.service.conversations.domain.CustomerMemoryIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcCustomerMemoryIdentityRepository implements CustomerMemoryIdentityRepository {

    private final JdbcClient jdbc;

    public JdbcCustomerMemoryIdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public CustomerMemoryIdentity resolveOrCreateIdentity(UUID tenantId, Channel channel,
                                                           List<VersionedFingerprint> fingerprints) {
        if (channel != Channel.WHATSAPP) {
            throw new IllegalArgumentException("customer memory identity currently supports WhatsApp only");
        }
        if (fingerprints == null || fingerprints.isEmpty()) {
            throw new IllegalArgumentException("at least one fingerprint is required");
        }
        if (tenantId == null || fingerprints.stream().anyMatch(value -> value == null
                || value.keyVersion() < 1 || value.fingerprint() == null
                || !value.fingerprint().matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("tenant and valid versioned fingerprints are required");
        }
        if (fingerprints.size() > 2) throw new IllegalArgumentException("at most current and previous fingerprints are allowed");
        for (var candidate : fingerprints) {
            var linked = jdbc.sql("""
                    select exists(
                        select 1 from customer_memory_identity_fingerprint old
                        join customer_memory_identity_fingerprint candidate
                          on candidate.tenant_id=old.tenant_id and candidate.channel=old.channel
                        where old.tenant_id=:tenantId and old.channel=:channel
                          and old.key_version=:candidateVersion
                          and old.participant_fingerprint=:candidateFingerprint
                          and candidate.key_version=:otherVersion
                          and candidate.participant_fingerprint=:otherFingerprint
                          and old.customer_identity_id <> candidate.customer_identity_id)
                    """)
                    .param("tenantId", tenantId).param("channel", channel.name())
                    .param("candidateVersion", candidate.keyVersion())
                    .param("candidateFingerprint", candidate.fingerprint())
                    .param("otherVersion", fingerprints.get(0).keyVersion())
                    .param("otherFingerprint", fingerprints.get(0).fingerprint())
                    .query(Boolean.class).single();
            if (linked) throw new IllegalStateException("fingerprints resolve to conflicting customer identities");
        }
        var distinctVersions = fingerprints.stream().map(VersionedFingerprint::keyVersion).distinct().count();
        if (distinctVersions != fingerprints.size()) {
            throw new IllegalArgumentException("fingerprint key versions must be unique");
        }
        var priorOwners = fingerprints.stream().map(fp -> jdbc.sql("""
                        select customer_identity_id from customer_memory_identity_fingerprint
                        where tenant_id=:tenantId and channel=:channel and key_version=:keyVersion
                          and participant_fingerprint=:fingerprint
                        """)
                .param("tenantId", tenantId).param("channel", channel.name())
                .param("keyVersion", fp.keyVersion()).param("fingerprint", fp.fingerprint())
                .query(UUID.class).optional().orElse(null)).filter(java.util.Objects::nonNull).distinct().toList();
        if (priorOwners.size() > 1) {
            throw new IllegalStateException("fingerprints resolve to conflicting customer identities");
        }
        var ordered = fingerprints.stream().sorted(Comparator.comparingInt(VersionedFingerprint::keyVersion)).toList();
        CustomerMemoryIdentity identity = null;
        for (var fingerprint : ordered) {
            identity = jdbc.sql("""
                    select i.id, i.tenant_id, i.channel, i.facts_forget_before, i.created_at
                    from customer_memory_identity_fingerprint f
                    join customer_memory_identity i on i.id = f.customer_identity_id
                    where f.tenant_id = :tenantId and f.channel = :channel
                      and f.key_version = :keyVersion and f.participant_fingerprint = :fingerprint
                    for update of i
                    """)
                    .param("tenantId", tenantId).param("channel", channel.name())
                    .param("keyVersion", fingerprint.keyVersion()).param("fingerprint", fingerprint.fingerprint())
                    .query((rs, row) -> mapIdentity(rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class), rs.getString("channel"),
                            rs.getTimestamp("facts_forget_before"), rs.getTimestamp("created_at")))
                    .optional().orElse(null);
            if (identity != null) break;
        }
        if (identity == null) {
            identity = new CustomerMemoryIdentity(UUID.randomUUID(), tenantId, channel, null, Instant.now());
            jdbc.sql("""
                    insert into customer_memory_identity(id, tenant_id, channel, created_at)
                    values (:id, :tenantId, :channel, :createdAt)
                    """)
                    .param("id", identity.id()).param("tenantId", tenantId)
                    .param("channel", channel.name()).param("createdAt", Timestamp.from(identity.createdAt()))
                    .update();
        }
        for (var fingerprint : ordered) {
            var inserted = jdbc.sql("""
                    insert into customer_memory_identity_fingerprint(
                        customer_identity_id, tenant_id, channel, key_version, participant_fingerprint, created_at)
                    values (:identityId, :tenantId, :channel, :keyVersion, :fingerprint, :createdAt)
                    on conflict (tenant_id, channel, key_version, participant_fingerprint) do nothing
                    """)
                    .param("identityId", identity.id()).param("tenantId", tenantId)
                    .param("channel", channel.name()).param("keyVersion", fingerprint.keyVersion())
                    .param("fingerprint", fingerprint.fingerprint()).param("createdAt", Timestamp.from(Instant.now()))
                    .update();
            if (inserted == 0) {
                var owner = jdbc.sql("""
                        select customer_identity_id from customer_memory_identity_fingerprint
                        where tenant_id=:tenantId and channel=:channel and key_version=:keyVersion
                          and participant_fingerprint=:fingerprint
                        """)
                        .param("tenantId", tenantId).param("channel", channel.name())
                        .param("keyVersion", fingerprint.keyVersion()).param("fingerprint", fingerprint.fingerprint())
                        .query(UUID.class).single();
                if (!owner.equals(identity.id())) {
                    throw new IllegalStateException("fingerprint ownership conflict");
                }
            }
        }
        return identity;
    }

    @Override
    @Transactional
    public void linkConversation(UUID tenantId, Channel channel, UUID identityId, UUID conversationId) {
        if (tenantId == null || channel != Channel.WHATSAPP || identityId == null || conversationId == null) {
            throw new IllegalArgumentException("tenant, WhatsApp channel, identity, and conversation are required");
        }
        var identityMatches = jdbc.sql("""
                select exists(select 1 from customer_memory_identity
                              where id = :identityId and tenant_id = :tenantId and channel = :channel)
                """)
                .param("identityId", identityId).param("tenantId", tenantId).param("channel", channel.name())
                .query(Boolean.class).single();
        if (!identityMatches) throw new IllegalArgumentException("identity does not match tenant/channel");
        jdbc.sql("""
                insert into customer_memory_identity_conversation(
                    tenant_id, channel, customer_identity_id, conversation_id, linked_at)
                values (:tenantId, :channel, :identityId, :conversationId, :linkedAt)
                on conflict (conversation_id) do nothing
                """)
                .param("tenantId", tenantId).param("channel", channel.name())
                .param("identityId", identityId).param("conversationId", conversationId)
                .param("linkedAt", Timestamp.from(Instant.now())).update();
        var linkedIdentity = findIdentityByConversation(tenantId, channel, conversationId);
        if (linkedIdentity == null || !linkedIdentity.id().equals(identityId)) {
            throw new IllegalStateException("conversation is already linked to another customer identity");
        }
    }

    @Override
    public List<ConversationIdentitySource> nextConversationBatch(UUID afterConversationId, int limit) {
        if (limit < 1 || limit > 5000) throw new IllegalArgumentException("limit must be between 1 and 5000");
        var query = jdbc.sql("""
                select c.id, c.tenant_id, c.channel, c.participant_id
                from conversation c
                where (:afterId is null or c.id > :afterId)
                  and c.provider_account_id <> 'legacy'
                  and c.channel = 'WHATSAPP'
                  and exists (select 1 from channel_binding b
                               where b.tenant_id = c.tenant_id and b.channel = c.channel
                                 and b.provider_account_id = c.provider_account_id)
                order by c.id
                limit :limit
                """).param("afterId", afterConversationId).param("limit", limit);
        return query.query((rs, row) -> new ConversationIdentitySource(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                Channel.valueOf(rs.getString("channel")), rs.getString("participant_id")))
                .stream().filter(source -> source.channel() == Channel.WHATSAPP).toList();
    }

    @Override
    public CustomerMemoryIdentity findIdentityByConversation(UUID tenantId, Channel channel, UUID conversationId) {
        return jdbc.sql("""
                select i.id, i.tenant_id, i.channel, i.facts_forget_before, i.created_at
                from customer_memory_identity_conversation c
                join customer_memory_identity i on i.id = c.customer_identity_id
                where c.tenant_id = :tenantId and c.channel = :channel and c.conversation_id = :conversationId
                """)
                .param("tenantId", tenantId).param("channel", channel.name())
                .param("conversationId", conversationId)
                .query((rs, row) -> mapIdentity(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("channel"),
                        rs.getTimestamp("facts_forget_before"), rs.getTimestamp("created_at")))
                .optional().orElse(null);
    }

    private static CustomerMemoryIdentity mapIdentity(UUID id, UUID tenantId, String channel,
                                                       Timestamp forgetBefore, Timestamp createdAt) {
        return new CustomerMemoryIdentity(id, tenantId, Channel.valueOf(channel),
                forgetBefore == null ? null : forgetBefore.toInstant(), createdAt.toInstant());
    }
}
