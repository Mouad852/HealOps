package com.example.Openstack_ai_agent.incident;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentTransitionMapTest {

    @Test
    void allowsEveryDocumentedLifecycleTransition() {
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.ALERTED, IncidentWorkflowEvent.COLLECTION_STARTED, null))
                .isEqualTo(IncidentStatus.GATHERING_EVIDENCE);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.GATHERING_EVIDENCE, IncidentWorkflowEvent.EVIDENCE_GATHERED, null))
                .isEqualTo(IncidentStatus.CORRELATING_SIGNALS);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.CORRELATING_SIGNALS, IncidentWorkflowEvent.SIGNALS_CORRELATED, null))
                .isEqualTo(IncidentStatus.DIAGNOSING);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.DIAGNOSING, IncidentWorkflowEvent.ACTION_PROPOSED, null))
                .isEqualTo(IncidentStatus.AWAITING_APPROVAL);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.DIAGNOSING, IncidentWorkflowEvent.NO_ACTION_REQUIRED, null))
                .isEqualTo(IncidentStatus.VERIFYING);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.AWAITING_APPROVAL, IncidentWorkflowEvent.ACTION_APPROVED, null))
                .isEqualTo(IncidentStatus.EXECUTING);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.AWAITING_APPROVAL, IncidentWorkflowEvent.ACTION_REJECTED, null))
                .isEqualTo(IncidentStatus.CANCELLED);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.EXECUTING, IncidentWorkflowEvent.EXECUTION_SUCCEEDED, null))
                .isEqualTo(IncidentStatus.VERIFYING);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.EXECUTING, IncidentWorkflowEvent.EXECUTION_FAILED, FailureDisposition.FAILED))
                .isEqualTo(IncidentStatus.FAILED);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.EXECUTING, IncidentWorkflowEvent.EXECUTION_FAILED, FailureDisposition.ROLLING_BACK))
                .isEqualTo(IncidentStatus.ROLLING_BACK);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.VERIFYING, IncidentWorkflowEvent.VERIFICATION_PASSED, null))
                .isEqualTo(IncidentStatus.CLOSED);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.VERIFYING, IncidentWorkflowEvent.VERIFICATION_FAILED, FailureDisposition.FAILED))
                .isEqualTo(IncidentStatus.FAILED);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.VERIFYING, IncidentWorkflowEvent.VERIFICATION_FAILED, FailureDisposition.ROLLING_BACK))
                .isEqualTo(IncidentStatus.ROLLING_BACK);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.ROLLING_BACK, IncidentWorkflowEvent.ROLLBACK_SUCCEEDED, null))
                .isEqualTo(IncidentStatus.ROLLED_BACK);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.ROLLING_BACK, IncidentWorkflowEvent.ROLLBACK_FAILED, null))
                .isEqualTo(IncidentStatus.FAILED);
        assertThat(IncidentTransitionMap.targetFor(IncidentStatus.FAILED, IncidentWorkflowEvent.PHASE_RETRIED, null))
                .isEqualTo(IncidentStatus.GATHERING_EVIDENCE);
    }

    @Test
    void rejectsFailureEventsWithoutDisposition() {
        assertThatThrownBy(() -> IncidentTransitionMap.targetFor(IncidentStatus.EXECUTING, IncidentWorkflowEvent.EXECUTION_FAILED, null))
                .isInstanceOf(InvalidIncidentTransitionException.class);
        assertThatThrownBy(() -> IncidentTransitionMap.targetFor(IncidentStatus.VERIFYING, IncidentWorkflowEvent.VERIFICATION_FAILED, null))
                .isInstanceOf(InvalidIncidentTransitionException.class);
    }

    @Test
    void rejectsTransitionsFromTerminalStates() {
        for (IncidentStatus terminal : new IncidentStatus[]{IncidentStatus.CLOSED, IncidentStatus.ROLLED_BACK, IncidentStatus.CANCELLED}) {
            for (IncidentWorkflowEvent event : IncidentWorkflowEvent.values()) {
                assertThatThrownBy(() -> IncidentTransitionMap.targetFor(terminal, event, FailureDisposition.FAILED))
                        .isInstanceOf(InvalidIncidentTransitionException.class);
            }
        }
    }
}
