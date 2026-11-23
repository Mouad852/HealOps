package com.example.Openstack_ai_agent.remediation;

import com.example.Openstack_ai_agent.diagnosis.DeterministicDiagnosticsEngine;
import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.incident.IncidentActionApprovedEvent;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentRollbackRequestedEvent;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentVerificationRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Event-driven phase handlers for execution, post-remediation {@code AFTER} verification,
 * and {@code POST_ROLLBACK} recovery.
 * Runs with {@link Propagation#NOT_SUPPORTED} so no DB transaction is held open during
 * external adapter calls or verification stabilization delays.
 */
@Component
public class RemediationOrchestrationListener {
    private static final Logger log = LoggerFactory.getLogger(RemediationOrchestrationListener.class);
    private static final int MAX_STARTING_HEALTH_RETRIES = 15;
    private static final Duration STARTING_HEALTH_RETRY_DELAY = Duration.ofSeconds(2);

    private final IncidentService incidentService;
    private final RemediationOrchestrationService orchestrationService;
    private final EvidenceCollectionService evidenceCollectionService;
    private final CorrelationService correlationService;
    private final DeterministicDiagnosticsEngine diagnosticsEngine;
    private final PolicyEvaluator policyEvaluator;
    private final RemediationProperties remediationProperties;
    private final VerificationProperties verificationProperties;

    public RemediationOrchestrationListener(
            IncidentService incidentService,
            RemediationOrchestrationService orchestrationService,
            EvidenceCollectionService evidenceCollectionService,
            CorrelationService correlationService,
            DeterministicDiagnosticsEngine diagnosticsEngine,
            PolicyEvaluator policyEvaluator,
            RemediationProperties remediationProperties,
            VerificationProperties verificationProperties
    ) {
        this.incidentService = incidentService;
        this.orchestrationService = orchestrationService;
        this.evidenceCollectionService = evidenceCollectionService;
        this.correlationService = correlationService;
        this.diagnosticsEngine = diagnosticsEngine;
        this.policyEvaluator = policyEvaluator;
        this.remediationProperties = remediationProperties;
        this.verificationProperties = verificationProperties;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onActionApproved(IncidentActionApprovedEvent event) {
        IncidentResponse current = incidentService.findById(event.incidentId());
        if (current.status() != IncidentStatus.EXECUTING) {
            log.debug("Skipping execution for incident {} in status {}", event.incidentId(), current.status());
            return;
        }

        Optional<ProposedActionEntity> actionOpt =
                orchestrationService.resolveActionForExecution(event.incidentId(), event.actionId());
        if (actionOpt.isEmpty()) {
            orchestrationService.failMissingExecutorOrAction(
                    event.incidentId(),
                    event.actionId(),
                    "No approved remediation action found for incident " + event.incidentId()
            );
            return;
        }

        ProposedActionEntity action = actionOpt.get();
        Optional<ActionExecutor> executorOpt = orchestrationService.findExecutor(action.getActionType());
        if (executorOpt.isEmpty()) {
            orchestrationService.failMissingExecutorOrAction(
                    event.incidentId(),
                    action.getId(),
                    "No registered ActionExecutor supports action type " + action.getActionType()
            );
            return;
        }

        ActionExecutor executor = executorOpt.get();
        Optional<RemediationOrchestrationService.ExecutionStartRecord> started =
                orchestrationService.beginForwardExecution(current, action);
        if (started.isEmpty()) {
            log.info("Skipping duplicate execution for approved action {}", action.getId());
            return;
        }

        ActionExecutor.ActionExecutionResult result;
        try {
            result = executor.execute(new ActionExecutor.ActionExecutionContext(
                    current.id(),
                    action.getId(),
                    current.environmentId(),
                    action.getActionType(),
                    action.getArguments(),
                    started.get().idempotencyKey(),
                    current.traceId(),
                    remediationProperties.getExecutionTimeout()
            ));
        } catch (RuntimeException exception) {
            result = ActionExecutor.ActionExecutionResult.failed(
                    "Action execution threw " + exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    Map.of("errorType", exception.getClass().getSimpleName()),
                    Map.of(),
                    false
            );
        }

        orchestrationService.completeForwardExecutionAndTransition(
                current.id(),
                action.getId(),
                started.get().executionId(),
                executor,
                result,
                policyEvaluator.policyVersion()
        );
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onVerificationRequested(IncidentVerificationRequestedEvent event) {
        IncidentResponse current = incidentService.findById(event.incidentId());
        if (current.status() != IncidentStatus.VERIFYING) {
            log.debug("Skipping verification for incident {} in status {}", event.incidentId(), current.status());
            return;
        }

        Duration stabilizationDelay = verificationProperties.getStabilizationDelay();
        waitStabilizationWindow(stabilizationDelay);

        List<EvidenceItemResponse> freshEvidence = evidenceCollectionService.collectRuntimeEvidence(
                event.incidentId(),
                Set.of(IncidentStatus.VERIFYING)
        );
        CorrelationService.CorrelationSummary correlation = correlationService.correlate(current, freshEvidence);
        DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics =
                diagnosticsEngine.diagnose(current, freshEvidence, correlation);

        for (int attempt = 0; attempt < MAX_STARTING_HEALTH_RETRIES && isStartingHealthOnly(diagnostics, freshEvidence); attempt++) {
            if (hasPositiveDelay(stabilizationDelay)) {
                waitStabilizationWindow(STARTING_HEALTH_RETRY_DELAY);
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            log.debug("Verification for incident {} saw starting container healthcheck (attempt {}/{}); re-collecting runtime evidence",
                    event.incidentId(), attempt + 1, MAX_STARTING_HEALTH_RETRIES);
            freshEvidence = evidenceCollectionService.collectRuntimeEvidence(
                    event.incidentId(),
                    Set.of(IncidentStatus.VERIFYING)
            );
            correlation = correlationService.correlate(current, freshEvidence);
            diagnostics = diagnosticsEngine.diagnose(current, freshEvidence, correlation);
        }

        orchestrationService.recordVerificationAndTransition(
                event.incidentId(),
                event.actionId(),
                freshEvidence,
                correlation,
                diagnostics
        );
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onRollbackRequested(IncidentRollbackRequestedEvent event) {
        IncidentResponse current = incidentService.findById(event.incidentId());
        if (current.status() != IncidentStatus.ROLLING_BACK) {
            log.debug("Skipping rollback for incident {} in status {}", event.incidentId(), current.status());
            return;
        }

        Optional<RemediationOrchestrationService.RollbackPreparation> preparationOpt =
                orchestrationService.prepareRollback(event.incidentId(), event.actionId());
        if (preparationOpt.isEmpty()) {
            orchestrationService.failImpossibleRollback(
                    event.incidentId(),
                    event.actionId(),
                    "Rollback could not proceed: action is not reversible or prior pre-state was not captured"
            );
            return;
        }

        RemediationOrchestrationService.RollbackPreparation preparation = preparationOpt.get();
        Optional<RemediationOrchestrationService.ExecutionStartRecord> started =
                orchestrationService.beginRollbackExecution(current, preparation.action(), preparation.preState());
        if (started.isEmpty()) {
            log.info("Skipping duplicate rollback for action {}", preparation.action().getId());
            return;
        }

        ActionExecutor.ActionExecutionResult rollbackResult;
        try {
            rollbackResult = preparation.executor().rollback(new ActionExecutor.ActionRollbackContext(
                    current.id(),
                    preparation.action().getId(),
                    current.environmentId(),
                    preparation.action().getActionType(),
                    preparation.action().getArguments(),
                    preparation.preState(),
                    started.get().idempotencyKey(),
                    current.traceId(),
                    remediationProperties.getExecutionTimeout()
            ));
        } catch (RuntimeException exception) {
            rollbackResult = ActionExecutor.ActionExecutionResult.failed(
                    "Rollback execution threw " + exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    Map.of("errorType", exception.getClass().getSimpleName()),
                    Map.of(),
                    false
            );
        }

        List<EvidenceItemResponse> postRollbackEvidence = evidenceCollectionService.collectRuntimeEvidence(
                event.incidentId(),
                Set.of(IncidentStatus.ROLLING_BACK)
        );
        CorrelationService.CorrelationSummary correlation = correlationService.correlate(current, postRollbackEvidence);
        DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics =
                diagnosticsEngine.diagnose(current, postRollbackEvidence, correlation);

        orchestrationService.completeRollbackAndTransition(
                current.id(),
                preparation.action().getId(),
                started.get().executionId(),
                rollbackResult,
                postRollbackEvidence,
                correlation,
                diagnostics
        );
    }

    private boolean isStartingHealthOnly(
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics,
            List<EvidenceItemResponse> evidence
    ) {
        if (diagnostics == null || diagnostics.healthSignals() == null) {
            return false;
        }
        DeterministicDiagnosticsEngine.ExtractedHealthSignals signals = diagnostics.healthSignals();
        if (!"Running".equals(signals.workloadStatus())
                || "HEALTHY".equals(signals.healthStatus())
                || !diagnostics.findings().isEmpty()) {
            return false;
        }
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        return evidence.stream().anyMatch(RemediationOrchestrationListener::containsStartingHealth);
    }

    private static boolean containsStartingHealth(EvidenceItemResponse item) {
        if (item == null) {
            return false;
        }
        if (item.metadata() != null && "starting".equalsIgnoreCase(item.metadata().get("health"))) {
            return true;
        }
        String excerpt = item.excerpt();
        if (excerpt == null) {
            return false;
        }
        String lower = excerpt.toLowerCase(Locale.ROOT);
        return lower.contains("\"health\":\"starting\"")
                || lower.contains("health=starting")
                || lower.contains("health: starting");
    }

    private static boolean hasPositiveDelay(Duration delay) {
        return delay != null && !delay.isZero() && !delay.isNegative();
    }

    private void waitStabilizationWindow(Duration delay) {
        if (!hasPositiveDelay(delay)) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
