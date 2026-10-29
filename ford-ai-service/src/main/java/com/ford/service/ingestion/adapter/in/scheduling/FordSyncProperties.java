package com.ford.service.ingestion.adapter.in.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "ford.ingestion")
public class FordSyncProperties {
    private UUID tenantId;
    private String catalogEndpoint = "";
    private String inventoryEndpoint = "";
    private String scraperEndpoint = "";
    private String manualCsvPath = "";
    private int maxAttempts = 3;

    public UUID tenantId() { return tenantId; }
    public String catalogEndpoint() { return catalogEndpoint; }
    public String inventoryEndpoint() { return inventoryEndpoint; }
    public String scraperEndpoint() { return scraperEndpoint; }
    public String manualCsvPath() { return manualCsvPath; }
    public int maxAttempts() { return maxAttempts; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public void setCatalogEndpoint(String value) { this.catalogEndpoint = value; }
    public void setInventoryEndpoint(String value) { this.inventoryEndpoint = value; }
    public void setScraperEndpoint(String value) { this.scraperEndpoint = value; }
    public void setManualCsvPath(String value) { this.manualCsvPath = value; }
    public void setMaxAttempts(int value) { this.maxAttempts = value; }
}
