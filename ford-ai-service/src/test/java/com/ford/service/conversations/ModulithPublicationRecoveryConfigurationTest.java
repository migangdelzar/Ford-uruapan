package com.ford.service.conversations.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ModulithPublicationRecoveryConfigurationTest {

    @Test
    void packagedConfigurationDoesNotBlindlyRepublishOutstandingEventsOnRestart() throws IOException {
        var sources = new YamlPropertySourceLoader().load("application",
                new FileSystemResource("build/resources/main/application.yml"));
        var value = sources.stream()
                .map(source -> source.getProperty("spring.modulith.events.republish-outstanding-events-on-restart"))
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .findFirst();

        assertThat(value).contains(false);
    }
}
