package com.ford.service.ui.adapter.in.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class UiProxyConfiguration {
    @Bean
    com.ford.service.ui.application.UiProxyProperties uiProxyProperties(
            @Value("${ford.web.ui.upstream-url:https://emme-crm-ui.pages.dev}") java.net.URI upstreamUrl,
            @Value("${ford.web.ui.public-origin:https://ford.emme.business}") java.net.URI publicOrigin) {
        return new com.ford.service.ui.application.UiProxyProperties(upstreamUrl, publicOrigin);
    }

    @Bean
    RestClient uiProxyRestClient() {
        return RestClient.builder().build();
    }
}
