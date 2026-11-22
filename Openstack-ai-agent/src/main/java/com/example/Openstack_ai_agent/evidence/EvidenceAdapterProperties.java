package com.example.Openstack_ai_agent.evidence;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "healops.evidence.adapter")
public class EvidenceAdapterProperties {
    @NotNull
    private Duration timeout = Duration.ofSeconds(3);

    @Min(1024)
    private int maxResponseBytes = 32768;

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }
}
