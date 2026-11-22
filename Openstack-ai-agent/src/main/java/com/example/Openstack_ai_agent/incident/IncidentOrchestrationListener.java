package com.example.Openstack_ai_agent.incident;

import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.Map;

/**
 * Event-driven phase handlers for the Incident Autopilot workflow.
 * Runs with {@link Propagation#NOT_SUPPORTED} so external adapter I/O never holds open a database transaction;
 * each state transition and evidence record executes inside its own short transactional boundary.
 */
@Component
public class IncidentOrchestrationListener {
    private static final Logger log = LoggerFactory.getLogger(IncidentOrchestrationListener.class);
    private static final String WORKFLOW_ACTOR = "workflow-engine";

    private final IncidentService incidentService;
    private final EvidenceCollectionService evidenceCollectionService;
    private final CorrelationService correlationService;

    public IncidentOrchestrationListener(
            IncidentService incidentService,
            EvidenceCollectionService evidenceCollectionService,
            CorrelationService correlationService
    ) {
        this.incidentService = incidentService;
        this.evidenceCollectionService = evidenceCollectionService;
        this.correlationService = correlationService;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onIncidentAlerted(IncidentAlertedEvent event) {
        IncidentResponse current = incidentService.findById(event.incidentId());

        if (current.status() == IncidentStatus.ALERTED) {
            current = incidentService.transition(event.incidentId(), new TransitionIncidentRequest(
                    IncidentWorkflowEvent.COLLECTION_STARTED,
                    null,
                    WORKFLOW_ACTOR,
                    "Automated evidence collection started",
                    event.traceId(),
                    Map.of("service", event.service()),
                    current.version()
            ));
        }

        if (current.status() != IncidentStatus.GATHERING_EVIDENCE) {
            log.debug("Skipping evidence collection for incident {} in status {}", event.incidentId(), current.status());
            return;
        }

        List<EvidenceItemResponse> collected = evidenceCollectionService.collect(event.incidentId());
        long errorCount = collected.stream()
                .filter(item -> item.type() == EvidenceType.COLLECTION_ERROR)
                .count();

        IncidentResponse refreshed = incidentService.findById(event.incidentId());
        if (refreshed.status() == IncidentStatus.GATHERING_EVIDENCE) {
            incidentService.transition(event.incidentId(), new TransitionIncidentRequest(
                    IncidentWorkflowEvent.EVIDENCE_GATHERED,
                    null,
                    WORKFLOW_ACTOR,
                    "Collected " + collected.size() + " bounded evidence item(s)",
                    event.traceId(),
                    Map.of(
                            "evidenceCount", Integer.toString(collected.size()),
                            "collectionErrorCount", Long.toString(errorCount)
                    ),
                    refreshed.version()
            ));
        }
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onEvidenceGathered(IncidentEvidenceGatheredEvent event) {
        IncidentResponse current = incidentService.findById(event.incidentId());
        if (current.status() != IncidentStatus.CORRELATING_SIGNALS) {
            log.debug("Skipping signal correlation for incident {} in status {}", event.incidentId(), current.status());
            return;
        }

        List<EvidenceItemResponse> evidence = evidenceCollectionService.findAll(event.incidentId());
        CorrelationService.CorrelationSummary summary = correlationService.correlate(current, evidence);

        IncidentResponse refreshed = incidentService.findById(event.incidentId());
        if (refreshed.status() == IncidentStatus.CORRELATING_SIGNALS) {
            incidentService.transition(event.incidentId(), new TransitionIncidentRequest(
                    IncidentWorkflowEvent.SIGNALS_CORRELATED,
                    null,
                    WORKFLOW_ACTOR,
                    "Correlated " + summary.evidenceCount() + " evidence item(s) across " + summary.sources().size() + " source(s)",
                    event.traceId(),
                    summary.toEventMetadata(),
                    refreshed.version()
            ));
        }
    }
}
