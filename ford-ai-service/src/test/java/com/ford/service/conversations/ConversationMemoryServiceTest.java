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
    void capturesSharedFactsOnlyAfterInboundTranscriptPersistence() {
        var properties = new MemoryProperties();
        var port = new FakeMemoryPort();
        var memory = new java.util.ArrayList<String>();
        var customerMemory = mock(com.ford.service.conversations.application.CustomerMemoryService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(memory).containsExactly("inbound-persisted");
            return null;
        }).when(customerMemory).captureExplicitFacts(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        var memoryPort = new FakeMemoryPort();
        memoryPort.afterInbound = () -> memory.add("inbound-persisted");
        var service = new ConversationMemoryService(memoryPort, properties, new FakeSemanticPort(),
                new FakeRecallPort(), event -> { }, com.ford.service.conversations.application.ConversationMemoryCompactionPort.unavailable(),
                null, customerMemory);
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "phone-1", "customer-1", UUID.randomUUID());
        var messageId = UUID.randomUUID();
        var receivedAt = Instant.now();

        service.recordInbound(key, messageId, "provider-message", "Me interesa la Bronco", receivedAt);

        org.mockito.Mockito.verify(customerMemory).captureExplicitFacts(key.tenantId(), key.conversationId(), key,
                messageId, receivedAt, "Me interesa la Bronco");
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

    @Test
    void expandsOneContextDependentMemoryQueryAfterAnInsufficientBaseSearch() {
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider", "participant", UUID.randomUUID());
        var properties = new MemoryProperties();
        properties.setSemanticRecallEnabled(true);
        var memory = new FakeMemoryPort();
        memory.context = new ConversationMemoryContext(key, "", List.of(
                new ConversationMessage(UUID.randomUUID(), key.conversationId(), ConversationMessage.Direction.INBOUND,
                        null, "Comparo Ranger XL y Ranger XLT", Instant.now())),
                List.of(new MemoryFact("vehicle_comparison", "Ranger XL y Ranger XLT", UUID.randomUUID())), 1);
        var recall = new FakeRecallPort();
        var expandedResult = new com.ford.service.conversations.domain.ConversationMemoryRecall(
                UUID.randomUUID(), ConversationMessage.Direction.INBOUND, "Ranger XL y Ranger XLT",
                Instant.now(), 0.93);
        recall.responses.put("la otra versión Ranger XL Ranger XLT", List.of(expandedResult));
        var service = new ConversationMemoryService(memory, properties, new FakeSemanticPort(), recall, event -> { });

        var context = service.load(new MemoryQuery(key, "la otra versión"));

        assertThat(recall.queries).containsExactly("la otra versión", "la otra versión Ranger XL Ranger XLT");
        assertThat(context.recalledTurns()).containsExactly(expandedResult);
    }

    @Test
    void skipsExpandedMemoryQueryWhenBaseRecallAlreadyHasEnoughEvidence() {
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider", "participant", UUID.randomUUID());
        var properties = new MemoryProperties();
        properties.setSemanticRecallEnabled(true);
        var recall = new FakeRecallPort();
        recall.turns = List.of(
                new com.ford.service.conversations.domain.ConversationMemoryRecall(UUID.randomUUID(),
                        ConversationMessage.Direction.INBOUND, "Ranger XL", Instant.now(), 0.93),
                new com.ford.service.conversations.domain.ConversationMemoryRecall(UUID.randomUUID(),
                        ConversationMessage.Direction.INBOUND, "Ranger XLT", Instant.now(), 0.91));
        var service = new ConversationMemoryService(new FakeMemoryPort(), properties,
                new FakeSemanticPort(), recall, event -> { });

        service.load(new MemoryQuery(key, "la otra versión"));

        assertThat(recall.queries).containsExactly("la otra versión");
    }

    @Test
    void treatsDuplicateBaseMatchesAsInsufficientEvidenceBeforeExpansion() {
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider", "participant", UUID.randomUUID());
        var properties = new MemoryProperties();
        properties.setSemanticRecallEnabled(true);
        var recall = new FakeRecallPort();
        var sameMessageId = UUID.randomUUID();
        var first = new com.ford.service.conversations.domain.ConversationMemoryRecall(sameMessageId,
                ConversationMessage.Direction.INBOUND, "Ranger XL", Instant.now(), 0.93);
        var duplicate = new com.ford.service.conversations.domain.ConversationMemoryRecall(sameMessageId,
                ConversationMessage.Direction.INBOUND, "Ranger XL duplicate", Instant.now(), 0.91);
        recall.turns = List.of(first, duplicate);
        var expanded = new com.ford.service.conversations.domain.ConversationMemoryRecall(UUID.randomUUID(),
                ConversationMessage.Direction.INBOUND, "Ranger XLT", Instant.now(), 0.90);
        recall.responses.put("la otra versión Ranger XL Ranger XLT", List.of(expanded));
        var port = new FakeMemoryPort();
        port.context = new ConversationMemoryContext(key, "", List.of(new ConversationMessage(
                UUID.randomUUID(), key.conversationId(), ConversationMessage.Direction.INBOUND, null,
                "Comparo Ranger XL y Ranger XLT", Instant.now())), List.of(), 1);
        var service = new ConversationMemoryService(port, properties, new FakeSemanticPort(), recall, event -> { });

        var context = service.load(new MemoryQuery(key, "la otra versión"));

        assertThat(recall.queries).containsExactly("la otra versión", "la otra versión Ranger XL Ranger XLT");
        assertThat(context.recalledTurns()).containsExactly(first, expanded);
    }

    @Test
    void keepsBaseRecallWhenExpandedSearchFails() {
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider", "participant", UUID.randomUUID());
        var properties = new MemoryProperties();
        properties.setSemanticRecallEnabled(true);
        var memory = new FakeMemoryPort();
        memory.context = new ConversationMemoryContext(key, "", List.of(new ConversationMessage(
                UUID.randomUUID(), key.conversationId(), ConversationMessage.Direction.INBOUND, null,
                "Comparo Ranger XL y Ranger XLT", Instant.now())), List.of(), 1);
        var recall = new FakeRecallPort();
        var base = new com.ford.service.conversations.domain.ConversationMemoryRecall(UUID.randomUUID(),
                ConversationMessage.Direction.INBOUND, "Ranger XL", Instant.now(), 0.90);
        recall.responses.put("la otra versión", List.of(base));
        recall.failures.put("la otra versión Ranger XL Ranger XLT",
                new IllegalStateException("recall backend unavailable"));
        var service = new ConversationMemoryService(memory, properties, new FakeSemanticPort(), recall, event -> { });

        var context = service.load(new MemoryQuery(key, "la otra versión"));

        assertThat(recall.queries).containsExactly("la otra versión", "la otra versión Ranger XL Ranger XLT");
        assertThat(context.recalledTurns()).containsExactly(base);
    }

    @Test
    void projectsFromTheBoundedCanonicalTranscriptSlice() {
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider", "participant", UUID.randomUUID());
        var properties = new MemoryProperties();
        var port = new FakeMemoryPort();
        var contextStartedAt = Instant.now().minusSeconds(3600);
        var context = new ConversationMemoryContext(key, "previous generated summary", List.of(),
                List.of(new MemoryFact("budget", "350000", UUID.randomUUID())), 2,
                Instant.now(), List.of(), contextStartedAt);
        var source = List.of(new ConversationMessage(UUID.randomUUID(), key.conversationId(),
                ConversationMessage.Direction.INBOUND, "older", "Busco Ranger para ciudad", contextStartedAt));
        port.rebaseMessages = source;
        var capturedPreviousSummary = new java.util.concurrent.atomic.AtomicReference<String>();
        var capturedSource = new java.util.concurrent.atomic.AtomicReference<List<ConversationMessage>>();
        com.ford.service.conversations.application.ConversationMemoryCompactionPort compactor =
                (tenant, conversation, summary, messages, maxChars) -> {
                    capturedPreviousSummary.set(summary);
                    capturedSource.set(messages);
                    return "Busca Ranger para ciudad.";
                };
        var service = new ConversationMemoryService(port, properties, new FakeSemanticPort(),
                new FakeRecallPort(), event -> { }, compactor);

        var update = service.project(context, "La Ranger XLT me atrae", UUID.randomUUID());

        assertThat(port.lastRebaseKey).isEqualTo(key);
        assertThat(port.lastKeepRecentCount).isEqualTo(properties.promptRecentMessageLimit());
        assertThat(port.lastMaximumRebaseMessages).isEqualTo(properties.rebaseMessageLimit());
        assertThat(capturedPreviousSummary.get()).isEmpty();
        assertThat(capturedSource.get()).containsExactlyElementsOf(source);
        assertThat(update.summary()).isEqualTo("Busca Ranger para ciudad.");
    }

    private static final class FakeMemoryPort implements ConversationMemoryPort {
        private RuntimeException loadFailure;
        private MemoryQuery lastQuery;
        private int recordedInbound;
        private Runnable afterInbound = () -> { };
        private ConversationMemoryContext context;
        private List<ConversationMessage> rebaseMessages = List.of();
        private ConversationMemoryKey lastRebaseKey;
        private int lastKeepRecentCount;
        private int lastMaximumRebaseMessages;

        @Override
        public ConversationMemoryContext load(MemoryQuery query) {
            lastQuery = query;
            if (loadFailure != null) {
                throw loadFailure;
            }
            return context == null ? ConversationMemoryContext.empty(query.key()) : context;
        }

        @Override
        public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
            recordedInbound++;
            afterInbound.run();
        }

        @Override
        public List<ConversationMessage> loadTranscriptForRebase(ConversationMemoryKey key, Instant contextStartedAt,
                                                                  int keepRecentCount, int maxRebaseMessages) {
            lastRebaseKey = key;
            lastKeepRecentCount = keepRecentCount;
            lastMaximumRebaseMessages = maxRebaseMessages;
            return rebaseMessages;
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
        private final java.util.Map<String, List<com.ford.service.conversations.domain.ConversationMemoryRecall>> responses =
                new java.util.HashMap<>();
        private final java.util.Map<String, RuntimeException> failures = new java.util.HashMap<>();
        private final java.util.List<String> queries = new java.util.ArrayList<>();

        @Override
        public List<com.ford.service.conversations.domain.ConversationMemoryRecall> search(
                ConversationMemoryKey key, String query, Instant before, int limit, double minimumSimilarity) {
            lastLimit = limit;
            queries.add(query);
            if (failures.containsKey(query)) throw failures.get(query);
            return responses.getOrDefault(query, turns);
        }

        @Override
        public void upsert(ConversationMemoryKey key, ConversationMessage message, String redactedContent,
                           String embeddingModel, float[] embedding) {
        }
    }
}
