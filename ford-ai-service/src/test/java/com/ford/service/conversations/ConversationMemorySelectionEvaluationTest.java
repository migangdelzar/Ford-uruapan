package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryPromptSelector;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMemoryRecall;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemorySelectionEvaluationTest {

    private static final String CURRENT_MESSAGE =
            "La caja de la Ranger XLT me atrae más, pero la XL quizá sea más práctica en ciudad.";

    @Test
    void retainsLongLivedBuyerNeedsCorrectionsAndRecentTrimComparisonWithinBudget() {
        var properties = new MemoryProperties();
        properties.setPromptTokenBudget(400);
        var selector = new ConversationMemoryPromptSelector(properties);
        var transcript = syntheticThirtyOneTurnHistory();
        var protectedFacts = List.of(
                new MemoryFact("intended_use", "ciudad y familia", UUID.randomUUID()),
                new MemoryFact("activity", "camping de fin de semana", UUID.randomUUID()),
                new MemoryFact("vehicle_comparison", "Ranger XL y XLT", UUID.randomUUID()),
                new MemoryFact("correction", "prefiere gasolina; corrigió híbrida", UUID.randomUUID()));
        var relevantOldTurn = new ConversationMemoryRecall(UUID.randomUUID(),
                ConversationMessage.Direction.INBOUND,
                "Para camping y familia, la Ranger XL resulta más práctica en ciudad.",
                Instant.parse("2026-10-20T12:00:00Z"), 0.97);
        var resolvedSocialTurn = new ConversationMemoryRecall(UUID.randomUUID(),
                ConversationMessage.Direction.INBOUND,
                "Gracias, muy amable. " .repeat(70), Instant.parse("2026-10-21T12:00:00Z"), 0.50);
        var memory = new ConversationMemoryContext(key(), "", transcript, protectedFacts, 1,
                Instant.now(), List.of(resolvedSocialTurn, relevantOldTurn));

        var selected = selector.select(memory, CURRENT_MESSAGE, List.of());

        assertThat(selected.durableFacts()).containsExactlyElementsOf(protectedFacts);
        assertThat(selected.recentMessages()).anySatisfy(message ->
                assertThat(message.content()).contains("La XLT tiene la caja"));
        assertThat(selected.recalledTurns()).contains(relevantOldTurn).doesNotContain(resolvedSocialTurn);
        assertThat(selected.estimatedTokens()).isLessThanOrEqualTo(properties.promptTokenBudget());
        assertThat(selected.omittedRecalledTurns()).isPositive();
    }

    static List<ConversationMessage> syntheticThirtyOneTurnHistory() {
        var conversationId = UUID.randomUUID();
        var messages = new ArrayList<ConversationMessage>();
        var start = Instant.parse("2026-10-01T12:00:00Z");
        for (var turn = 1; turn <= 31; turn++) {
            var userText = switch (turn) {
                case 1 -> "Busco vehículo para ciudad y familia.";
                case 2 -> "También salimos de camping.";
                case 8 -> "Corrijo: prefiero gasolina, no híbrida.";
                case 27 -> "Compara Ranger XL contra Ranger XLT, por favor.";
                case 28 -> "La XLT tiene la caja que me atrae.";
                case 29 -> "La XL sería más práctica en ciudad.";
                case 30 -> "Sigo comparando Ranger XL y XLT.";
                case 31 -> CURRENT_MESSAGE;
                default -> turn % 3 == 0 ? "Gracias, muy amable." : "Entendido, continuemos.";
            };
            var time = start.plusSeconds(turn * 60L);
            messages.add(new ConversationMessage(UUID.randomUUID(), conversationId,
                    ConversationMessage.Direction.INBOUND, "in-" + turn, userText, time));
            messages.add(new ConversationMessage(UUID.randomUUID(), conversationId,
                    ConversationMessage.Direction.OUTBOUND, "out-" + turn,
                    turn >= 27 ? "Revisamos Ranger XL y Ranger XLT." : "Con gusto, seguimos.", time.plusSeconds(1)));
        }
        return List.copyOf(messages);
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider", "participant", UUID.randomUUID());
    }
}
