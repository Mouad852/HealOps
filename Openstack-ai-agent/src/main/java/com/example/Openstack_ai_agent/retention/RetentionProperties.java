package com.example.Openstack_ai_agent.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "healops.retention")
public class RetentionProperties {
    private boolean enabled = true;
    private String cron = "0 0 * * * *";
    private Duration dashboardEventTtl = Duration.ofDays(7);
    private Duration telemetryReferenceTtl = Duration.ofDays(30);
    private Duration conversationTtl = Duration.ofDays(30);
    private int maxBatchSize = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public Duration getDashboardEventTtl() {
        return dashboardEventTtl;
    }

    public void setDashboardEventTtl(Duration dashboardEventTtl) {
        if (dashboardEventTtl != null && !dashboardEventTtl.isNegative() && !dashboardEventTtl.isZero()) {
            this.dashboardEventTtl = dashboardEventTtl;
        }
    }

    public Duration getTelemetryReferenceTtl() {
        return telemetryReferenceTtl;
    }

    public void setTelemetryReferenceTtl(Duration telemetryReferenceTtl) {
        if (telemetryReferenceTtl != null && !telemetryReferenceTtl.isNegative() && !telemetryReferenceTtl.isZero()) {
            this.telemetryReferenceTtl = telemetryReferenceTtl;
        }
    }

    public Duration getConversationTtl() {
        return conversationTtl;
    }

    public void setConversationTtl(Duration conversationTtl) {
        if (conversationTtl != null && !conversationTtl.isNegative() && !conversationTtl.isZero()) {
            this.conversationTtl = conversationTtl;
        }
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = Math.max(1, Math.min(maxBatchSize, 5000));
    }
}
