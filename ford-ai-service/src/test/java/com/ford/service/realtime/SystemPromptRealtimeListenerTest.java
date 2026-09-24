package com.ford.service.realtime;

import com.ford.service.ai.domain.SystemPromptChanged;
import com.ford.service.channels.adapter.in.meta.MetaWebhookProperties;
import com.ford.service.realtime.application.RealtimeEventHub;
import com.ford.service.realtime.application.SystemPromptRealtimeListener;
import com.ford.service.realtime.domain.FrontendEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SystemPromptRealtimeListenerTest {

    @Test
    void emitsMetadataOnlyPromptUpdateForConnectedOperators() {
        var hub = mock(RealtimeEventHub.class);
        var tenantId = UUID.randomUUID();
        var properties = new MetaWebhookProperties();
        properties.setTenantId(tenantId);
        var listener = new SystemPromptRealtimeListener(hub, properties);
        var changedAt = Instant.parse("2026-09-23T12:00:00Z");

        listener.on(new SystemPromptChanged(4, "operator", changedAt));

        var event = forClass(FrontendEvent.class);
        verify(hub).publish(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(event.getValue().type()).isEqualTo("ai.system_prompt.updated");
        assertThat(event.getValue().data()).containsEntry("version", 4L);
        assertThat(event.getValue().data()).doesNotContainKey("content");
    }
}
