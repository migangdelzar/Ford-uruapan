package com.ford.service.conversations.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.conversations")
public class ConversationProperties {

    private HumanHandoff humanHandoff = new HumanHandoff();
    private Workflow workflow = new Workflow();
    private Hours hours = new Hours();
    private ShadowReview shadowReview = new ShadowReview();

    public boolean humanHandoffEnabled() {
        return humanHandoff.enabled();
    }

    public Hours hours() { return hours; }
    public void setHours(Hours value) { hours = value; }

    public boolean shadowReviewEnabled() { return shadowReview.enabled(); }
    public boolean shadowReviewKillSwitch() { return shadowReview.killSwitch(); }
    public ShadowReview shadowReview() { return shadowReview; }
    public void setShadowReview(ShadowReview value) { shadowReview = value; }

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

    public static class Hours {
        private String zone = "America/Mexico_City";
        private java.time.LocalTime weekdayOpens = java.time.LocalTime.of(9, 0);
        private java.time.LocalTime weekdayCloses = java.time.LocalTime.of(19, 0);
        private java.time.LocalTime saturdayOpens = java.time.LocalTime.of(9, 0);
        private java.time.LocalTime saturdayCloses = java.time.LocalTime.of(14, 0);
        private java.util.Map<String, String> calendar = new java.util.LinkedHashMap<>();

        public String zone() { return zone; }
        public void setZone(String value) { zone = value; }
        public java.time.LocalTime weekdayOpens() { return weekdayOpens; }
        public void setWeekdayOpens(java.time.LocalTime value) { weekdayOpens = value; }
        public java.time.LocalTime weekdayCloses() { return weekdayCloses; }
        public void setWeekdayCloses(java.time.LocalTime value) { weekdayCloses = value; }
        public java.time.LocalTime saturdayOpens() { return saturdayOpens; }
        public void setSaturdayOpens(java.time.LocalTime value) { saturdayOpens = value; }
        public java.time.LocalTime saturdayCloses() { return saturdayCloses; }
        public void setSaturdayCloses(java.time.LocalTime value) { saturdayCloses = value; }
        public java.util.Map<String, String> calendar() { return calendar; }
        public void setCalendar(java.util.Map<String, String> value) { calendar = value; }
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

    /** Shadow review is opt-in; the kill switch always suppresses bot replies. */
    public static class ShadowReview {
        private boolean enabled;
        private boolean killSwitch;
        public boolean enabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public boolean killSwitch() { return killSwitch; }
        public void setKillSwitch(boolean value) { killSwitch = value; }
    }
}
