package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryCompactionPort;
import com.ford.service.conversations.application.ConversationMemoryProjector;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConversationMemoryProjectorTest {

    @Test
    void projectsExplicitCustomerFactsAndRetainsExistingSummaryBeforeWindowRollsOver() {
        var sourceMessageId = UUID.randomUUID();
        var memory = context("old summary", List.of(), List.of(new MemoryFact("name", "Luis", null)));

        var update = new ConversationMemoryProjector(new MemoryProperties()).project(
                memory, "Me llamo Ana y busco una Ranger", sourceMessageId);

        assertThat(update.summary()).isEqualTo("old summary");
        assertThat(update.durableFacts())
                .containsExactly(new MemoryFact("name", "Ana", sourceMessageId),
                        new MemoryFact("vehicle_interest", "Ranger", sourceMessageId));
        assertThat(update.sourceMessageId()).isEqualTo(sourceMessageId);
    }

    @Test
    void compactsOnlyMessagesThatLeaveTheEightMessageWindow() {
        var receivedMessages = new AtomicReference<List<ConversationMessage>>();
        var previousSummary = new AtomicReference<String>();
        ConversationMemoryCompactionPort compactor = (tenant, conversation, summary, expired, maxChars) -> {
            previousSummary.set(summary);
            receivedMessages.set(expired);
            return "Cliente busca Ranger y revisa presupuesto.";
        };
        var recent = eightTurns();
        var memory = context("compacto previo", recent, List.of());

        var update = new ConversationMemoryProjector(new MemoryProperties(), compactor)
                .project(memory, "¿Cuánto queda por mes?", UUID.randomUUID());

        assertThat(previousSummary.get()).isEqualTo("compacto previo");
        assertThat(receivedMessages.get()).hasSize(2);
        assertThat(receivedMessages.get()).containsExactlyElementsOf(recent.subList(0, 2));
        assertThat(update.summary()).isEqualTo("Cliente busca Ranger y revisa presupuesto.");
    }

    @Test
    void preservesLastValidSummaryWhenCompactionFails() {
        ConversationMemoryCompactionPort compactor = (tenant, conversation, summary, expired, maxChars) -> {
            throw new IllegalStateException("provider unavailable");
        };

        var update = new ConversationMemoryProjector(new MemoryProperties(), compactor)
                .project(context("último resumen válido", eightTurns(), List.of()),
                        "¿Cuánto queda por mes?", UUID.randomUUID());

        assertThat(update.summary()).isEqualTo("último resumen válido");
    }

    @Test
    void persistsADeclinedSpecialistOfferAsDurableConversationState() {
        var update = new ConversationMemoryProjector(new MemoryProperties()).project(
                context("", List.of(), List.of(new MemoryFact("specialist_offer_offered", "true", null))),
                "No, gracias", UUID.randomUUID());

        assertThat(update.durableFacts())
                .contains(new MemoryFact("specialist_offer_declined", "true", update.sourceMessageId()))
                .doesNotContain(new MemoryFact("specialist_offer_offered", "true", null));
    }

    @Test
    void acceptsExplicitlyShorterSummaryLimitsAndDoesNotTruncatePriorSummary() {
        var properties = new MemoryProperties();
        properties.setSummaryMaxChars(16);

        assertThatCode(() -> new ConversationMemoryProjector(properties).project(
                context("long previous summary remains intact", List.of(), List.of()),
                "¿Qué opciones hay?", UUID.randomUUID())).doesNotThrowAnyException();
        var update = new ConversationMemoryProjector(properties).project(
                context("long previous summary remains intact", List.of(), List.of()),
                "¿Qué opciones hay?", UUID.randomUUID());
        assertThat(update.summary()).isEqualTo("long previous summary remains intact");
    }

    private static ConversationMemoryContext context(String summary, List<ConversationMessage> recent,
                                                    List<MemoryFact> facts) {
        return new ConversationMemoryContext(key(), summary, recent, facts, 4);
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP, "provider", "participant",
                UUID.randomUUID());
    }

    private static List<ConversationMessage> eightTurns() {
        var turns = new ArrayList<ConversationMessage>();
        for (var index = 0; index < 4; index++) {
            turns.add(message(ConversationMessage.Direction.INBOUND, "mensaje del cliente " + index));
            turns.add(message(ConversationMessage.Direction.OUTBOUND, "respuesta del asistente " + index));
        }
        return List.copyOf(turns);
    }

    private static ConversationMessage message(ConversationMessage.Direction direction, String content) {
        return new ConversationMessage(UUID.randomUUID(), UUID.randomUUID(), direction,
                UUID.randomUUID().toString(), content, Instant.now());
    }
}
