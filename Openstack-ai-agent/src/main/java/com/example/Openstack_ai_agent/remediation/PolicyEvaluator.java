package com.example.Openstack_ai_agent.remediation;

import java.util.Map;
import java.util.Set;

/**
 * Policy evaluation contract for governance of remediation approvals and executions.
 * Designed as an explicit boundary so an external Open Policy Agent (OPA) evaluator
 * can replace or augment the in-process ruleset without changing callers.
 */
public interface PolicyEvaluator {
    String policyVersion();

    PolicyDecision evaluate(PolicyEvaluationRequest request);

    record PolicyEvaluationRequest(
            String environmentId,
            ActionType actionType,
            Map<String, Object> arguments,
            ActionRiskLevel riskLevel,
            String targetService,
            String actor,
            Set<String> authorities,
            ApprovalDecisionType requestedDecision
    ) {
    }

    record PolicyDecision(
            boolean allowed,
            String policyVersion,
            String ruleId,
            String reason,
            int requiredApprovals,
            Map<String, Object> context
    ) {
        public static PolicyDecision allow(
                String policyVersion,
                String ruleId,
                String reason,
                int requiredApprovals,
                Map<String, Object> context
        ) {
            return new PolicyDecision(
                    true,
                    policyVersion,
                    ruleId,
                    reason,
                    requiredApprovals,
                    context == null ? Map.of() : Map.copyOf(context)
            );
        }

        public static PolicyDecision deny(
                String policyVersion,
                String ruleId,
                String reason,
                Map<String, Object> context
        ) {
            return new PolicyDecision(
                    false,
                    policyVersion,
                    ruleId,
                    reason,
                    1,
                    context == null ? Map.of() : Map.copyOf(context)
            );
        }
    }
}
