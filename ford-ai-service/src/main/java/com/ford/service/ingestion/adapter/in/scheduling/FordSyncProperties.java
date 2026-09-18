package com.ford.service.ingestion.adapter.in.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "ford.ingestion")
public record FordSyncProperties(UUID tenantId) {
}
