package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.CustomerIdentityFingerprint;
import com.ford.service.conversations.application.CustomerIdentityBackfill;
import com.ford.service.conversations.application.CustomerMemoryProperties;
import com.ford.service.conversations.application.port.out.CustomerMemoryIdentityRepository;
import com.ford.service.conversations.domain.CustomerMemoryIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerIdentityBackfillTest {

    @Test
    void processesBatchesAndUsesCurrentAndPreviousFingerprintForOneIdentity() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var repository = new FakeRepository(List.of(new CustomerMemoryIdentityRepository.ConversationIdentitySource(
                conversation, tenant, Channel.WHATSAPP, "+52 333 173 3925")));
        var properties = new CustomerMemoryProperties();
        properties.setHmacKey("01234567890123456789012345678901");
        properties.setHmacKeyVersion(2);
        properties.setPreviousHmacKey("abcdefghijklmnopqrstuvwxyzABCDEF");
        properties.setPreviousHmacKeyVersion(1);
        properties.setEnabled(true);

        var result = new CustomerIdentityBackfill(repository, new CustomerIdentityFingerprint(), properties).run(10);

        assertThat(result).isEqualTo(new CustomerIdentityBackfill.BackfillResult(1, 1, 0));
        assertThat(repository.fingerprintVersions).containsExactly(1, 2);
        assertThat(repository.linkedConversation).isEqualTo(conversation);
    }

    @Test
    void rejectsInvalidBatchSize() {
        var service = new CustomerIdentityBackfill(new FakeRepository(List.of()),
                new CustomerIdentityFingerprint(), new CustomerMemoryProperties());
        assertThatThrownBy(() -> service.run(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeRepository implements CustomerMemoryIdentityRepository {
        private final List<ConversationIdentitySource> sources;
        private List<Integer> fingerprintVersions = List.of();
        private UUID linkedConversation;
        private int batchCalls;

        private FakeRepository(List<ConversationIdentitySource> sources) { this.sources = sources; }

        @Override
        public CustomerMemoryIdentity resolveOrCreateIdentity(UUID tenantId, Channel channel,
                                                               List<VersionedFingerprint> fingerprints) {
            fingerprintVersions = fingerprints.stream().map(VersionedFingerprint::keyVersion).sorted().toList();
            return new CustomerMemoryIdentity(UUID.randomUUID(), tenantId, channel, null, Instant.now());
        }

        @Override
        public void linkConversation(UUID tenantId, Channel channel, UUID identityId, UUID conversationId) {
            linkedConversation = conversationId;
        }

        @Override
        public List<ConversationIdentitySource> nextConversationBatch(UUID afterConversationId, int limit) {
            batchCalls++;
            return afterConversationId == null ? sources : List.of();
        }

        @Override
        public CustomerMemoryIdentity findIdentityByConversation(UUID tenantId, Channel channel, UUID conversationId) {
            return null;
        }

    }
}
