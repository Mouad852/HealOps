package com.example.Openstack_ai_agent.incident;

import com.example.Openstack_ai_agent.observability.DashboardEventEntity;
import com.example.Openstack_ai_agent.observability.DashboardEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IncidentService {
    private final IncidentRepository incidentRepository;
    private final IncidentEventRepository incidentEventRepository;
    private final DashboardEventRepository dashboardEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final boolean autoOrchestrate;

    public IncidentService(
            IncidentRepository incidentRepository,
            IncidentEventRepository incidentEventRepository,
            DashboardEventRepository dashboardEventRepository,
            ApplicationEventPublisher eventPublisher,
            @Value("${healops.workflow.auto-orchestrate:true}") boolean autoOrchestrate
    ) {
        this.incidentRepository = incidentRepository;
        this.incidentEventRepository = incidentEventRepository;
        this.dashboardEventRepository = dashboardEventRepository;
        this.eventPublisher = eventPublisher;
        this.autoOrchestrate = autoOrchestrate;
    }

    @Transactional
    public IncidentCreationResult create(CreateIncidentRequest request) {
        return incidentRepository
                .findBySourceAndEnvironmentIdAndFingerprint(request.source(), request.environmentId(), request.fingerprint())
                .map(incident -> new IncidentCreationResult(toResponse(incident), false))
                .orElseGet(() -> createNewIncident(request));
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> findAll(String environmentId, IncidentStatus status) {
        return incidentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter(incident -> environmentId == null || environmentId.equals(incident.getEnvironmentId()))
                .filter(incident -> status == null || status == incident.getStatus())
                .map(this::toResponse)
                .toList();
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
        dashboardEventRepository.save(DashboardEventEntity.of(
                incident.getEnvironmentId(),
                "incident.updated",
                incident.getId(),
                incident.getTraceId(),
                payload
        ));
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
