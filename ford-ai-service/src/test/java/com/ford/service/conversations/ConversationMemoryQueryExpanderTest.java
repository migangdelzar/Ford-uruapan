package com.ford.service.conversations;

import com.ford.service.conversations.application.ConversationMemoryQueryExpander;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryQueryExpanderTest {

    @Test
    void addsKnownVehicleTermsToOneContextDependentAlternateQuery() {
        var expander = new ConversationMemoryQueryExpander();
        var recent = List.of(message("Estoy comparando Ranger XL y Ranger XLT."));

        var expanded = expander.expand("la otra versión", List.of(), recent);

        assertThat(expanded).contains("la otra versión Ranger XL Ranger XLT");
    }

    @Test
    void doesNotExpandClearQueries() {
        var expander = new ConversationMemoryQueryExpander();
        var recent = List.of(message("Estoy comparando Ranger XL y Ranger XLT."));

        assertThat(expander.expand("¿Qué promociones tiene Ranger?", List.of(), recent)).isEmpty();
    }

    @Test
    void doesNotExpandWhenConversationHasNoKnownSpecificTerms() {
        var expander = new ConversationMemoryQueryExpander();

        assertThat(expander.expand("la otra versión", List.of(new MemoryFact("use", "ciudad", null)),
                List.of(message("Sí, por favor.")))).isEmpty();
    }

    private static ConversationMessage message(String content) {
        return new ConversationMessage(UUID.randomUUID(), UUID.randomUUID(),
                ConversationMessage.Direction.INBOUND, null, content, Instant.now());
    }
}
