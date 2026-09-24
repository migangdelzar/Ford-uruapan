package com.ford.service.realtime.application;

import com.ford.service.ai.domain.SystemPromptChanged;
import com.ford.service.channels.adapter.in.meta.MetaWebhookProperties;
import com.ford.service.realtime.domain.FrontendEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class SystemPromptRealtimeListener {

    private final RealtimeEventHub hub;
    private final UUID defaultTenantId;

    public SystemPromptRealtimeListener(RealtimeEventHub hub, MetaWebhookProperties properties) {
        this.hub = hub;
        this.defaultTenantId = properties.getTenantId();
    }

    @ApplicationModuleListener
    public void on(SystemPromptChanged changed) {
        hub.publish(new FrontendEvent(
                UUID.randomUUID(),
                defaultTenantId,
                null,
                "ai.system_prompt.updated",
                Instant.now(),
                Map.of("version", changed.version(), "updatedAt", changed.changedAt().toString())));
    }
}
