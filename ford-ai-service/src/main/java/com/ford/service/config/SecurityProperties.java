package com.ford.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.security")
public class SecurityProperties {

    private Jwt jwt = new Jwt();

    public Jwt jwt() {
        return jwt;
    }

    public void setJwt(Jwt value) {
        jwt = value;
    }

    public static class Jwt {
        private boolean enabled;
        private String issuerUri = "";
        private String audience = "ford-ai-service";

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public String issuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String value) {
            issuerUri = value;
        }

        public String audience() {
            return audience;
        }

        public void setAudience(String value) {
            audience = value;
        }
    }
}
