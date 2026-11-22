package com.example.Openstack_ai_agent.remediation;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "healops.verification")
public class VerificationProperties {
    @NotNull
    private Duration stabilizationDelay = Duration.ofSeconds(5);

    public Duration getStabilizationDelay() {
        return stabilizationDelay;
    }

    public void setStabilizationDelay(Duration stabilizationDelay) {
        this.stabilizationDelay = stabilizationDelay;
    }
}
