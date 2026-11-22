package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentSignalsCorrelatedEvent;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentWorkflowEvent;
import com.example.Openstack_ai_agent.incident.TransitionIncidentRequest;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles {@link IncidentSignalsCorrelatedEvent} with {@link Propagation#NOT_SUPPORTED}
 * so no database transaction is held while invoking the optional LLM diagnosis service.
 */
@Component
public class DiagnosisOrchestrationListener {
    private static final Logger log = LoggerFactory.getLogger(DiagnosisOrchestrationListener.class);
    private static final String WORKFLOW_ACTOR = "workflow-engine";

    private final IncidentService incidentService;
    private final EvidenceCollectionService evidenceCollectionService;
    private final CorrelationService correlationService;
    private final IncidentDiagnosisService incidentDiagnosisService;

    public DiagnosisOrchestrationListener(
            IncidentService incidentService,
            EvidenceCollectionService evidenceCollectionService,
            CorrelationService correlationService,
            IncidentDiagnosisService incidentDiagnosisService
    ) {
        this.incidentService = incidentService;
        this.evidenceCollectionService = evidenceCollectionService;
        this.correlationService = correlationService;
        this.incidentDiagnosisService = incidentDiagnosisService;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onSignalsCorrelated(IncidentSignalsCorrelatedEvent event) {
        IncidentResponse current = incidentService.findById(event.incidentId());
        if (current.status() != IncidentStatus.DIAGNOSING) {
            log.debug("Skipping diagnosis for incident {} in status {}", event.incidentId(), current.status());
            return;
        }

        List<EvidenceItemResponse> evidence = evidenceCollectionService.findAll(event.incidentId());
        CorrelationService.CorrelationSummary correlation = correlationService.correlate(current, evidence);

        IncidentDiagnosisService.DiagnosisExecutionResult result =
                incidentDiagnosisService.diagnoseAndPropose(current, evidence, correlation);

        IncidentResponse refreshed = incidentService.findById(event.incidentId());
        if (refreshed.status() != IncidentStatus.DIAGNOSING) {
            log.debug("Incident {} moved out of DIAGNOSING ({}) before transition", event.incidentId(), refreshed.status());
            return;
        }

        ProposedActionResponse proposedAction = result.proposedAction();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("diagnosisId", result.diagnosis().id().toString());
        metadata.put("beforeSnapshotId", result.beforeSnapshot().id().toString());
        metadata.put("modelVersion", result.diagnosis().modelVersion());
        metadata.put("ruleVersion", result.diagnosis().ruleVersion());

        if (proposedAction != null) {
            metadata.put("actionId", proposedAction.id().toString());
            metadata.put("actionType", proposedAction.actionType().name());
            metadata.put("riskLevel", proposedAction.riskLevel().name());
            incidentService.transition(event.incidentId(), new TransitionIncidentRequest(
                    IncidentWorkflowEvent.ACTION_PROPOSED,
                    null,
                    WORKFLOW_ACTOR,
                    "Proposed allowlisted action " + proposedAction.actionType() + " awaiting operator approval",
                    event.traceId(),
                    Map.copyOf(metadata),
                    refreshed.version()
            ));
        } else {
            metadata.put("actionType", ActionType.NO_ACTION.name());
            incidentService.transition(event.incidentId(), new TransitionIncidentRequest(
                    IncidentWorkflowEvent.NO_ACTION_REQUIRED,
                    null,
                    WORKFLOW_ACTOR,
                    "Diagnosis completed with NO_ACTION; proceeding to verification",
                    event.traceId(),
                    Map.copyOf(metadata),
                    refreshed.version()
            ));
        }
    }
}
