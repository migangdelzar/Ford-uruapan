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
        public boolean graphEnabled() { return graphEnabled; }
        public void setGraphEnabled(boolean value) { graphEnabled = value; }
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
