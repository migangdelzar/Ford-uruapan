package com.ford.service.operator.application;

public final class OperatorAuthorities {
    public static final String OPERATOR_READ = "SCOPE_operator:read";
    public static final String OPERATOR_WRITE = "SCOPE_operator:write";
    public static final String HANDOFF_MANAGE = "SCOPE_handoff:manage";
    public static final String LEADS_READ = "SCOPE_leads:read";
    public static final String LEADS_WRITE = "SCOPE_leads:write";
    public static final String METRICS_READ = "SCOPE_metrics:read";
    public static final String EVENTS_READ = "SCOPE_events:read";
    public static final String INVENTORY_READ = "SCOPE_inventory:read";
    public static final String FINANCE_QUOTE = "SCOPE_finance:quote";
    public static final String APPOINTMENTS_READ = "SCOPE_appointments:read";
    public static final String APPOINTMENTS_WRITE = "SCOPE_appointments:write";
    public static final String BOT_MANAGE = "SCOPE_bot:manage";
    public static final String TESTLAB_RUN = "SCOPE_testlab:run";

    private OperatorAuthorities() {
    }
}
