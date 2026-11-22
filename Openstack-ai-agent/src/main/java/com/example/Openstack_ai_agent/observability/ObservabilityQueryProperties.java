package com.example.Openstack_ai_agent.observability;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "healops.observability")
public class ObservabilityQueryProperties {
    private boolean liveQueryEnabled = true;

    @NotBlank
    private String prometheusBaseUrl = "http://prometheus:9090";

    @NotBlank
    private String lokiBaseUrl = "http://loki:3100";

    @NotBlank
    private String jaegerBaseUrl = "http://jaeger:16686";

    @NotNull
    private Duration timeout = Duration.ofSeconds(3);

    @Min(1024)
    private int maxResponseBytes = 262144;

    @NotNull
    private Duration maxRangeWindow = Duration.ofHours(24);

    @Min(10)
    private int maxDataPoints = 300;

    @Min(1)
    private int defaultLogLimit = 50;

    @Min(1)
    private int maxLogLimit = 200;

    @Min(1)
    private int defaultTraceLimit = 20;

    @Min(1)
    private int maxTraceLimit = 50;

    @Min(1)
    private int maxSpansPerTrace = 200;

    public boolean isLiveQueryEnabled() { return liveQueryEnabled; }
    public void setLiveQueryEnabled(boolean liveQueryEnabled) { this.liveQueryEnabled = liveQueryEnabled; }

    public String getPrometheusBaseUrl() { return prometheusBaseUrl; }
    public void setPrometheusBaseUrl(String prometheusBaseUrl) { this.prometheusBaseUrl = prometheusBaseUrl; }

    public String getLokiBaseUrl() { return lokiBaseUrl; }
    public void setLokiBaseUrl(String lokiBaseUrl) { this.lokiBaseUrl = lokiBaseUrl; }

    public String getJaegerBaseUrl() { return jaegerBaseUrl; }
    public void setJaegerBaseUrl(String jaegerBaseUrl) { this.jaegerBaseUrl = jaegerBaseUrl; }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }

    public Duration getMaxRangeWindow() { return maxRangeWindow; }
    public void setMaxRangeWindow(Duration maxRangeWindow) { this.maxRangeWindow = maxRangeWindow; }

    public int getMaxDataPoints() { return maxDataPoints; }
    public void setMaxDataPoints(int maxDataPoints) { this.maxDataPoints = maxDataPoints; }

    public int getDefaultLogLimit() { return defaultLogLimit; }
    public void setDefaultLogLimit(int defaultLogLimit) { this.defaultLogLimit = defaultLogLimit; }

    public int getMaxLogLimit() { return maxLogLimit; }
    public void setMaxLogLimit(int maxLogLimit) { this.maxLogLimit = maxLogLimit; }

    public int getDefaultTraceLimit() { return defaultTraceLimit; }
    public void setDefaultTraceLimit(int defaultTraceLimit) { this.defaultTraceLimit = defaultTraceLimit; }

    public int getMaxTraceLimit() { return maxTraceLimit; }
    public void setMaxTraceLimit(int maxTraceLimit) { this.maxTraceLimit = maxTraceLimit; }

    public int getMaxSpansPerTrace() { return maxSpansPerTrace; }
    public void setMaxSpansPerTrace(int maxSpansPerTrace) { this.maxSpansPerTrace = maxSpansPerTrace; }
}
