package com.ford.service.conversations;

import com.ford.service.conversations.application.MemoryProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPropertiesTest {

    @Test
    void usesDurableTranscriptDefaultsAndKeepsSemanticRecallOptIn() {
        var properties = new MemoryProperties();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.recentMessageLimit()).isEqualTo(20);
        assertThat(properties.summaryMaxChars()).isEqualTo(4000);
        assertThat(properties.factLimit()).isEqualTo(12);
        assertThat(properties.factMaxChars()).isEqualTo(500);
        assertThat(properties.semanticEnabled()).isFalse();
        assertThat(properties.semanticLimit()).isEqualTo(5);
        assertThat(properties.cacheEnabled()).isFalse();
    }
}
