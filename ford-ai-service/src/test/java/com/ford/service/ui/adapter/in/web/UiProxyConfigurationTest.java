package com.ford.service.ui.adapter.in.web;

import com.ford.service.ui.application.UiProxyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UiProxyConfigurationTest {

    @Test
    void defaultsToEmmePagesOriginAndKeepsTheFordPublicOrigin() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(UiProxyConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(UiProxyProperties.class);
                    assertThat(properties.upstreamUrl()).isEqualTo(URI.create("https://emme-crm-ui.pages.dev"));
                    assertThat(properties.publicOrigin()).isEqualTo(URI.create("https://ford.emme.business"));
                });
    }

    @Test
    void allowsAConfiguredOverrideForThePagesOrigin() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(UiProxyConfiguration.class)
                .withPropertyValues("ford.web.ui.upstream-url=https://preview.emme-crm-ui.pages.dev")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(UiProxyProperties.class);
                    assertThat(properties.upstreamUrl())
                            .isEqualTo(URI.create("https://preview.emme-crm-ui.pages.dev"));
                    assertThat(properties.publicOrigin()).isEqualTo(URI.create("https://ford.emme.business"));
                });
    }

    @Test
    void retainsTheEnvironmentVariableOverrideInApplicationConfiguration() throws Exception {
        var configuration = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(configuration)
                .contains("upstream-url: ${FORD_WEB_UI_UPSTREAM_URL:https://emme-crm-ui.pages.dev}");
    }
}
