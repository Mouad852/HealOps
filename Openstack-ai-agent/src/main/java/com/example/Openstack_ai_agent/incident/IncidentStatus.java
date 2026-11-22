package com.example.Openstack_ai_agent.incident;

public enum IncidentStatus {
    ALERTED,
    GATHERING_EVIDENCE,
    CORRELATING_SIGNALS,
    DIAGNOSING,
    AWAITING_APPROVAL,
    EXECUTING,
    VERIFYING,
    CLOSED,
    ROLLING_BACK,
    ROLLED_BACK,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == CLOSED || this == ROLLED_BACK || this == FAILED || this == CANCELLED;
    }
}
