package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryPromptSelector;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMemoryRecall;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.CustomerShoppingFact;
import com.ford.service.conversations.domain.CustomerShoppingFactState;
import com.ford.service.conversations.domain.CustomerShoppingFactType;
import com.ford.service.conversations.domain.MemoryFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryPromptSelectorTest {

    @Test
    void keepsProtectedFactsAndTheNewestFourCompleteTurns() {
        var properties = new MemoryProperties();
        var selector = new ConversationMemoryPromptSelector(properties);
        var facts = List.of(
                new MemoryFact("intended_use", "city and family", UUID.randomUUID()),
                new MemoryFact("vehicle_comparison", "Ranger XL versus XLT", UUID.randomUUID()));
        var messages = turns(6);
        var context = new ConversationMemoryContext(key(), "", messages, facts, 1);

        var selected = selector.select(context, "La XL quizá sea más práctica en ciudad.", List.of());

        assertThat(selected.durableFacts()).containsExactlyElementsOf(facts);
        assertThat(selected.recentMessages()).containsExactlyElementsOf(messages.subList(4, 12));
    }

    @Test
    void ranksOlderRecallsBySimilarityAndDeduplicatesRecentMessages() {
        var selector = new ConversationMemoryPromptSelector(new MemoryProperties());
        var recent = turns(4);
        var duplicate = recent.get(0);
        var strongest = recall(UUID.randomUUID(), "Ranger XL XLT", 0.95, 10);
        var weaker = recall(UUID.randomUUID(), "Saludo resuelto", 0.80, 9);
        var duplicateRecall = new ConversationMemoryRecall(duplicate.id(), duplicate.direction(),
                duplicate.content(), duplicate.occurredAt(), 0.99);
        var context = new ConversationMemoryContext(key(), "", recent, List.of(), 1,
                Instant.now(), List.of(weaker, strongest, duplicateRecall));

        var selected = selector.select(context, "la otra versión", List.of());

        assertThat(selected.recalledTurns()).containsExactly(strongest, weaker);
        assertThat(selected.recentMessages()).containsExactlyElementsOf(recent);
    }

    @Test
    void dropsOptionalHistoryBeforeProtectedFactsToMeetPromptBudget() {
        var properties = new MemoryProperties();
        properties.setPromptTokenBudget(60);
        var selector = new ConversationMemoryPromptSelector(properties);
        var facts = List.of(new MemoryFact("budget", "preserve exact budget 350000", UUID.randomUUID()));
        var shared = new CustomerShoppingFact(UUID.randomUUID(), CustomerShoppingFactType.SHOPPING_BUDGET,
                "budget", "350000 MXN", CustomerShoppingFactState.ACTIVE, UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), 1, Instant.now(), Instant.now(), null, 1);
        var longRecent = List.of(new ConversationMessage(UUID.randomUUID(), key().conversationId(),
                ConversationMessage.Direction.INBOUND, null, "intercambio resuelto ".repeat(30), Instant.now()));
        var context = new ConversationMemoryContext(key(), "", longRecent, facts, 1);

        var selected = selector.select(context, "¿Y la otra?", List.of(shared));

        assertThat(selected.durableFacts()).containsExactlyElementsOf(facts);
        assertThat(selected.sharedFacts()).containsExactly(shared);
        assertThat(selected.estimatedTokens()).isLessThanOrEqualTo(60);
        assertThat(selected.omittedRecentMessages()).isPositive();
    }

    @Test
    void retainsProtectedFactsAndReportsWhenTheyAloneExceedTheBudget() {
        var properties = new MemoryProperties();
        properties.setPromptTokenBudget(1);
        var fact = new MemoryFact("budget", "preserve exact budget 350000", UUID.randomUUID());
        var context = new ConversationMemoryContext(key(), "optional summary", turns(1), List.of(fact), 1);

        var selected = new ConversationMemoryPromptSelector(properties)
                .select(context, "current user message", List.of());

        assertThat(selected.durableFacts()).containsExactly(fact);
        assertThat(selected.summary()).isEmpty();
        assertThat(selected.recentMessages()).isEmpty();
        assertThat(selected.overBudget()).isTrue();
    }

    private static List<ConversationMessage> turns(int count) {
        var key = key();
        var messages = new ArrayList<ConversationMessage>();
        for (var turn = 0; turn < count; turn++) {
            var occurredAt = Instant.parse("2026-10-28T12:00:00Z").plusSeconds(turn * 60L);
            messages.add(new ConversationMessage(UUID.randomUUID(), key.conversationId(),
                    ConversationMessage.Direction.INBOUND, null, "Pregunta " + turn, occurredAt));
            messages.add(new ConversationMessage(UUID.randomUUID(), key.conversationId(),
                    ConversationMessage.Direction.OUTBOUND, null, "Respuesta " + turn, occurredAt.plusSeconds(1)));
        }
        return List.copyOf(messages);
    }

    private static ConversationMemoryRecall recall(UUID id, String content, double similarity, int minute) {
        return new ConversationMemoryRecall(id, ConversationMessage.Direction.INBOUND, content,
                Instant.parse("2026-10-28T10:00:00Z").plusSeconds(minute * 60L), similarity);
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "provider", "participant", UUID.randomUUID());
    }
}
