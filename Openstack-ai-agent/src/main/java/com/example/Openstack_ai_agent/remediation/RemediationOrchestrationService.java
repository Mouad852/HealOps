package com.example.Openstack_ai_agent.remediation;

import com.example.Openstack_ai_agent.audit.AuditOutcome;
import com.example.Openstack_ai_agent.audit.AuditService;
import com.example.Openstack_ai_agent.diagnosis.DeterministicDiagnosticsEngine;
import com.example.Openstack_ai_agent.diagnosis.HealthComparisonResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotService;
import com.example.Openstack_ai_agent.diagnosis.HealthVerificationComparator;
import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.incident.FailureDisposition;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentWorkflowEvent;
import com.example.Openstack_ai_agent.incident.TransitionIncidentRequest;
import com.example.Openstack_ai_agent.observability.DashboardEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional persistence coordinator used by {@link RemediationOrchestrationListener}.
 * Every method executes inside a short database transaction so external adapter calls and
 * stabilization windows never hold open database connections.
 */
@Service
public class RemediationOrchestrationService {
    private static final String WORKFLOW_ACTOR = "workflow-engine";

    private final ProposedActionRepository proposedActionRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final RemediationOutcomeRepository remediationOutcomeRepository;
    private final DashboardEventPublisher dashboardEventPublisher;
    private final IncidentService incidentService;
    private final HealthSnapshotService healthSnapshotService;
    private final HealthVerificationComparator healthVerificationComparator;
    private final AuditService auditService;
    private final List<ActionExecutor> executors;

    public RemediationOrchestrationService(
            ProposedActionRepository proposedActionRepository,
            ToolExecutionRepository toolExecutionRepository,
            RemediationOutcomeRepository remediationOutcomeRepository,
            DashboardEventPublisher dashboardEventPublisher,
            IncidentService incidentService,
            HealthSnapshotService healthSnapshotService,
            HealthVerificationComparator healthVerificationComparator,
            AuditService auditService,
            List<ActionExecutor> executors
    ) {
        this.proposedActionRepository = proposedActionRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.remediationOutcomeRepository = remediationOutcomeRepository;
        this.dashboardEventPublisher = dashboardEventPublisher;
        this.incidentService = incidentService;
        this.healthSnapshotService = healthSnapshotService;
        this.healthVerificationComparator = healthVerificationComparator;
        this.auditService = auditService;
        this.executors = List.copyOf(executors);
    }

