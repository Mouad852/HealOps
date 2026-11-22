package com.example.Openstack_ai_agent.remediation;

public class PolicyDeniedException extends RuntimeException {
    private final PolicyEvaluator.PolicyDecision decision;

    public PolicyDeniedException(PolicyEvaluator.PolicyDecision decision) {
        super(decision.reason());
        this.decision = decision;
    }

    public PolicyEvaluator.PolicyDecision getDecision() {
        return decision;
    }
}
