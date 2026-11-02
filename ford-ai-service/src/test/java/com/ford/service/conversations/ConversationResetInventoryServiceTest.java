package com.ford.service.conversations;

import com.ford.service.conversations.application.ConversationResetInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ConversationResetInventoryServiceTest {
    @Test
    void requiresAnExplicitTenantBeforeQueryingAnything() {
        var jdbc = mock(JdbcClient.class);
        var service = new ConversationResetInventoryService(jdbc);

        assertThatThrownBy(() -> service.inventory(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId is required");

        verifyNoInteractions(jdbc);
    }
}
