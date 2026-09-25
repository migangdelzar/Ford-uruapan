package com.ford.service.conversations.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.conversations")
public class ConversationProperties {

    private HumanHandoff humanHandoff = new HumanHandoff();

    public boolean humanHandoffEnabled() {
        return humanHandoff.enabled();
    }

    public HumanHandoff humanHandoff() {
        return humanHandoff;
    }

    public void setHumanHandoff(HumanHandoff value) {
        humanHandoff = value;
    }

    public static class HumanHandoff {
        private boolean enabled = true;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }
    }
}
