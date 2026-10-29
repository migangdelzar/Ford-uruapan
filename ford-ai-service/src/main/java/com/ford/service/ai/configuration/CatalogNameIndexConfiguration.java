package com.ford.service.ai.configuration;

import com.ford.service.ai.application.CatalogNameIndexProperties;
import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.catalog.api.CatalogPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(CatalogPort.class)
@EnableConfigurationProperties(CatalogNameIndexProperties.class)
public class CatalogNameIndexConfiguration {

    @Bean
    CatalogNameIndexService catalogNameIndexService(CatalogPort catalog, CatalogNameIndexProperties properties) {
        return new CatalogNameIndexService(catalog, properties, Clock.systemUTC());
    }
}
