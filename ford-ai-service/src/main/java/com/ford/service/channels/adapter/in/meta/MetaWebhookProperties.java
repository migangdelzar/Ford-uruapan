package com.ford.service.channels.adapter.in.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "ford.channels.meta")
public class MetaWebhookProperties {

    private String appSecret = "";
    private String verifyToken = "";
    private final AutoBinding autoBinding = new AutoBinding();
    private final ProviderBinding messengerBinding = new ProviderBinding();
    private final ProviderBinding instagramBinding = new ProviderBinding();

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

    public AutoBinding getAutoBinding() {
        return autoBinding;
    }

    public ProviderBinding getMessengerBinding() { return messengerBinding; }

    public ProviderBinding getInstagramBinding() { return instagramBinding; }

    public UUID getTenantId() {
        return autoBinding.getTenantId();
    }

    public void setTenantId(UUID tenantId) {
        autoBinding.setTenantId(tenantId);
    }

    public static class AutoBinding {

        private boolean enabled;
        private String providerAccountId = "";
        private UUID tenantId;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProviderAccountId() {
            return providerAccountId;
        }

        public void setProviderAccountId(String providerAccountId) {
            this.providerAccountId = providerAccountId;
        }

        public UUID getTenantId() {
            return tenantId;
        }

        public void setTenantId(UUID tenantId) {
            this.tenantId = tenantId;
        }
    }

    public static class ProviderBinding {
        private boolean enabled;
        private String providerAccountId = "";

        public boolean isEnabled() { return enabled; }

        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getProviderAccountId() { return providerAccountId; }

        public void setProviderAccountId(String providerAccountId) { this.providerAccountId = providerAccountId; }
    }
}
