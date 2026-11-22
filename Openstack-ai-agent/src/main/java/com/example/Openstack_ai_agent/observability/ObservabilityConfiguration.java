package com.example.Openstack_ai_agent.observability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        DashboardSseProperties.class,
        ObservabilityQueryProperties.class
})
public class ObservabilityConfiguration {
}
