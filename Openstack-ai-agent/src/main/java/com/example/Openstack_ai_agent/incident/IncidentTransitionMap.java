package com.example.Openstack_ai_agent.incident;

import java.util.EnumMap;
import java.util.Map;

final class IncidentTransitionMap {
    private static final Map<IncidentStatus, Map<IncidentWorkflowEvent, IncidentStatus>> TRANSITIONS = transitions();

    private IncidentTransitionMap() {
    }

    static IncidentStatus targetFor(
            IncidentStatus currentStatus,
            IncidentWorkflowEvent event,
            FailureDisposition failureDisposition
    ) {
        if (event == IncidentWorkflowEvent.EXECUTION_FAILED || event == IncidentWorkflowEvent.VERIFICATION_FAILED) {
            requireFailureDisposition(currentStatus, event, failureDisposition);
            return failureDisposition == FailureDisposition.ROLLING_BACK
                    ? IncidentStatus.ROLLING_BACK
                    : IncidentStatus.FAILED;
        }

        IncidentStatus targetStatus = TRANSITIONS.getOrDefault(currentStatus, Map.of()).get(event);
        if (targetStatus == null) {
            throw new InvalidIncidentTransitionException(currentStatus, event);
        }
        return targetStatus;
    }

    private static void requireFailureDisposition(
            IncidentStatus currentStatus,
            IncidentWorkflowEvent event,
            FailureDisposition failureDisposition
    ) {
        boolean validSource = (event == IncidentWorkflowEvent.EXECUTION_FAILED && currentStatus == IncidentStatus.EXECUTING)
                || (event == IncidentWorkflowEvent.VERIFICATION_FAILED && currentStatus == IncidentStatus.VERIFYING);
        if (!validSource || failureDisposition == null) {
            throw new InvalidIncidentTransitionException(currentStatus, event);
        }
    }

    private static Map<IncidentStatus, Map<IncidentWorkflowEvent, IncidentStatus>> transitions() {
        Map<IncidentStatus, Map<IncidentWorkflowEvent, IncidentStatus>> transitions = new EnumMap<>(IncidentStatus.class);
        transitions.put(IncidentStatus.ALERTED, Map.of(
                IncidentWorkflowEvent.COLLECTION_STARTED, IncidentStatus.GATHERING_EVIDENCE
        ));
        transitions.put(IncidentStatus.GATHERING_EVIDENCE, Map.of(
                IncidentWorkflowEvent.EVIDENCE_GATHERED, IncidentStatus.CORRELATING_SIGNALS
        ));
        transitions.put(IncidentStatus.CORRELATING_SIGNALS, Map.of(
                IncidentWorkflowEvent.SIGNALS_CORRELATED, IncidentStatus.DIAGNOSING
        ));
        transitions.put(IncidentStatus.DIAGNOSING, Map.of(
                IncidentWorkflowEvent.ACTION_PROPOSED, IncidentStatus.AWAITING_APPROVAL,
                IncidentWorkflowEvent.NO_ACTION_REQUIRED, IncidentStatus.VERIFYING
        ));
        transitions.put(IncidentStatus.AWAITING_APPROVAL, Map.of(
                IncidentWorkflowEvent.ACTION_APPROVED, IncidentStatus.EXECUTING,
                IncidentWorkflowEvent.ACTION_REJECTED, IncidentStatus.CANCELLED
        ));
        transitions.put(IncidentStatus.EXECUTING, Map.of(
                IncidentWorkflowEvent.EXECUTION_SUCCEEDED, IncidentStatus.VERIFYING
        ));
        transitions.put(IncidentStatus.VERIFYING, Map.of(
                IncidentWorkflowEvent.VERIFICATION_PASSED, IncidentStatus.CLOSED
        ));
        transitions.put(IncidentStatus.ROLLING_BACK, Map.of(
                IncidentWorkflowEvent.ROLLBACK_SUCCEEDED, IncidentStatus.ROLLED_BACK,
                IncidentWorkflowEvent.ROLLBACK_FAILED, IncidentStatus.FAILED
        ));
        transitions.put(IncidentStatus.FAILED, Map.of(
                IncidentWorkflowEvent.PHASE_RETRIED, IncidentStatus.GATHERING_EVIDENCE
        ));
        return Map.copyOf(transitions);
    }
}
