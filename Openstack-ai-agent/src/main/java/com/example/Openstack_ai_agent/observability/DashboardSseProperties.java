package com.example.Openstack_ai_agent.observability;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "healops.sse")
public class DashboardSseProperties {
    @NotNull
    private Duration emitterTimeout = Duration.ofMinutes(30);

    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    private boolean heartbeatEnabled = true;

    @Min(1)
    private int maxReplayEvents = 500;

    @Min(1)
    private int maxPendingLiveEvents = 200;

    public Duration getEmitterTimeout() {
        return emitterTimeout;
    }

    public void setEmitterTimeout(Duration emitterTimeout) {
        this.emitterTimeout = emitterTimeout;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public boolean isHeartbeatEnabled() {
        return heartbeatEnabled;
    }

    public void setHeartbeatEnabled(boolean heartbeatEnabled) {
        this.heartbeatEnabled = heartbeatEnabled;
    }

    public int getMaxReplayEvents() {
        return maxReplayEvents;
    }

    public void setMaxReplayEvents(int maxReplayEvents) {
        this.maxReplayEvents = maxReplayEvents;
    }

    public int getMaxPendingLiveEvents() {
        return maxPendingLiveEvents;
    }

    public void setMaxPendingLiveEvents(int maxPendingLiveEvents) {
        this.maxPendingLiveEvents = maxPendingLiveEvents;
    }
}
