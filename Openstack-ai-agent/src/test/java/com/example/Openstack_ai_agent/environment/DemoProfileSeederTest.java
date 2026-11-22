package com.example.Openstack_ai_agent.environment;

import com.example.Openstack_ai_agent.runbook.RunbookEntity;
import com.example.Openstack_ai_agent.runbook.RunbookRepository;
import com.example.Openstack_ai_agent.runbook.RunbookStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles({"test", "demo"})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:healops_demo_profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class DemoProfileSeederTest {

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private RunbookRepository runbookRepository;

    @Autowired
    private UserDetailsService userDetailsService;

    @Test
    void demoProfileSeedsDemoEnvironmentPublishedRunbookAndDemoAuthorities() {
        EnvironmentEntity demoEnv = environmentRepository.findById(DemoProfileSeeder.DEMO_ENVIRONMENT_ID)
                .orElseThrow();
        assertThat(demoEnv.getKind()).isEqualTo("DEMO");
        assertThat(demoEnv.getDisplayLabel()).isEqualTo("Demo Sandbox");
        assertThat(demoEnv.getCapabilities()).containsEntry("demoWorkload", true);
        assertThat(demoEnv.getCapabilities()).containsEntry("remediationApprovalRequired", true);

        RunbookEntity demoRunbook = runbookRepository.findAll().stream()
                .filter(rb -> DemoProfileSeeder.DEMO_RUNBOOK_NAME.equals(rb.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(demoRunbook.getStatus()).isEqualTo(RunbookStatus.PUBLISHED);

        UserDetails operator = userDetailsService.loadUserByUsername("operator");
        assertThat(operator.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("env:demo", "env:local", "incident:approve");

        UserDetails viewer = userDetailsService.loadUserByUsername("viewer");
        assertThat(viewer.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("env:demo", "incident:read")
                .doesNotContain("incident:approve");
    }
}
