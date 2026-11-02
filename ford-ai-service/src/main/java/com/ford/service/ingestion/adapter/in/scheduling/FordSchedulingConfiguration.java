package com.ford.service.ingestion.adapter.in.scheduling;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(FordSyncProperties.class)
public class FordSchedulingConfiguration {
}
