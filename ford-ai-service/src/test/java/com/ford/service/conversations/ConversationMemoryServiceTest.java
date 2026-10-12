package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationMemorySemanticPort;
import com.ford.service.conversations.application.ConversationMemoryRecallPort;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.application.ConversationRuntimeSettings;
import com.ford.service.conversations.application.ConversationRuntimeSettingsService;
import com.ford.service.conversations.application.ConversationRuntimeSettingsSnapshot;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.MemoryUpdateResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceTest {

    @Test
    void loadsOnlyThePromptWindowWhileTheDurableWindowRemainsLarger() {
        var properties = new MemoryProperties();
        properties.setRecentMessageLimit(20);
        properties.setPromptRecentMessageLimit(8);
        var port = new FakeMemoryPort();
        var service = new ConversationMemoryService(port, properties, new FakeSemanticPort());
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "phone-1", "customer-1", UUID.randomUUID());

        service.load(new MemoryQuery(key, "¿Qué promociones tienen?"));

        assertThat(port.lastQuery.recentMessageLimit()).isEqualTo(8);
        assertThat(port.lastQuery.semanticLimit()).isEqualTo(properties.semanticLimit());
    }

    @Test
    void appliesTenantRuntimeMemoryDisableAndPromptLimit() {
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "phone-1", "customer-1", UUID.randomUUID());
        var port = new FakeMemoryPort();
        var runtime = mock(ConversationRuntimeSettingsService.class);
        when(runtime.current(key.tenantId())).thenReturn(new ConversationRuntimeSettingsSnapshot(
                key.tenantId(), 2, new ConversationRuntimeSettings(false, false, 6), Instant.now(), UUID.randomUUID()));
        var service = new ConversationMemoryService(port, new MemoryProperties(), new FakeSemanticPort(),
                new FakeRecallPort(), event -> { }, com.ford.service.conversations.application.ConversationMemoryCompactionPort.unavailable(), runtime);

        var context = service.load(new MemoryQuery(key, "Ranger"));
        service.recordInbound(key, UUID.randomUUID(), "msg-1", "Ranger", Instant.now());

        assertThat(context.summary()).isEmpty();
        assertThat(port.lastQuery).isNull();
        assertThat(port.recordedInbound).isZero();
    }

    @Test
    void appliesTenantRuntimePromptLimitWhenMemoryIsEnabled() {
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "phone-1", "customer-1", UUID.randomUUID());
        var port = new FakeMemoryPort();
        var runtime = mock(ConversationRuntimeSettingsService.class);
        when(runtime.current(key.tenantId())).thenReturn(new ConversationRuntimeSettingsSnapshot(
                key.tenantId(), 2, new ConversationRuntimeSettings(false, true, 6), Instant.now(), UUID.randomUUID()));
        var service = new ConversationMemoryService(port, new MemoryProperties(), new FakeSemanticPort(),
                new FakeRecallPort(), event -> { }, com.ford.service.conversations.application.ConversationMemoryCompactionPort.unavailable(), runtime);

        service.load(new MemoryQuery(key, "Ranger"));

        assertThat(port.lastQuery.recentMessageLimit()).isEqualTo(6);
    }

    @Test
    void indexesCanonicalOutboundRepliesWithoutWritingAnotherTranscriptCopy() {
        var properties = new MemoryProperties();
        var port = new FakeMemoryPort();
        var published = new java.util.ArrayList<Object>();
        var service = new ConversationMemoryService(port, properties,
                new FakeSemanticPort(), event -> published.add(event));
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "phone-1", "customer-1", UUID.randomUUID());
        var outboundId = UUID.randomUUID();

        service.indexOutbound(key, outboundId, "outbound:inbound-1", "Le comparto la información.",
                Instant.now());

        assertThat(published).singleElement()
                .isInstanceOfSatisfying(com.ford.service.conversations.api.ConversationMemoryIndexRequested.class,
                        event -> {
                            assertThat(event.message().direction()).isEqualTo(ConversationMessage.Direction.OUTBOUND);
                            assertThat(event.message().providerMessageId()).isEqualTo("outbound:inbound-1");
                            assertThat(event.message().content()).isEqualTo("Le comparto la información.");
                        });
    }

    @Test
    void returnsEmptyContextWhenMemoryStoreFails() {
        var key = new ConversationMemoryKey(
                UUID.randomUUID(), Channel.WHATSAPP, "phone-1", "customer-1", UUID.randomUUID());
        var port = new FakeMemoryPort();
        port.loadFailure = new IllegalStateException("database unavailable");

        var service = new ConversationMemoryService(port, new MemoryProperties());

        var context = service.load(new MemoryQuery(key, "Hola"));

        assertThat(context.key()).isEqualTo(key);
        assertThat(context.summary()).isEmpty();
        assertThat(context.recentMessages()).isEmpty();
        assertThat(context.durableFacts()).isEmpty();
        assertThat(context.memoryVersion()).isZero();
    }

    @Test
    void addsSemanticFactsWhenSemanticMemoryIsEnabled() {
        var key = new ConversationMemoryKey(
                UUID.randomUUID(), Channel.WHATSAPP, "phone-1", "customer-1", UUID.randomUUID());
        var properties = new MemoryProperties();
        properties.setSemanticEnabled(true);
        var semantic = new FakeSemanticPort();
        semantic.facts = List.of(new MemoryFact("preference", "Ranger", null));

        var service = new ConversationMemoryService(new FakeMemoryPort(), properties, semantic);

        var context = service.load(new MemoryQuery(key, "Ranger"));

        assertThat(context.durableFacts()).containsExactly(new MemoryFact("preference", "Ranger", null));
    }

    @Test
    void recallsArchivedTurnsOnlyForHistoricalQuestions() {
        var key = new ConversationMemoryKey(
                UUID.randomUUID(), Channel.WHATSAPP, "phone-1", "customer-1", UUID.randomUUID());
        var properties = new MemoryProperties();
        properties.setSemanticRecallEnabled(true);
        var recall = new FakeRecallPort();
        recall.turns = List.of(new com.ford.service.conversations.domain.ConversationMemoryRecall(
                UUID.randomUUID(), ConversationMessage.Direction.INBOUND, "Ranger", Instant.now(), 0.91));

        var service = new ConversationMemoryService(new FakeMemoryPort(), properties,
                new FakeSemanticPort(), recall, event -> { });

        var context = service.load(new MemoryQuery(key, "¿Qué modelo le había dicho que me interesaba?"));

        assertThat(context.recalledTurns()).hasSize(1);
        assertThat(recall.lastLimit).isEqualTo(3);
    }

    private static final class FakeMemoryPort implements ConversationMemoryPort {
        private RuntimeException loadFailure;
        private MemoryQuery lastQuery;
        private int recordedInbound;

        @Override
        public ConversationMemoryContext load(MemoryQuery query) {
            lastQuery = query;
            if (loadFailure != null) {
                throw loadFailure;
            }
            return ConversationMemoryContext.empty(query.key());
        }

        @Override
        public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
            recordedInbound++;
        }

        @Override
        public MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                                    MemoryProjectionUpdate update,
                                                    long expectedVersion) {
            return new MemoryUpdateResult(false, expectedVersion);
        }
    }

    private static final class FakeSemanticPort implements ConversationMemorySemanticPort {
        private List<MemoryFact> facts = List.of();

        @Override
        public List<MemoryFact> search(ConversationMemoryKey key, String query, int limit) {
            return facts;
        }

        @Override
        public void upsert(ConversationMemoryKey key, MemoryFact fact) {
        }
    }

    private static final class FakeRecallPort implements ConversationMemoryRecallPort {
        private List<com.ford.service.conversations.domain.ConversationMemoryRecall> turns = List.of();
        private int lastLimit;

        @Override
        public List<com.ford.service.conversations.domain.ConversationMemoryRecall> search(
                ConversationMemoryKey key, String query, Instant before, int limit, double minimumSimilarity) {
            lastLimit = limit;
            return turns;
        }

        @Override
        public void upsert(ConversationMemoryKey key, ConversationMessage message, String redactedContent,
                           String embeddingModel, float[] embedding) {
        }
    }
}
