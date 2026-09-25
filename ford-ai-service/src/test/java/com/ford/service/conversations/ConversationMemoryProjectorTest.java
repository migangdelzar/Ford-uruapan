package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryProjector;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryFact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryProjectorTest {

    @Test
    void projectsOnlyExplicitCustomerFactsAndLatestRequest() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var sourceMessageId = UUID.randomUUID();
        var key = new ConversationMemoryKey(tenantId, Channel.WHATSAPP, "phone-1", "customer-1",
                conversationId);
        var memory = new ConversationMemoryContext(key, "old summary", List.of(),
                List.of(new MemoryFact("name", "Luis", null)), 4);

        var update = new ConversationMemoryProjector(new MemoryProperties()).project(
                memory, "Me llamo Ana y busco una Ranger", sourceMessageId);

        assertThat(update.summary()).isEqualTo(
                "Última solicitud del usuario: Me llamo Ana y busco una Ranger");
        assertThat(update.durableFacts())
                .containsExactly(new MemoryFact("name", "Ana", sourceMessageId),
                        new MemoryFact("vehicle_interest", "Ranger", sourceMessageId));
        assertThat(update.sourceMessageId()).isEqualTo(sourceMessageId);
    }

    @Test
    void keepsLatestRequestWhenTheMessageHasNoProjectableFact() {
        var key = new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP, "phone-1", "customer-1",
                UUID.randomUUID());
        var memory = new ConversationMemoryContext(key, "Última solicitud anterior", List.of(), List.of(), 2);

        var update = new ConversationMemoryProjector(new MemoryProperties()).project(
                memory, "¿Qué promociones tienen?", UUID.randomUUID());

        assertThat(update.summary()).isEqualTo("Última solicitud del usuario: ¿Qué promociones tienen?");
        assertThat(update.durableFacts()).isEmpty();
    }
}