    @Transactional(readOnly = true)
    public Optional<ProposedActionEntity> resolveActionForExecution(UUID incidentId, UUID actionId) {
        if (actionId != null) {
            return proposedActionRepository.findById(actionId);
        }
        return proposedActionRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .filter(action -> action.getStatus() == ProposedActionStatus.APPROVED)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<ProposedActionEntity> resolveLatestActionForIncident(UUID incidentId, UUID actionId) {
        if (actionId != null) {
            return proposedActionRepository.findById(actionId);
        }
        return proposedActionRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .findFirst();
    }

    public Optional<ActionExecutor> findExecutor(ActionType actionType) {
        if (actionType == null) {
            return Optional.empty();
        }
        return executors.stream()
                .filter(executor -> executor.supports(actionType))
                .findFirst();
    }

    @Transactional
    public Optional<ExecutionStartRecord> beginForwardExecution(
            IncidentResponse incident,
            ProposedActionEntity action
    ) {
        String primaryKey = "exec:" + action.getId() + ":attempt:1";
        if (toolExecutionRepository.findByIdempotencyKey(primaryKey).isPresent()) {
            return Optional.empty();
        }

        Map<String, Object> sanitizedInput = sanitizeMap(Map.of(
                "actionType", action.getActionType().name(),
                "arguments", action.getArguments() == null ? Map.of() : action.getArguments(),
                "environmentId", incident.environmentId(),
                "service", incident.service()
        ));

        ToolExecutionEntity execution = toolExecutionRepository.saveAndFlush(
                ToolExecutionEntity.running(
                        action.getId(),
                        incident.id(),
                        primaryKey,
                        1,
                        sanitizedInput,
                        incident.traceId()
                )
        );
        emitToolExecutionEvent(incident, execution, action.getActionType(), "EXECUTE");
        return Optional.of(new ExecutionStartRecord(execution.getId(), primaryKey, 1));
    }

    @Transactional
    public void completeForwardExecutionAndTransition(
            UUID incidentId,
            UUID actionId,
            UUID executionId,
            ActionExecutor executor,
            ActionExecutor.ActionExecutionResult result,
            String policyVersion
    ) {
        IncidentResponse incident = incidentService.findById(incidentId);
        ProposedActionEntity action = proposedActionRepository.findById(actionId)
                .orElseThrow(() -> new ProposedActionNotFoundException(actionId));
        ToolExecutionEntity execution = toolExecutionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException("ToolExecution not found: " + executionId));

        boolean canRollback = executor.isReversible()
                && result.rollbackCapable()
                && !result.capturedPreState().isEmpty();

        Map<String, Object> outputPayload = new LinkedHashMap<>(sanitizeMap(result.sanitizedOutput()));
        outputPayload.put("summary", result.summary());
        outputPayload.put("rollbackCapable", canRollback);
        if (!result.capturedPreState().isEmpty()) {
            outputPayload.put("preState", sanitizeMap(result.capturedPreState()));
        }

        OffsetDateTime endedAt = OffsetDateTime.now();
        execution.complete(result.status(), outputPayload, endedAt);
        toolExecutionRepository.saveAndFlush(execution);

        if (result.status() == ToolExecutionStatus.SUCCEEDED) {
            action.markExecuted();
        } else {
            action.markFailed();
        }
        proposedActionRepository.saveAndFlush(action);

        emitToolExecutionEvent(incident, execution, action.getActionType(), "EXECUTE");

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("executionId", execution.getId().toString());
        auditDetails.put("actionId", action.getId().toString());
        auditDetails.put("incidentId", incident.id().toString());
        auditDetails.put("actionType", action.getActionType().name());
        auditDetails.put("idempotencyKey", execution.getIdempotencyKey());
        auditDetails.put("status", result.status().name());
        auditDetails.put("rollbackCapable", canRollback);
        auditDetails.put("summary", result.summary());
        auditDetails.put("policyVersion", policyVersion);
        auditService.record(
                WORKFLOW_ACTOR,
                "ACTION_EXECUTION",
                "proposed_action:" + action.getId(),
                result.status() == ToolExecutionStatus.SUCCEEDED ? AuditOutcome.SUCCESS : AuditOutcome.FAILED,
                auditDetails,
                incident.traceId()
        );

        if (incident.status() != IncidentStatus.EXECUTING) {
            return;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("actionId", action.getId().toString());
        metadata.put("executionId", execution.getId().toString());
        metadata.put("actionType", action.getActionType().name());
        metadata.put("rollbackCapable", Boolean.toString(canRollback));

        if (result.status() == ToolExecutionStatus.SUCCEEDED) {
            incidentService.transition(incident.id(), new TransitionIncidentRequest(
                    IncidentWorkflowEvent.EXECUTION_SUCCEEDED,
                    null,
                    WORKFLOW_ACTOR,
                    result.summary(),
                    incident.traceId(),
                    Map.copyOf(metadata),
                    incident.version()
            ));
        } else {
            FailureDisposition disposition = canRollback ? FailureDisposition.ROLLING_BACK : FailureDisposition.FAILED;
            if (!canRollback) {
                long durationMs = Math.max(0L, Duration.between(execution.getStartedAt(), endedAt).toMillis());
                remediationOutcomeRepository.save(RemediationOutcomeEntity.create(
                        action.getId(),
                        incident.id(),
                        action.getActionType(),
                        false,
                        durationMs,
                        false,
                        null
                ));
            }
            incidentService.transition(incident.id(), new TransitionIncidentRequest(
                    IncidentWorkflowEvent.EXECUTION_FAILED,
                    disposition,
                    WORKFLOW_ACTOR,
                    result.summary(),
                    incident.traceId(),
                    Map.copyOf(metadata),
                    incident.version()
            ));
        }
    }

    @Transactional
    public void failMissingExecutorOrAction(UUID incidentId, UUID actionId, String reason) {
        IncidentResponse incident = incidentService.findById(incidentId);
        if (actionId != null) {
            proposedActionRepository.findById(actionId).ifPresent(action -> {
                if (action.getStatus() == ProposedActionStatus.APPROVED) {
                    action.markFailed();
                    proposedActionRepository.save(action);
                }
            });
        }
        auditService.record(
                WORKFLOW_ACTOR,
                "ACTION_EXECUTION",
                actionId != null ? "proposed_action:" + actionId : "incident:" + incidentId,
                AuditOutcome.FAILED,
                Map.of("incidentId", incidentId.toString(), "reason", reason),
                incident.traceId()
        );
        if (incident.status() == IncidentStatus.EXECUTING) {
            incidentService.transition(incidentId, new TransitionIncidentRequest(
                    IncidentWorkflowEvent.EXECUTION_FAILED,
                    FailureDisposition.FAILED,
                    WORKFLOW_ACTOR,
                    reason,
                    incident.traceId(),
                    Map.of("reason", reason),
                    incident.version()
            ));
        }
    }

    @Transactional
    public void recordVerificationAndTransition(
            UUID incidentId,
            UUID actionId,
            List<EvidenceItemResponse> verificationEvidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics
    ) {
        IncidentResponse incident = incidentService.findById(incidentId);
        if (incident.status() != IncidentStatus.VERIFYING) {
            return;
        }

        HealthSnapshotResponse afterSnapshot = healthSnapshotService.captureAfterSnapshot(
                incident,
                verificationEvidence,
                correlation,
                diagnostics
        );
        HealthComparisonResponse comparisonResponse = healthSnapshotService.getHealthComparisons(incidentId);
        HealthVerificationComparator.HealthVerificationResult comparison =
                healthVerificationComparator.compare(comparisonResponse.before(), afterSnapshot);

        ProposedActionEntity action = resolveLatestActionForIncident(incidentId, actionId).orElse(null);
        ToolExecutionEntity forwardExecution = action != null
                ? findForwardExecution(action.getId()).orElse(null)
                : null;

        ActionType outcomeActionType = action != null ? action.getActionType() : ActionType.NO_ACTION;
        OffsetDateTime startRef = forwardExecution != null ? forwardExecution.getStartedAt() : incident.createdAt();
        long durationMs = startRef != null
                ? Math.max(0L, Duration.between(startRef, OffsetDateTime.now()).toMillis())
                : 0L;

        boolean rollbackCapable = isRollbackCapable(action, forwardExecution);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("incidentId", incidentId.toString());
        if (action != null) {
            auditDetails.put("actionId", action.getId().toString());
            auditDetails.put("actionType", action.getActionType().name());
        }
        if (comparisonResponse.before() != null) {
            auditDetails.put("beforeSnapshotId", comparisonResponse.before().id().toString());
        }
        auditDetails.put("afterSnapshotId", afterSnapshot.id().toString());
        auditDetails.put("passed", comparison.passed());
        auditDetails.put("summary", comparison.summary());
        auditDetails.put("failureReasons", comparison.failureReasons());
        auditDetails.put("rollbackTriggered", !comparison.passed() && rollbackCapable);

        if (comparison.passed()) {
            remediationOutcomeRepository.save(RemediationOutcomeEntity.create(
                    action != null ? action.getId() : null,
                    incidentId,
                    outcomeActionType,
                    true,
                    durationMs,
                    false,
                    null
            ));
            auditService.record(
                    WORKFLOW_ACTOR,
                    "HEALTH_VERIFICATION",
                    "incident:" + incidentId,
                    AuditOutcome.SUCCESS,
                    auditDetails,
                    incident.traceId()
            );

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("afterSnapshotId", afterSnapshot.id().toString());
            if (action != null) {
                metadata.put("actionId", action.getId().toString());
            }
            incidentService.transition(incidentId, new TransitionIncidentRequest(
                    IncidentWorkflowEvent.VERIFICATION_PASSED,
                    null,
                    WORKFLOW_ACTOR,
                    comparison.summary(),
                    incident.traceId(),
                    Map.copyOf(metadata),
                    incident.version()
            ));
        } else {
            FailureDisposition disposition = rollbackCapable
                    ? FailureDisposition.ROLLING_BACK
                    : FailureDisposition.FAILED;
            remediationOutcomeRepository.save(RemediationOutcomeEntity.create(
                    action != null ? action.getId() : null,
                    incidentId,
                    outcomeActionType,
                    false,
                    durationMs,
                    rollbackCapable,
                    null
            ));
            auditService.record(
                    WORKFLOW_ACTOR,
                    "HEALTH_VERIFICATION",
                    "incident:" + incidentId,
                    AuditOutcome.FAILED,
                    auditDetails,
                    incident.traceId()
            );

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("afterSnapshotId", afterSnapshot.id().toString());
            metadata.put("rollbackCapable", Boolean.toString(rollbackCapable));
            if (action != null) {
                metadata.put("actionId", action.getId().toString());
            }
            incidentService.transition(incidentId, new TransitionIncidentRequest(
                    IncidentWorkflowEvent.VERIFICATION_FAILED,
                    disposition,
                    WORKFLOW_ACTOR,
                    comparison.summary(),
                    incident.traceId(),
                    Map.copyOf(metadata),
                    incident.version()
            ));
        }
    }

    @Transactional(readOnly = true)
    public Optional<RollbackPreparation> prepareRollback(UUID incidentId, UUID actionId) {
        Optional<ProposedActionEntity> actionOpt = resolveLatestActionForIncident(incidentId, actionId);
        if (actionOpt.isEmpty()) {
            return Optional.empty();
        }
        ProposedActionEntity action = actionOpt.get();
        Optional<ActionExecutor> executorOpt = findExecutor(action.getActionType());
        if (executorOpt.isEmpty() || !executorOpt.get().isReversible()) {
            return Optional.empty();
        }
        Optional<ToolExecutionEntity> forwardOpt = findForwardExecution(action.getId());
        if (forwardOpt.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> preState = extractPreState(forwardOpt.get());
        if (preState.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RollbackPreparation(action, executorOpt.get(), preState));
    }

    @Transactional
    public Optional<ExecutionStartRecord> beginRollbackExecution(
            IncidentResponse incident,
            ProposedActionEntity action,
            Map<String, Object> preState
    ) {
        String rollbackKey = "rollback:" + action.getId() + ":attempt:1";
        if (toolExecutionRepository.findByIdempotencyKey(rollbackKey).isPresent()) {
            return Optional.empty();
        }
        int attempt = toolExecutionRepository.countByActionId(action.getId()) + 1;
        Map<String, Object> sanitizedInput = sanitizeMap(Map.of(
                "phase", "ROLLBACK",
                "actionType", action.getActionType().name(),
                "preState", preState,
                "environmentId", incident.environmentId(),
                "service", incident.service()
        ));
        ToolExecutionEntity execution = toolExecutionRepository.saveAndFlush(
                ToolExecutionEntity.running(
                        action.getId(),
                        incident.id(),
                        rollbackKey,
                        attempt,
                        sanitizedInput,
                        incident.traceId()
                )
        );
        emitToolExecutionEvent(incident, execution, action.getActionType(), "ROLLBACK");
        return Optional.of(new ExecutionStartRecord(execution.getId(), rollbackKey, attempt));
    }

    @Transactional
    public void completeRollbackAndTransition(
            UUID incidentId,
            UUID actionId,
            UUID rollbackExecutionId,
            ActionExecutor.ActionExecutionResult rollbackResult,
            List<EvidenceItemResponse> postRollbackEvidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics
    ) {
        IncidentResponse incident = incidentService.findById(incidentId);
        ProposedActionEntity action = proposedActionRepository.findById(actionId)
                .orElseThrow(() -> new ProposedActionNotFoundException(actionId));
        ToolExecutionEntity rollbackExecution = toolExecutionRepository.findById(rollbackExecutionId)
                .orElseThrow(() -> new IllegalStateException("Rollback ToolExecution not found: " + rollbackExecutionId));

        OffsetDateTime endedAt = OffsetDateTime.now();
        Map<String, Object> outputPayload = new LinkedHashMap<>(sanitizeMap(rollbackResult.sanitizedOutput()));
        outputPayload.put("summary", rollbackResult.summary());
        outputPayload.put("phase", "ROLLBACK");
        rollbackExecution.complete(rollbackResult.status(), outputPayload, endedAt);
        toolExecutionRepository.saveAndFlush(rollbackExecution);

        emitToolExecutionEvent(incident, rollbackExecution, action.getActionType(), "ROLLBACK");

        HealthSnapshotResponse postRollbackSnapshot = healthSnapshotService.capturePostRollbackSnapshot(
                incident,
                postRollbackEvidence,
                correlation,
                diagnostics
        );

        boolean rollbackSucceeded = rollbackResult.status() == ToolExecutionStatus.SUCCEEDED;
        long totalDurationMs = incident.createdAt() != null
                ? Math.max(0L, Duration.between(incident.createdAt(), endedAt).toMillis())
                : 0L;

        Optional<RemediationOutcomeEntity> existingOutcome =
                remediationOutcomeRepository.findFirstByIncidentIdOrderByCreatedAtDesc(incidentId);
        if (existingOutcome.isPresent()) {
            RemediationOutcomeEntity outcome = existingOutcome.get();
            outcome.recordRollbackOutcome(rollbackSucceeded, totalDurationMs);
            remediationOutcomeRepository.save(outcome);
        } else {
            remediationOutcomeRepository.save(RemediationOutcomeEntity.create(
                    action.getId(),
                    incidentId,
                    action.getActionType(),
                    false,
                    totalDurationMs,
                    true,
                    rollbackSucceeded
            ));
        }

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("incidentId", incidentId.toString());
        auditDetails.put("actionId", action.getId().toString());
        auditDetails.put("rollbackExecutionId", rollbackExecution.getId().toString());
        auditDetails.put("postRollbackSnapshotId", postRollbackSnapshot.id().toString());
        auditDetails.put("status", rollbackResult.status().name());
        auditDetails.put("summary", rollbackResult.summary());
        auditService.record(
                WORKFLOW_ACTOR,
                "ACTION_ROLLBACK",
                "proposed_action:" + action.getId(),
                rollbackSucceeded ? AuditOutcome.SUCCESS : AuditOutcome.FAILED,
                auditDetails,
                incident.traceId()
        );

        if (incident.status() != IncidentStatus.ROLLING_BACK) {
            return;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("actionId", action.getId().toString());
        metadata.put("rollbackExecutionId", rollbackExecution.getId().toString());
        metadata.put("postRollbackSnapshotId", postRollbackSnapshot.id().toString());

        incidentService.transition(incidentId, new TransitionIncidentRequest(
                rollbackSucceeded ? IncidentWorkflowEvent.ROLLBACK_SUCCEEDED : IncidentWorkflowEvent.ROLLBACK_FAILED,
                null,
                WORKFLOW_ACTOR,
                rollbackResult.summary(),
                incident.traceId(),
                Map.copyOf(metadata),
                incident.version()
        ));
    }

    @Transactional
    public void failImpossibleRollback(UUID incidentId, UUID actionId, String reason) {
        IncidentResponse incident = incidentService.findById(incidentId);
        remediationOutcomeRepository.findFirstByIncidentIdOrderByCreatedAtDesc(incidentId).ifPresent(outcome -> {
            long totalDurationMs = incident.createdAt() != null
                    ? Math.max(0L, Duration.between(incident.createdAt(), OffsetDateTime.now()).toMillis())
                    : 0L;
            outcome.recordRollbackOutcome(false, totalDurationMs);
            remediationOutcomeRepository.save(outcome);
        });
        auditService.record(
                WORKFLOW_ACTOR,
                "ACTION_ROLLBACK",
                actionId != null ? "proposed_action:" + actionId : "incident:" + incidentId,
                AuditOutcome.FAILED,
                Map.of("incidentId", incidentId.toString(), "reason", reason),
                incident.traceId()
        );
        if (incident.status() == IncidentStatus.ROLLING_BACK) {
            incidentService.transition(incidentId, new TransitionIncidentRequest(
                    IncidentWorkflowEvent.ROLLBACK_FAILED,
                    null,
                    WORKFLOW_ACTOR,
                    reason,
                    incident.traceId(),
                    Map.of("reason", reason),
                    incident.version()
            ));
        }
    }

    private Optional<ToolExecutionEntity> findForwardExecution(UUID actionId) {
        Optional<ToolExecutionEntity> primary = toolExecutionRepository.findByIdempotencyKey("exec:" + actionId + ":attempt:1");
        if (primary.isPresent()) {
            return primary;
        }
        return toolExecutionRepository.findByActionIdOrderByStartedAtAsc(actionId).stream()
                .filter(exec -> exec.getIdempotencyKey().startsWith("exec:"))
                .findFirst();
    }

    private boolean isRollbackCapable(ProposedActionEntity action, ToolExecutionEntity forwardExecution) {
        if (action == null || forwardExecution == null) {
            return false;
        }
        Optional<ActionExecutor> executor = findExecutor(action.getActionType());
        if (executor.isEmpty() || !executor.get().isReversible()) {
            return false;
        }
        Map<String, Object> output = forwardExecution.getSanitizedOutput();
        if (output == null) {
            return false;
        }
        Object capable = output.get("rollbackCapable");
        boolean flagged = capable instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(capable));
        return flagged && !extractPreState(forwardExecution).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPreState(ToolExecutionEntity forwardExecution) {
        if (forwardExecution == null || forwardExecution.getSanitizedOutput() == null) {
            return Map.of();
        }
        Object rawPreState = forwardExecution.getSanitizedOutput().get("preState");
        if (rawPreState instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(String.valueOf(k), v);
                }
            });
            return Map.copyOf(copy);
        }
        return Map.of();
    }

