package com.ford.service.channels.adapter.in.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "ford.channels.meta")
public class MetaWebhookProperties {

    private String appSecret = "";
    private String verifyToken = "";
    private UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getVerifyToken() {
        return verifyToken;
    }

    public void setVerifyToken(String verifyToken) {
        this.verifyToken = verifyToken;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }
}
