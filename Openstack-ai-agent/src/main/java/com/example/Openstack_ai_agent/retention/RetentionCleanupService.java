package com.example.Openstack_ai_agent.retention;

import com.example.Openstack_ai_agent.conversation.ConversationMessageRepository;
import com.example.Openstack_ai_agent.conversation.ConversationSessionEntity;
import com.example.Openstack_ai_agent.conversation.ConversationSessionRepository;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisEntity;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.observability.DashboardEventEntity;
import com.example.Openstack_ai_agent.observability.DashboardEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduled retention cleanup for bounded operational artifacts:
 * - expired dashboard SSE replay rows ({@code dashboard_events})
 * - expired conversation sessions and messages ({@code conversation_sessions}, {@code conversation_messages})
 * - stale, uncited, unreferenced telemetry evidence rows ({@code evidence_items} of telemetry types)
 *
 * Immutable compliance and lifecycle tables ({@code audit_records}, {@code incidents},
 * {@code incident_events}, {@code approval_decisions}, {@code incident_reports},
 * and {@code remediation_outcomes}) are intentionally never deleted by this job.
 */
@Service
public class RetentionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);
    private static final Set<EvidenceType> PRUNABLE_TELEMETRY_TYPES = EnumSet.of(
            EvidenceType.METRIC,
            EvidenceType.LOG,
            EvidenceType.TRACE,
            EvidenceType.COLLECTION_ERROR
    );

    private final RetentionProperties properties;
    private final DashboardEventRepository dashboardEventRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final ConversationSessionRepository conversationSessionRepository;
    private final ConversationMessageRepository conversationMessageRepository;
    private final TransactionTemplate transactionTemplate;
    private final Counter dashboardEventsDeletedCounter;
    private final Counter telemetryDeletedCounter;
    private final Counter conversationsDeletedCounter;

    public RetentionCleanupService(
            RetentionProperties properties,
            DashboardEventRepository dashboardEventRepository,
            EvidenceItemRepository evidenceItemRepository,
            DiagnosisRepository diagnosisRepository,
            ConversationSessionRepository conversationSessionRepository,
            ConversationMessageRepository conversationMessageRepository,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.dashboardEventRepository = dashboardEventRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.conversationSessionRepository = conversationSessionRepository;
        this.conversationMessageRepository = conversationMessageRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.dashboardEventsDeletedCounter = Counter.builder("healops.retention.deleted")
                .tag("category", "dashboard_events")
                .register(meterRegistry);
        this.telemetryDeletedCounter = Counter.builder("healops.retention.deleted")
                .tag("category", "telemetry_references")
                .register(meterRegistry);
        this.conversationsDeletedCounter = Counter.builder("healops.retention.deleted")
                .tag("category", "conversation_sessions")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${healops.retention.cron:0 0 * * * *}")
    public void runScheduledCleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            RetentionCleanupResult result = runCleanupNow(OffsetDateTime.now());
            if (result.totalDeleted() > 0) {
                log.info(
                        "Retention cleanup removed dashboardEvents={}, telemetryRefs={}, conversationMessages={}, conversationSessions={}",
                        result.deletedDashboardEvents(),
                        result.deletedTelemetryReferences(),
                        result.deletedConversationMessages(),
                        result.deletedConversationSessions()
                );
            }
        } catch (Exception ex) {
            log.warn("Scheduled retention cleanup failed: {}", ex.getMessage(), ex);
        }
    }

    public RetentionCleanupResult runCleanupNow() {
        return runCleanupNow(OffsetDateTime.now());
    }

    public RetentionCleanupResult runCleanupNow(OffsetDateTime referenceNow) {
        RetentionCleanupResult result = transactionTemplate.execute(status -> executeCleanup(referenceNow));
        if (result == null) {
            throw new IllegalStateException("Retention cleanup transaction returned null");
        }
        return result;
    }

    private RetentionCleanupResult executeCleanup(OffsetDateTime referenceNow) {
        OffsetDateTime now = referenceNow == null ? OffsetDateTime.now() : referenceNow;
        int batchSize = properties.getMaxBatchSize();

        int deletedDashboardEvents = purgeExpiredDashboardEvents(now.minus(properties.getDashboardEventTtl()), batchSize);
        ConversationPurgeCounts convCounts = purgeExpiredConversations(now.minus(properties.getConversationTtl()), batchSize);
        int deletedTelemetryRefs = purgeStaleTelemetryReferences(now.minus(properties.getTelemetryReferenceTtl()), batchSize);

        if (deletedDashboardEvents > 0) {
            dashboardEventsDeletedCounter.increment(deletedDashboardEvents);
        }
        if (deletedTelemetryRefs > 0) {
            telemetryDeletedCounter.increment(deletedTelemetryRefs);
        }
        if (convCounts.sessionsDeleted() > 0) {
            conversationsDeletedCounter.increment(convCounts.sessionsDeleted());
        }

        return new RetentionCleanupResult(
                deletedDashboardEvents,
                deletedTelemetryRefs,
                convCounts.messagesDeleted(),
                convCounts.sessionsDeleted(),
                now
        );
    }

    private int purgeExpiredDashboardEvents(OffsetDateTime cutoff, int batchSize) {
        List<DashboardEventEntity> expired = dashboardEventRepository.findByOccurredAtBeforeOrderByOccurredAtAsc(cutoff);
        if (expired.isEmpty()) {
            return 0;
        }
        List<DashboardEventEntity> batch = expired.size() <= batchSize ? expired : expired.subList(0, batchSize);
        dashboardEventRepository.deleteAllInBatch(batch);
        return batch.size();
    }

    private ConversationPurgeCounts purgeExpiredConversations(OffsetDateTime cutoff, int batchSize) {
        List<ConversationSessionEntity> expiredSessions = conversationSessionRepository
                .findByUpdatedAtBeforeOrderByUpdatedAtAsc(cutoff);
        if (expiredSessions.isEmpty()) {
            return new ConversationPurgeCounts(0, 0);
        }
        List<ConversationSessionEntity> batch = expiredSessions.size() <= batchSize
                ? expiredSessions
                : expiredSessions.subList(0, batchSize);
        List<UUID> sessionIds = batch.stream().map(ConversationSessionEntity::getId).toList();
        int deletedMessages = conversationMessageRepository.deleteBySessionIdIn(sessionIds);
        conversationSessionRepository.deleteAllInBatch(batch);
        return new ConversationPurgeCounts(deletedMessages, batch.size());
    }

    private int purgeStaleTelemetryReferences(OffsetDateTime cutoff, int batchSize) {
        List<EvidenceItemEntity> candidates = evidenceItemRepository
                .findByTypeInAndObservedAtBeforeOrderByObservedAtAsc(PRUNABLE_TELEMETRY_TYPES, cutoff);
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<UUID, Set<UUID>> citedByIncident = new HashMap<>();
        List<EvidenceItemEntity> eligible = new ArrayList<>();

        for (EvidenceItemEntity item : candidates) {
            if (eligible.size() >= batchSize) {
                break;
            }
            if (conversationMessageRepository.existsByEvidenceId(item.getId())) {
                continue;
            }
            Set<UUID> citedIds = citedByIncident.computeIfAbsent(item.getIncidentId(), incidentId -> {
                Set<UUID> ids = new HashSet<>();
                for (DiagnosisEntity diagnosis : diagnosisRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId)) {
                    if (diagnosis.getCitedEvidenceIds() != null) {
                        ids.addAll(diagnosis.getCitedEvidenceIds());
                    }
                }
                return ids;
            });
            if (citedIds.contains(item.getId())) {
                continue;
            }
            eligible.add(item);
        }

        if (eligible.isEmpty()) {
            return 0;
        }
        evidenceItemRepository.deleteAllInBatch(eligible);
        return eligible.size();
    }

    private record ConversationPurgeCounts(int messagesDeleted, int sessionsDeleted) {
    }
}
