package com.example.Openstack_ai_agent.remediation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RemediationProperties.class, VerificationProperties.class})
public class RemediationConfiguration {
}
