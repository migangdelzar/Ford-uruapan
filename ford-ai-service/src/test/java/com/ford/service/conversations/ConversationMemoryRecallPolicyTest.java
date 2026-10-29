package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryRecallPolicy;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryRecallPolicyTest {

    private final ConversationMemoryRecallPolicy policy = new ConversationMemoryRecallPolicy();
    private final ConversationMemoryContext context = ConversationMemoryContext.empty(
            new ConversationMemoryKey(
                    UUID.randomUUID(), Channel.WHATSAPP, "provider", "participant", UUID.randomUUID()));

    @Test
    void recallsExplicitHistoricalQuestionsInSpanishAndEnglish() {
        assertThat(policy.shouldRecall("¿Qué presupuesto le había dicho?", context)).isTrue();
        assertThat(policy.shouldRecall("What model did I ask about before?", context)).isTrue();
    }

    @Test
    void doesNotUseConversationRecallForCurrentFordInformation() {
        assertThat(policy.shouldRecall("¿Qué promociones tienen?", context)).isFalse();
        assertThat(policy.shouldRecall("¿Qué modelos tienen disponibles?", context)).isFalse();
    }

    @Test
    void recallsContextDependentReferencesForGroundedMemoryResolution() {
        assertThat(policy.shouldRecall("la otra versión", context)).isTrue();
        assertThat(policy.shouldRecall("esa opción quizá sea más práctica", context)).isTrue();
        assertThat(policy.shouldRecall("¿Qué promociones tienen?", context)).isFalse();
    }

    @Test
    void capsRecallResults() {
        assertThat(policy.limit(10)).isEqualTo(3);
        assertThat(policy.limit(2)).isEqualTo(2);
    }
}
