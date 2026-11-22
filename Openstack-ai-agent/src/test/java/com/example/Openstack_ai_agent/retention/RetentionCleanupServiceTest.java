package com.example.Openstack_ai_agent.retention;

import com.example.Openstack_ai_agent.audit.AuditOutcome;
import com.example.Openstack_ai_agent.audit.AuditRecordEntity;
import com.example.Openstack_ai_agent.audit.AuditRecordRepository;
import com.example.Openstack_ai_agent.conversation.ConversationMessageEntity;
import com.example.Openstack_ai_agent.conversation.ConversationMessageRepository;
import com.example.Openstack_ai_agent.conversation.ConversationMessageRole;
import com.example.Openstack_ai_agent.conversation.ConversationSessionEntity;
import com.example.Openstack_ai_agent.conversation.ConversationSessionRepository;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisEntity;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository;
import com.example.Openstack_ai_agent.environment.DemoProfileSeeder;
import com.example.Openstack_ai_agent.environment.EnvironmentRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.evidence.RedactionStatus;
import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentEventRepository;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.observability.DashboardEventEntity;
import com.example.Openstack_ai_agent.observability.DashboardEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RetentionCleanupServiceTest {

    @Autowired
    private RetentionCleanupService retentionCleanupService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IncidentEventRepository incidentEventRepository;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private DashboardEventRepository dashboardEventRepository;

    @Autowired
    private EvidenceItemRepository evidenceItemRepository;

    @Autowired
    private DiagnosisRepository diagnosisRepository;

    @Autowired
    private ConversationSessionRepository conversationSessionRepository;

    @Autowired
    private ConversationMessageRepository conversationMessageRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deletesOnlyEligibleExpiredRowsAndNeverDeletesImmutableAuditOrIncidentRecords() {
        assertThat(applicationContext.getBeansOfType(DemoProfileSeeder.class)).isEmpty();
        assertThat(environmentRepository.existsById(DemoProfileSeeder.DEMO_ENVIRONMENT_ID)).isFalse();
        OffsetDateTime now = OffsetDateTime.now();

        // Create an incident + timeline event + audit record and backdate them far past all TTLs (365 days)
        UUID incidentId = incidentService.create(new CreateIncidentRequest(
                "alertmanager",
                "retention-ext-" + UUID.randomUUID(),
                "retention-fp-" + UUID.randomUUID(),
                "local",
                IncidentSeverity.HIGH,
                "retention-service",
                now.minusDays(365),
                Map.of("env", "local"),
                Map.of("summary", "Old incident that must remain immutable"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        )).incident().id();

        jdbcTemplate.update(
                "update incidents set created_at = ?, updated_at = ? where id = ?",
                Timestamp.from(now.minusDays(365).toInstant()),
                Timestamp.from(now.minusDays(365).toInstant()),
                incidentId
        );
        jdbcTemplate.update(
                "update incident_events set occurred_at = ? where incident_id = ?",
                Timestamp.from(now.minusDays(365).toInstant()),
                incidentId
        );

        AuditRecordEntity auditRecord = auditRecordRepository.saveAndFlush(AuditRecordEntity.create(
                "operator",
                "APPROVAL_DECISION",
                "incident:" + incidentId,
                AuditOutcome.SUCCESS,
                Map.of("reason", "Historical immutable audit record"),
                "trace-immutable-1"
        ));
        jdbcTemplate.update(
                "update audit_records set occurred_at = ? where id = ?",
                Timestamp.from(now.minusDays(365).toInstant()),
                auditRecord.getId()
        );

        // Dashboard events: 1 expired (20 days old > 7d TTL), 1 fresh (1 day old)
        DashboardEventEntity expiredDashboardEvent = dashboardEventRepository.saveAndFlush(
                DashboardEventEntity.of("local", "incident.updated", incidentId, "trace-old", Map.of("old", true))
        );
        jdbcTemplate.update(
                "update dashboard_events set occurred_at = ? where id = ?",
                Timestamp.from(now.minusDays(20).toInstant()),
                expiredDashboardEvent.getId()
        );
        DashboardEventEntity freshDashboardEvent = dashboardEventRepository.saveAndFlush(
                DashboardEventEntity.of("local", "incident.updated", incidentId, "trace-fresh", Map.of("old", false))
        );

        // Conversations: 1 expired session (45 days old > 30d TTL) + 2 messages, 1 fresh session + 1 message
        ConversationSessionEntity expiredSession = conversationSessionRepository.saveAndFlush(
                ConversationSessionEntity.create("local", "Expired session")
        );
        ConversationMessageEntity expiredMsg1 = conversationMessageRepository.saveAndFlush(
                ConversationMessageEntity.create(expiredSession.getId(), ConversationMessageRole.USER, "old question", null, null, incidentId)
        );
        ConversationMessageEntity expiredMsg2 = conversationMessageRepository.saveAndFlush(
                ConversationMessageEntity.create(expiredSession.getId(), ConversationMessageRole.ASSISTANT, "old reply", null, null, incidentId)
        );
        jdbcTemplate.update(
                "update conversation_sessions set created_at = ?, updated_at = ? where id = ?",
                Timestamp.from(now.minusDays(45).toInstant()),
                Timestamp.from(now.minusDays(45).toInstant()),
                expiredSession.getId()
        );

        ConversationSessionEntity freshSession = conversationSessionRepository.saveAndFlush(
                ConversationSessionEntity.create("local", "Fresh session")
        );
        ConversationMessageEntity freshMsg = conversationMessageRepository.saveAndFlush(
                ConversationMessageEntity.create(freshSession.getId(), ConversationMessageRole.USER, "fresh question", null, null, incidentId)
        );

        // Evidence items:
        // 1. Expired uncited METRIC telemetry -> eligible for deletion
        UUID expiredTelemetryId = insertEvidence(incidentId, EvidenceType.METRIC, now.minusDays(45));
        // 2. Expired cited METRIC telemetry -> MUST be preserved because diagnosis cites it
        UUID citedTelemetryId = insertEvidence(incidentId, EvidenceType.METRIC, now.minusDays(45));
        diagnosisRepository.saveAndFlush(DiagnosisEntity.create(
                incidentId,
                "Diagnosis citing old telemetry",
                List.of(),
                BigDecimal.valueOf(0.85),
                List.of(citedTelemetryId),
                "deterministic-only",
                "deterministic-rules-v1.0.0"
        ));
        // 3. Expired ALERT_CONTEXT -> MUST be preserved (not a prunable raw telemetry type)
        UUID expiredAlertContextId = insertEvidence(incidentId, EvidenceType.ALERT_CONTEXT, now.minusDays(45));
        // 4. Fresh METRIC telemetry -> MUST be preserved
        UUID freshTelemetryId = insertEvidence(incidentId, EvidenceType.METRIC, now.minusDays(2));

        // Scheduled entrypoint is a no-op because healops.retention.enabled=false in test profile
        retentionCleanupService.runScheduledCleanup();
        assertThat(dashboardEventRepository.existsById(expiredDashboardEvent.getId())).isTrue();

        // Execute retention cleanup directly
        RetentionCleanupResult result = retentionCleanupService.runCleanupNow(now);

        assertThat(result.deletedDashboardEvents()).isGreaterThanOrEqualTo(1);
        assertThat(result.deletedTelemetryReferences()).isGreaterThanOrEqualTo(1);
        assertThat(result.deletedConversationMessages()).isGreaterThanOrEqualTo(2);
        assertThat(result.deletedConversationSessions()).isGreaterThanOrEqualTo(1);

        // Eligible expired items removed
        assertThat(dashboardEventRepository.existsById(expiredDashboardEvent.getId())).isFalse();
        assertThat(conversationSessionRepository.existsById(expiredSession.getId())).isFalse();
        assertThat(conversationMessageRepository.existsById(expiredMsg1.getId())).isFalse();
        assertThat(conversationMessageRepository.existsById(expiredMsg2.getId())).isFalse();
        assertThat(evidenceItemRepository.existsById(expiredTelemetryId)).isFalse();

        // Fresh, cited, and alert-context items preserved
        assertThat(dashboardEventRepository.existsById(freshDashboardEvent.getId())).isTrue();
        assertThat(conversationSessionRepository.existsById(freshSession.getId())).isTrue();
        assertThat(conversationMessageRepository.existsById(freshMsg.getId())).isTrue();
        assertThat(evidenceItemRepository.existsById(citedTelemetryId)).isTrue();
        assertThat(evidenceItemRepository.existsById(expiredAlertContextId)).isTrue();
        assertThat(evidenceItemRepository.existsById(freshTelemetryId)).isTrue();

        // Immutable records NEVER deleted even when 365 days old
        assertThat(auditRecordRepository.existsById(auditRecord.getId())).isTrue();
        assertThat(incidentRepository.existsById(incidentId)).isTrue();
        assertThat(incidentEventRepository.countByIncidentId(incidentId)).isGreaterThanOrEqualTo(1L);
    }

    private UUID insertEvidence(UUID incidentId, EvidenceType type, OffsetDateTime observedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into evidence_items (
                    id, incident_id, evidence_type, source, target, observed_at,
                    excerpt, content_hash, redaction_status, metadata, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}' format json, ?)
                """,
                id,
                incidentId,
                type.name(),
                "prometheus",
                "retention-service",
                Timestamp.from(observedAt.toInstant()),
                "cpu=42%",
                "hash-" + id.toString().substring(0, 8),
                RedactionStatus.NOT_REQUIRED.name(),
                Timestamp.from(observedAt.toInstant())
        );
        return id;
    }
}
