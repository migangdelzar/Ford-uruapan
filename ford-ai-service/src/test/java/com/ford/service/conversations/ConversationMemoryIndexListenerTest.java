package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.ConversationMemoryIndexRequested;
import com.ford.service.conversations.application.ConversationMemoryIndexListener;
import com.ford.service.conversations.application.ConversationMemoryRecallPort;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryIndexListenerTest {

    @Test
    void redactsSensitiveValuesBeforeIndexing() {
        var properties = new MemoryProperties();
        properties.setSemanticRecallEnabled(true);
        var recall = mock(ConversationMemoryRecallPort.class);
        var model = mock(EmbeddingModel.class);
        var models = provider(model);
        when(model.embed(any(String.class))).thenReturn(new float[]{0.1f, 0.2f});
        var listener = new ConversationMemoryIndexListener(recall, models, properties);
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider", "participant", UUID.randomUUID());
        var message = new ConversationMessage(UUID.randomUUID(), key.conversationId(),
                ConversationMessage.Direction.INBOUND, "message-1",
                "Mi correo es miguel@example.com y mi teléfono +52 333 123 4567", Instant.now());

        listener.on(new ConversationMemoryIndexRequested(key, message));

        verify(recall).upsert(eq(key), eq(message),
                eq("Mi correo es [EMAIL] y mi teléfono [PHONE]"),
                eq(properties.semanticEmbeddingModel()), any(float[].class));
    }

    @Test
    void remainsDisabledByDefault() {
        var recall = mock(ConversationMemoryRecallPort.class);
        var listener = new ConversationMemoryIndexListener(recall, provider(mock(EmbeddingModel.class)),
                new MemoryProperties());

        listener.on(new ConversationMemoryIndexRequested(null, null));

        org.mockito.Mockito.verifyNoInteractions(recall);
    }

    private static ObjectProvider<EmbeddingModel> provider(EmbeddingModel model) {
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return provider;
    }
}
