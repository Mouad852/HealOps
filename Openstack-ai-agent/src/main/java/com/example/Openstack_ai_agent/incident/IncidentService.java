package com.example.Openstack_ai_agent.incident;

import com.example.Openstack_ai_agent.common.api.CursorPageResponse;
import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import com.example.Openstack_ai_agent.observability.DashboardEventPublisher;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class IncidentService {
    private final IncidentRepository incidentRepository;
    private final IncidentEventRepository incidentEventRepository;
    private final DashboardEventPublisher dashboardEventPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNewTx;
    private final boolean autoOrchestrate;

    public IncidentService(
            IncidentRepository incidentRepository,
            IncidentEventRepository incidentEventRepository,
            DashboardEventPublisher dashboardEventPublisher,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            @Value("${healops.workflow.auto-orchestrate:true}") boolean autoOrchestrate
    ) {
        this.incidentRepository = incidentRepository;
        this.incidentEventRepository = incidentEventRepository;
        this.dashboardEventPublisher = dashboardEventPublisher;
        this.eventPublisher = eventPublisher;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.autoOrchestrate = autoOrchestrate;
    }

    public IncidentCreationResult create(CreateIncidentRequest request) {
        Optional<IncidentEntity> existing = incidentRepository.findBySourceAndEnvironmentIdAndFingerprint(
                request.source(),
                request.environmentId(),
                request.fingerprint()
        );
        if (existing.isPresent()) {
            return new IncidentCreationResult(toResponse(existing.get()), false);
        }
        try {
            IncidentCreationResult created = requiresNewTx.execute(status -> createNewIncident(request));
            if (created != null) {
                return created;
            }
        } catch (DataIntegrityViolationException race) {
            return incidentRepository
                    .findBySourceAndEnvironmentIdAndFingerprint(
                            request.source(),
                            request.environmentId(),
                            request.fingerprint()
                    )
                    .map(incident -> new IncidentCreationResult(toResponse(incident), false))
                    .orElseThrow(() -> race);
        }
        throw new IllegalStateException("Incident creation transaction returned null");
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> findAll(String environmentId, IncidentStatus status) {
        return findAll(environmentId, status, null);
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> findAll(String environmentId, IncidentStatus status, IncidentSeverity severity) {
        Specification<IncidentEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (environmentId != null && !environmentId.isBlank()) {
                predicates.add(cb.equal(root.get("environmentId"), environmentId.trim()));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
        return incidentRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<UUID, IncidentResponse> findMapByEnvironmentIn(Collection<String> environmentIds) {
        if (environmentIds == null || environmentIds.isEmpty()) {
            return Map.of();
        }
        return incidentRepository.findByEnvironmentIdIn(environmentIds).stream()
                .collect(Collectors.toMap(
                        IncidentEntity::getId,
                        this::toResponse,
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<IncidentResponse> findPage(
            String environmentId,
            IncidentStatus status,
            IncidentSeverity severity,
            String cursor,
            Integer limit,
            Authentication authentication
    ) {
        int effectiveLimit = IncidentCursorCodec.resolveLimit(limit);
        IncidentCursorCodec.CursorToken cursorToken = IncidentCursorCodec.decode(cursor);
        Set<String> scopedEnvs = EnvironmentAuthorizationSupport.resolveAuthorizedScope(environmentId, authentication);
        if (scopedEnvs.isEmpty()) {
            return new CursorPageResponse<>(List.of(), null);
        }

        OffsetDateTime cursorCreatedAt = cursorToken == null
                ? null
                : OffsetDateTime.ofInstant(cursorToken.createdAt(), ZoneOffset.UTC);
        UUID cursorId = cursorToken == null ? null : cursorToken.id();

        List<IncidentEntity> matching = incidentRepository.findKeysetPage(
                scopedEnvs,
                status,
                severity,
                cursorCreatedAt,
                cursorId,
                effectiveLimit + 1
        );

        boolean hasMore = matching.size() > effectiveLimit;
        List<IncidentEntity> pageEntities = hasMore ? matching.subList(0, effectiveLimit) : matching;
        String nextCursor = hasMore && !pageEntities.isEmpty()
                ? IncidentCursorCodec.encode(pageEntities.getLast().getCreatedAt(), pageEntities.getLast().getId())
                : null;

        List<IncidentResponse> items = pageEntities.stream()
                .map(this::toResponse)
                .toList();
        return new CursorPageResponse<>(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public IncidentResponse findById(UUID incidentId) {
        return toResponse(findEntity(incidentId));
    }

    @Transactional
    public IncidentResponse transition(UUID incidentId, TransitionIncidentRequest request) {
        IncidentEntity incident = findEntity(incidentId);
        if (incident.getVersion() != request.expectedVersion()) {
            throw new IncidentVersionConflictException(incidentId, request.expectedVersion(), incident.getVersion());
        }

        IncidentStatus fromStatus = incident.getStatus();
        IncidentStatus toStatus = IncidentTransitionMap.targetFor(
                fromStatus,
                request.event(),
                request.failureDisposition()
        );
        incident.transitionTo(toStatus);
        IncidentEntity updatedIncident = incidentRepository.saveAndFlush(incident);
        long nextSequence = incidentEventRepository.countByIncidentId(incidentId) + 1;
        incidentEventRepository.save(IncidentEventEntity.transition(
                updatedIncident,
                nextSequence,
                fromStatus,
                toStatus,
                request
        ));
        recordDashboardUpdate(updatedIncident, request.event().name(), request.actor());
        publishNextWorkflowEventIfEnabled(updatedIncident, toStatus, request.event(), request.actor(), request.reason(), request.metadata());
        if (toStatus.isTerminal()) {
            eventPublisher.publishEvent(new IncidentTerminalStateReachedEvent(
                    updatedIncident.getId(),
                    updatedIncident.getEnvironmentId(),
                    toStatus,
                    updatedIncident.getTraceId()
            ));
        }
        return toResponse(updatedIncident);
    }

    @Transactional
    public IncidentResponse retryFailedPhase(UUID incidentId, String actor, String reason, long expectedVersion) {
        IncidentResponse updated = transition(incidentId, new TransitionIncidentRequest(
                IncidentWorkflowEvent.PHASE_RETRIED,
                null,
                actor,
                reason == null || reason.isBlank() ? "Operator requested phase retry" : reason,
                null,
                Map.of("retry", "true"),
                expectedVersion
        ));
        if (autoOrchestrate) {
            eventPublisher.publishEvent(new IncidentAlertedEvent(
                    updated.id(),
                    updated.environmentId(),
                    updated.service(),
                    updated.traceId()
            ));
        }
        return updated;
    }

    @Transactional(readOnly = true)
    public List<IncidentEventResponse> findTimeline(UUID incidentId) {
        findEntity(incidentId);
        return incidentEventRepository.findByIncidentIdOrderByEventSequenceAsc(incidentId).stream()
                .map(IncidentEventResponse::from)
                .toList();
    }

    private IncidentCreationResult createNewIncident(CreateIncidentRequest request) {
        IncidentEntity incident = incidentRepository.saveAndFlush(IncidentEntity.alertReceived(request));
        incidentEventRepository.save(IncidentEventEntity.alertReceived(incident));
        recordDashboardUpdate(incident, IncidentWorkflowEvent.ALERT_RECEIVED.name(), incident.getSource());
        if (autoOrchestrate) {
            eventPublisher.publishEvent(new IncidentAlertedEvent(
                    incident.getId(),
                    incident.getEnvironmentId(),
                    incident.getService(),
                    incident.getTraceId()
            ));
        }
        return new IncidentCreationResult(toResponse(incident), true);
    }

    private void publishNextWorkflowEventIfEnabled(
            IncidentEntity incident,
            IncidentStatus toStatus,
            IncidentWorkflowEvent workflowEvent,
            String actor,
            String reason,
            Map<String, String> metadata
    ) {
        if (!autoOrchestrate) {
            return;
        }
        Map<String, String> safeMetadata = metadata == null ? Map.of() : metadata;
        if (workflowEvent == IncidentWorkflowEvent.EVIDENCE_GATHERED) {
            int evidenceCount = parseIntOrZero(safeMetadata.get("evidenceCount"));
            int errorCount = parseIntOrZero(safeMetadata.get("collectionErrorCount"));
            eventPublisher.publishEvent(new IncidentEvidenceGatheredEvent(
                    incident.getId(),
                    incident.getEnvironmentId(),
                    incident.getService(),
                    evidenceCount,
                    errorCount,
                    incident.getTraceId()
            ));
        } else if (workflowEvent == IncidentWorkflowEvent.SIGNALS_CORRELATED) {
            int evidenceCount = parseIntOrZero(safeMetadata.get("evidenceCount"));
            int matchedTraceCount = parseIntOrZero(safeMetadata.get("matchedTraceCount"));
            eventPublisher.publishEvent(new IncidentSignalsCorrelatedEvent(
                    incident.getId(),
                    incident.getEnvironmentId(),
                    incident.getService(),
                    evidenceCount,
                    matchedTraceCount,
                    incident.getTraceId()
            ));
        } else if (workflowEvent == IncidentWorkflowEvent.ACTION_APPROVED) {
            eventPublisher.publishEvent(new IncidentActionApprovedEvent(
                    incident.getId(),
                    parseUuidOrNull(safeMetadata.get("actionId")),
                    incident.getEnvironmentId(),
                    incident.getService(),
                    actor,
                    incident.getTraceId()
            ));
        } else if (workflowEvent == IncidentWorkflowEvent.EXECUTION_SUCCEEDED
                || workflowEvent == IncidentWorkflowEvent.NO_ACTION_REQUIRED) {
            eventPublisher.publishEvent(new IncidentVerificationRequestedEvent(
                    incident.getId(),
                    parseUuidOrNull(safeMetadata.get("actionId")),
                    incident.getEnvironmentId(),
                    incident.getService(),
                    workflowEvent.name(),
                    incident.getTraceId()
            ));
        } else if (toStatus == IncidentStatus.ROLLING_BACK) {
            eventPublisher.publishEvent(new IncidentRollbackRequestedEvent(
                    incident.getId(),
                    parseUuidOrNull(safeMetadata.get("actionId")),
                    incident.getEnvironmentId(),
                    incident.getService(),
                    reason,
                    incident.getTraceId()
            ));
        }
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void recordDashboardUpdate(IncidentEntity incident, String eventType, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", incident.getId().toString());
        payload.put("status", incident.getStatus().name());
        payload.put("severity", incident.getSeverity().name());
        payload.put("service", incident.getService());
        payload.put("version", incident.getVersion());
        payload.put("workflowEvent", eventType);
        payload.put("actor", actor);
        dashboardEventPublisher.publish(
                incident.getEnvironmentId(),
                "incident.updated",
                incident.getId(),
                incident.getTraceId(),
                payload
        );
    }

    private int parseIntOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private IncidentEntity findEntity(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
    }

    private IncidentResponse toResponse(IncidentEntity incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getSource(),
                incident.getExternalAlertId(),
                incident.getFingerprint(),
                incident.getEnvironmentId(),
                incident.getSeverity(),
                incident.getService(),
                incident.getStatus(),
                incident.getStartedAt(),
                incident.getCreatedAt(),
                incident.getVersion(),
                incident.getLabels(),
                incident.getAnnotations(),
                incident.getTraceId()
        );
    }

    public record IncidentCreationResult(IncidentResponse incident, boolean created) {
    }
}
