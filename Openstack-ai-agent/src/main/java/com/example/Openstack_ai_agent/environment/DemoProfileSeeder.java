package com.example.Openstack_ai_agent.environment;

import com.example.Openstack_ai_agent.remediation.ActionRiskLevel;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.runbook.RunbookEntity;
import com.example.Openstack_ai_agent.runbook.RunbookRepository;
import com.example.Openstack_ai_agent.runbook.RunbookStep;
import com.example.Openstack_ai_agent.runbook.RunbookVersionEntity;
import com.example.Openstack_ai_agent.runbook.RunbookVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds safe demo-only environment and runbook records exclusively when the {@code demo}
 * Spring profile is active. Never executes in default, test, or production profiles.
 */
@Component
@Profile("demo")
public class DemoProfileSeeder {
    public static final String DEMO_ENVIRONMENT_ID = "demo";
    public static final String DEMO_RUNBOOK_NAME = "Demo Workload Latency & Error Recovery";

    private static final Logger log = LoggerFactory.getLogger(DemoProfileSeeder.class);

    private final EnvironmentRepository environmentRepository;
    private final RunbookRepository runbookRepository;
    private final RunbookVersionRepository runbookVersionRepository;

    public DemoProfileSeeder(
            EnvironmentRepository environmentRepository,
            RunbookRepository runbookRepository,
            RunbookVersionRepository runbookVersionRepository
    ) {
        this.environmentRepository = environmentRepository;
        this.runbookRepository = runbookRepository;
        this.runbookVersionRepository = runbookVersionRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    @Transactional
    public void seedDemoResources() {
        seedDemoEnvironment();
        seedDemoRunbook();
    }

    private void seedDemoEnvironment() {
        if (environmentRepository.existsById(DEMO_ENVIRONMENT_ID)) {
            return;
        }
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("docker", true);
        capabilities.put("kubernetes", false);
        capabilities.put("openstack", false);
        capabilities.put("prometheus", true);
        capabilities.put("loki", true);
        capabilities.put("jaeger", true);
        capabilities.put("sseLiveUpdates", true);
        capabilities.put("remediationApprovalRequired", true);
        capabilities.put("demoWorkload", true);

        environmentRepository.save(EnvironmentEntity.create(
                DEMO_ENVIRONMENT_ID,
                "demo",
                "DEMO",
                "Demo Sandbox",
                true,
                capabilities
        ));
        log.info("Seeded demo-only environment '{}'", DEMO_ENVIRONMENT_ID);
    }

    private void seedDemoRunbook() {
        if (runbookRepository.existsByNameIgnoreCase(DEMO_RUNBOOK_NAME)) {
            return;
        }
        Map<String, Object> matchCriteria = Map.of(
                "environmentId", DEMO_ENVIRONMENT_ID,
                "service", "demo-workload",
                "symptoms", List.of("latency", "error_rate", "unhealthy"),
                "tags", List.of("demo", "docker", "synthetic")
        );

        RunbookEntity runbook = RunbookEntity.createPublished(DEMO_RUNBOOK_NAME, matchCriteria);
        runbookRepository.save(runbook);

        RunbookStep restartStep = new RunbookStep(
                1,
                ActionType.RESTART_CONTAINER,
                Map.of("container", "healops-demo-workload"),
                "Restart the isolated demo-workload container to clear injected latency/error faults.",
                "docker restart healops-demo-workload",
                ActionRiskLevel.LOW,
                "NONE"
        );

        RunbookVersionEntity version = RunbookVersionEntity.create(
                runbook.getId(),
                1,
                List.of(restartStep),
                Map.of(
                        "approvalRequired", true,
                        "requiredAuthority", "incident:approve",
                        "allowedEnvironments", List.of(DEMO_ENVIRONMENT_ID)
                ),
                "demo-seeder"
        );
        runbookVersionRepository.save(version);
        log.info("Seeded demo-only published runbook '{}'", DEMO_RUNBOOK_NAME);
    }
}
