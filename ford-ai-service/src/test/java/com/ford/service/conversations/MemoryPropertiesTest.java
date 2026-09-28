package com.ford.service.conversations;

import com.ford.service.conversations.application.MemoryProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPropertiesTest {

    @Test
    void usesDurableTranscriptDefaultsAndKeepsSemanticRecallOptIn() {
        var properties = new MemoryProperties();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.recentMessageLimit()).isEqualTo(20);
        assertThat(properties.promptRecentMessageLimit()).isEqualTo(8);
        assertThat(properties.summaryMaxChars()).isEqualTo(4000);
        assertThat(properties.factLimit()).isEqualTo(12);
        assertThat(properties.factMaxChars()).isEqualTo(500);
        assertThat(properties.semanticEnabled()).isFalse();
        assertThat(properties.semanticLimit()).isEqualTo(5);
        assertThat(properties.semanticRecallEnabled()).isFalse();
        assertThat(properties.semanticRecallLimit()).isEqualTo(3);
        assertThat(properties.semanticRecallMinimumSimilarity()).isEqualTo(0.78);
        assertThat(properties.semanticEmbeddingModel()).isEqualTo("nomic-embed-text");
        assertThat(properties.cacheEnabled()).isFalse();
        assertThat(properties.cacheTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.workingMemoryTtl()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void rejectsPromptWindowLargerThanDurableRecentWindow() {
        var properties = new MemoryProperties();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> properties.setPromptRecentMessageLimit(properties.recentMessageLimit() + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("promptRecentMessageLimit cannot exceed recentMessageLimit");
    }
}
