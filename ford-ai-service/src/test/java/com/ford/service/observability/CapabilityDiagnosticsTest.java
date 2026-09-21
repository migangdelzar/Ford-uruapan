package com.ford.service.observability;

import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.conversations.application.ConversationProperties;
import com.ford.service.outbox.OutboxProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityDiagnosticsTest {

    @Test
    void summarizesCapabilitiesWithoutSecrets() {
        var ai = new AiProperties();
        ai.setCloudEnabled(true);
        ai.setLocalEnabled(false);
        ai.setCloudChatProvider("openai");
        ai.setCloudChatModelVersion("private-model?token=secret");
        var conversations = new ConversationProperties();
        var outbox = new OutboxProperties();

        var diagnostics = new CapabilityDiagnostics(ai, conversations, outbox,
                false, false, false);

        var summary = diagnostics.summary();

        assertThat(summary).contains("humanHandoff=enabled");
        assertThat(summary).contains("ai.cloud=enabled");
        assertThat(summary).contains("ai.local=disabled");
        assertThat(summary).contains("meta.outbound=disabled");
        assertThat(summary).contains("outbox=disabled");
        assertThat(summary).contains("observability.traces=disabled");
        assertThat(summary).doesNotContain("private-model", "token=secret");
    }
}