    private void emitToolExecutionEvent(
            IncidentResponse incident,
            ToolExecutionEntity execution,
            ActionType actionType,
            String phase
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", execution.getId().toString());
        payload.put("actionId", execution.getActionId() != null ? execution.getActionId().toString() : null);
        payload.put("incidentId", incident.id().toString());
        payload.put("actionType", actionType.name());
        payload.put("phase", phase);
        payload.put("attempt", execution.getAttempt());
        payload.put("idempotencyKey", execution.getIdempotencyKey());
        payload.put("status", execution.getStatus().name());
        dashboardEventPublisher.publish(
                incident.environmentId(),
                "tool-execution.updated",
                incident.id(),
                incident.traceId(),
                payload
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (lowerKey.contains("password") || lowerKey.contains("secret") || lowerKey.contains("token") || lowerKey.contains("api_key")) {
                sanitized.put(key, "[REDACTED]");
            } else if (value instanceof Map<?, ?> nested) {
                Map<String, Object> nestedCopy = new LinkedHashMap<>();
                nested.forEach((nk, nv) -> {
                    if (nk != null && nv != null) {
                        nestedCopy.put(String.valueOf(nk), nv);
                    }
                });
                sanitized.put(key, sanitizeMap(nestedCopy));
            } else if (value instanceof String str) {
                sanitized.put(key, str.length() <= 2048 ? str : str.substring(0, 2048));
            } else {
                sanitized.put(key, value);
            }
        });
        return Map.copyOf(sanitized);
    }

    public record ExecutionStartRecord(UUID executionId, String idempotencyKey, int attempt) {
    }

    public record RollbackPreparation(
            ProposedActionEntity action,
            ActionExecutor executor,
            Map<String, Object> preState
    ) {
    }
}
