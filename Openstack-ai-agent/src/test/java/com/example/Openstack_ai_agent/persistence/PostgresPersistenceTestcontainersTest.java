package com.example.Openstack_ai_agent.persistence;

import com.example.Openstack_ai_agent.audit.AuditOutcome;
import com.example.Openstack_ai_agent.audit.AuditRecordEntity;
import com.example.Openstack_ai_agent.audit.AuditRecordRepository;
import com.example.Openstack_ai_agent.config.PersistenceConfiguration;
import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.remediation.ActionRiskLevel;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.remediation.ProposedActionEntity;
import com.example.Openstack_ai_agent.remediation.ProposedActionRepository;
import com.example.Openstack_ai_agent.remediation.ValidatedActionProposal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Flyway PostgreSQL migrations (V1-V4), unique deduplication constraints,
 * JSONB persistence, and JPA optimistic locking against a pinned PostgreSQL 17.4 container.
 * Automatically skipped on developer workstations where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PersistenceConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration/postgresql",
        "spring.flyway.validate-on-migrate=true",
        "spring.flyway.validate-migration-naming=true",
        "spring.flyway.clean-disabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class PostgresPersistenceTestcontainersTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.4-alpine");

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ProposedActionRepository proposedActionRepository;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void enforcesUniqueDeduplicationConstraintOnIncidents() {
        CreateIncidentRequest req = new CreateIncidentRequest(
                "alertmanager",
                "pg-ext-1",
                "pg-unique-fp-1",
                "local",
                IncidentSeverity.HIGH,
                "checkout-api",
                OffsetDateTime.now().minusSeconds(5),
                Map.of("namespace", "payments"),
                Map.of("summary", "PostgreSQL unique deduplication test"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        );

        IncidentEntity saved = incidentRepository.saveAndFlush(IncidentEntity.alertReceived(req));
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0L);

        assertThatThrownBy(() -> incidentRepository.saveAndFlush(IncidentEntity.alertReceived(req)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsJsonbAuditRecordsAgainstFlywaySchema() {
        CreateIncidentRequest req = new CreateIncidentRequest(
                "alertmanager",
                "pg-ext-audit",
                "pg-audit-fp-1",
                "local",
                IncidentSeverity.MEDIUM,
                "checkout-api",
                OffsetDateTime.now().minusSeconds(5),
                Map.of("namespace", "payments"),
                Map.of("summary", "PostgreSQL JSONB audit test"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        );

        IncidentEntity saved = incidentRepository.saveAndFlush(IncidentEntity.alertReceived(req));
        AuditRecordEntity audit = auditRecordRepository.saveAndFlush(AuditRecordEntity.create(
                "operator",
                "POLICY_CHECK",
                "incident:" + saved.getId(),
                AuditOutcome.SUCCESS,
                Map.of("policyVersion", "policy-v1.0.0", "environmentId", "local"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        ));
        entityManager.clear();

        AuditRecordEntity reloaded = auditRecordRepository.findById(audit.getId()).orElseThrow();
        assertThat(reloaded.getDetails())
                .containsEntry("policyVersion", "policy-v1.0.0")
                .containsEntry("environmentId", "local");
    }

    @Test
    void enforcesOptimisticLockingVersionOnProposedActions() {
        IncidentEntity incident = incidentRepository.saveAndFlush(IncidentEntity.alertReceived(new CreateIncidentRequest(
                "alertmanager",
                "pg-ext-lock",
                "pg-lock-fp-1",
                "local",
                IncidentSeverity.CRITICAL,
                "search-api",
                OffsetDateTime.now().minusSeconds(5),
                Map.of(),
                Map.of(),
                null
        )));

        ProposedActionEntity action = proposedActionRepository.saveAndFlush(ProposedActionEntity.proposed(
                incident.getId(),
                null,
                new ValidatedActionProposal(
                        ActionType.RESTART_CONTAINER,
                        Map.of("container", "healops-demo-workload"),
                        ActionRiskLevel.LOW,
                        "NONE",
                        "docker restart healops-demo-workload"
                )
        ));

        entityManager.detach(action);

        // Simulate concurrent update incrementing version in database
        entityManager.createNativeQuery("update proposed_actions set status = 'APPROVED', version = version + 1 where id = ?1")
                .setParameter(1, action.getId())
                .executeUpdate();
        entityManager.flush();

        // Merging stale detached entity with version=0 must raise optimistic locking failure
        assertThatThrownBy(() -> {
            proposedActionRepository.saveAndFlush(action);
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void executesKeysetPaginationQueryAgainstPostgresIndex() {
        for (int i = 0; i < 4; i++) {
            incidentRepository.saveAndFlush(IncidentEntity.alertReceived(new CreateIncidentRequest(
                    "alertmanager",
                    "pg-keyset-ext-" + i,
                    "pg-keyset-fp-" + i,
                    "local",
                    IncidentSeverity.HIGH,
                    "checkout-api",
                    OffsetDateTime.now().minusMinutes(5 - i),
                    Map.of(),
                    Map.of(),
                    null
            )));
        }

        List<IncidentEntity> firstPage = incidentRepository.findKeysetPage(
                Set.of("local"), null, null, null, null, 3);
        assertThat(firstPage).hasSize(3);

        IncidentEntity lastOfFirst = firstPage.get(1);
        List<IncidentEntity> secondPage = incidentRepository.findKeysetPage(
                Set.of("local"),
                null,
                null,
                lastOfFirst.getCreatedAt(),
                lastOfFirst.getId(),
                3);
        assertThat(secondPage).isNotEmpty();
        assertThat(secondPage.getFirst().getId()).isEqualTo(firstPage.get(2).getId());
    }
}
