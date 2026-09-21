package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.MemoryProperties;
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

class ConversationMemoryServiceTest {

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

    private static final class FakeMemoryPort implements ConversationMemoryPort {
        private RuntimeException loadFailure;

        @Override
        public ConversationMemoryContext load(MemoryQuery query) {
            if (loadFailure != null) {
                throw loadFailure;
            }
            return ConversationMemoryContext.empty(query.key());
        }

        @Override
        public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
        }

        @Override
        public void recordOutbound(ConversationMemoryKey key, ConversationMessage message) {
        }

        @Override
        public MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                                    MemoryProjectionUpdate update,
                                                    long expectedVersion) {
            return new MemoryUpdateResult(false, expectedVersion);
        }
    }
}
