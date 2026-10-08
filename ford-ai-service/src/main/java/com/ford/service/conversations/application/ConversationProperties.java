package com.ford.service.conversations.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.conversations")
public class ConversationProperties {

    private HumanHandoff humanHandoff = new HumanHandoff();
    private Workflow workflow = new Workflow();

    public boolean humanHandoffEnabled() {
        return humanHandoff.enabled();
    }

    public HumanHandoff humanHandoff() {
        return humanHandoff;
    }

    public void setHumanHandoff(HumanHandoff value) {
        humanHandoff = value;
    }

    public boolean graphEnabled() { return workflow.graphEnabled(); }
    public Workflow workflow() { return workflow; }
    public void setWorkflow(Workflow value) { workflow = value; }

    public static class Workflow {
        private boolean graphEnabled;
        private int turnConcurrency = 1;
        private int turnQueueCapacity = 8;
        private java.time.Duration publicationRetryAge = java.time.Duration.ofMinutes(1);
        public boolean graphEnabled() { return graphEnabled; }
        public void setGraphEnabled(boolean value) { graphEnabled = value; }
        public int turnConcurrency() { return turnConcurrency; }
        public void setTurnConcurrency(int value) {
            if (value < 1 || value > 5) throw new IllegalArgumentException("turnConcurrency must be between 1 and 5");
            turnConcurrency = value;
        }
        public java.time.Duration publicationRetryAge() { return publicationRetryAge; }
        public void setPublicationRetryAge(java.time.Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("publicationRetryAge must be positive");
            }
            publicationRetryAge = value;
        }
        public int turnQueueCapacity() { return turnQueueCapacity; }
        public void setTurnQueueCapacity(int value) {
            if (value < 1 || value > 100) throw new IllegalArgumentException("turnQueueCapacity must be between 1 and 100");
            turnQueueCapacity = value;
        }
    }

    public static class HumanHandoff {
        private boolean enabled;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }
    }
}
