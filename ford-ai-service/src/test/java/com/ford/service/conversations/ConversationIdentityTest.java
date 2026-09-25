package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ConversationIdentityTest {

    @Test
    void keepsTenantChannelProviderAccountParticipantAndConversationIdentity() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        var key = new ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId);

        assertThat(key.tenantId()).isEqualTo(tenantId);
        assertThat(key.channel()).isEqualTo(Channel.WHATSAPP);
        assertThat(key.providerAccountId()).isEqualTo("phone-1");
        assertThat(key.participantId()).isEqualTo("customer-1");
        assertThat(key.conversationId()).isEqualTo(conversationId);
    }

    @Test
    void rejectsBlankProviderAccountOrParticipant() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() -> new ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, " ", "customer-1", conversationId));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", " ", conversationId));
    }
}
