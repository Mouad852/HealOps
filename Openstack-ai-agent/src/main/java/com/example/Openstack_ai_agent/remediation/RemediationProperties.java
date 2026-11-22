package com.example.Openstack_ai_agent.remediation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "healops.remediation")
public class RemediationProperties {
    private boolean liveAdapterEnabled = true;

    @NotNull
    private Duration executionTimeout = Duration.ofSeconds(10);

    @Min(1024)
    private int maxResponseBytes = 32768;

    public boolean isLiveAdapterEnabled() {
        return liveAdapterEnabled;
    }

    public void setLiveAdapterEnabled(boolean liveAdapterEnabled) {
        this.liveAdapterEnabled = liveAdapterEnabled;
    }

    public Duration getExecutionTimeout() {
        return executionTimeout;
    }

    public void setExecutionTimeout(Duration executionTimeout) {
        this.executionTimeout = executionTimeout;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }
}
