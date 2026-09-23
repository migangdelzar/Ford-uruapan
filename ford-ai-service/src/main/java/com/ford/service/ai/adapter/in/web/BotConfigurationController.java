package com.ford.service.ai.adapter.in.web;

import com.ford.service.ai.api.BotConfigurationView;
import com.ford.service.ai.configuration.AiProperties;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/bot/config")
@PreAuthorize("hasAuthority('SCOPE_bot:manage') || hasRole('ADMIN')")
public class BotConfigurationController {
    private final AiProperties ai;
    private final Environment environment;

    public BotConfigurationController(AiProperties ai, Environment environment) {
        this.ai = ai;
        this.environment = environment;
    }

    @GetMapping
    public BotConfigurationView get() {
        var model = ai.cloudEnabled() ? ai.cloudChatModelVersion() : ai.localChatModelVersion();
        return new BotConfigurationView(
                ai.cloudEnabled() || ai.localEnabled(),
                environment.getProperty("ford.conversations.human-handoff.enabled", Boolean.class, true),
                model, null, Instant.now());
    }
}
