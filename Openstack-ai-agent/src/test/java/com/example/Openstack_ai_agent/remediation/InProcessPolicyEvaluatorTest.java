package com.example.Openstack_ai_agent.remediation;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InProcessPolicyEvaluatorTest {

    private final ActionCatalogValidator validator = new ActionCatalogValidator();
    private final InProcessPolicyEvaluator evaluator = new InProcessPolicyEvaluator(validator);

    @Test
    void allowsAuthorizedOperatorWithinEnvironmentScope() {
        PolicyEvaluator.PolicyDecision decision = evaluator.evaluate(
                new PolicyEvaluator.PolicyEvaluationRequest(
                        "local",
                        ActionType.RESTART_K8S_WORKLOAD,
                        Map.of("namespace", "payments", "workload", "checkout-api", "kind", "Deployment"),
                        ActionRiskLevel.MEDIUM,
                        "checkout-api",
                        "operator",
                        Set.of("ROLE_OPERATOR", "incident:approve", "env:local"),
                        ApprovalDecisionType.APPROVED
                )
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.policyVersion()).isEqualTo("policy-v1.0.0");
        assertThat(decision.ruleId()).isEqualTo("RULE_ALLOW_V1");
        assertThat(decision.requiredApprovals()).isEqualTo(1);
    }

    @Test
    void deniesViewerLackingApprovalAuthority() {
        PolicyEvaluator.PolicyDecision decision = evaluator.evaluate(
                new PolicyEvaluator.PolicyEvaluationRequest(
                        "local",
                        ActionType.RESTART_CONTAINER,
                        Map.of("container", "orders-worker"),
                        ActionRiskLevel.MEDIUM,
                        "orders-worker",
                        "viewer",
                        Set.of("ROLE_VIEWER", "incident:read", "env:local"),
                        ApprovalDecisionType.APPROVED
                )
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.ruleId()).isEqualTo("RULE_APPROVAL_AUTHORITY_REQUIRED");
        assertThat(decision.policyVersion()).isEqualTo("policy-v1.0.0");
    }

    @Test
    void deniesOperatorOutsideAuthorizedEnvironmentScope() {
        PolicyEvaluator.PolicyDecision decision = evaluator.evaluate(
                new PolicyEvaluator.PolicyEvaluationRequest(
                        "production",
                        ActionType.RESTART_CONTAINER,
                        Map.of("container", "orders-worker"),
                        ActionRiskLevel.MEDIUM,
                        "orders-worker",
                        "operator",
                        Set.of("ROLE_OPERATOR", "incident:approve", "env:local", "env:staging"),
                        ApprovalDecisionType.APPROVED
                )
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.ruleId()).isEqualTo("RULE_ENVIRONMENT_SCOPE_DENIED");
    }

    @Test
    void deniesCriticalRiskWithoutElevatedPolicyAndAllowsWithElevatedAuthority() {
        PolicyEvaluator.PolicyEvaluationRequest base = new PolicyEvaluator.PolicyEvaluationRequest(
                "local",
                ActionType.RESTART_OPENSTACK_SERVICE,
                Map.of("node", "compute-01", "service", "nova-compute"),
                ActionRiskLevel.CRITICAL,
                "nova-compute",
                "operator",
                Set.of("incident:approve", "env:local"),
                ApprovalDecisionType.APPROVED
        );
        assertThat(evaluator.evaluate(base).allowed()).isFalse();
        assertThat(evaluator.evaluate(base).ruleId()).isEqualTo("RULE_CRITICAL_RISK_ELEVATED_POLICY_REQUIRED");

        PolicyEvaluator.PolicyEvaluationRequest elevated = new PolicyEvaluator.PolicyEvaluationRequest(
                "local",
                ActionType.RESTART_OPENSTACK_SERVICE,
                Map.of("node", "compute-01", "service", "nova-compute"),
                ActionRiskLevel.CRITICAL,
                "nova-compute",
                "lead-operator",
                Set.of("incident:approve", "env:local", "policy:critical-approve"),
                ApprovalDecisionType.APPROVED
        );
        assertThat(evaluator.evaluate(elevated).allowed()).isTrue();
    }

    @Test
    void allowsAuthorizedOperatorToRejectCriticalAction() {
        PolicyEvaluator.PolicyDecision decision = evaluator.evaluate(
                new PolicyEvaluator.PolicyEvaluationRequest(
                        "local",
                        ActionType.RESTART_OPENSTACK_SERVICE,
                        Map.of("node", "compute-01", "service", "nova-compute"),
                        ActionRiskLevel.CRITICAL,
                        "nova-compute",
                        "operator",
                        Set.of("incident:approve", "env:local"),
                        ApprovalDecisionType.REJECTED
                )
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.ruleId()).isEqualTo("RULE_ALLOW_REJECTION_V1");
    }

    @Test
    void deniesProtectedKubernetesNamespaceAndInvalidArguments() {
        PolicyEvaluator.PolicyDecision protectedNs = evaluator.evaluate(
                new PolicyEvaluator.PolicyEvaluationRequest(
                        "local",
                        ActionType.RESTART_K8S_WORKLOAD,
                        Map.of("namespace", "kube-system", "workload", "coredns", "kind", "Deployment"),
                        ActionRiskLevel.MEDIUM,
                        "coredns",
                        "operator",
                        Set.of("incident:approve", "env:local"),
                        ApprovalDecisionType.APPROVED
                )
        );
        assertThat(protectedNs.allowed()).isFalse();
        assertThat(protectedNs.ruleId()).isEqualTo("RULE_PROTECTED_NAMESPACE_DENIED");

        PolicyEvaluator.PolicyDecision invalidArgs = evaluator.evaluate(
                new PolicyEvaluator.PolicyEvaluationRequest(
                        "local",
                        ActionType.SCALE_K8S_DEPLOYMENT,
                        Map.of("namespace", "payments", "deployment", "checkout-api", "replicas", 99),
                        ActionRiskLevel.HIGH,
                        "checkout-api",
                        "operator",
                        Set.of("incident:approve", "env:local"),
                        ApprovalDecisionType.APPROVED
                )
        );
        assertThat(invalidArgs.allowed()).isFalse();
        assertThat(invalidArgs.ruleId()).isEqualTo("RULE_ACTION_CATALOG_INVALID");
    }

    @Test
    void deniesHighRiskInProductionWithoutElevatedPolicy() {
        PolicyEvaluator.PolicyDecision denied = evaluator.evaluate(
                new PolicyEvaluator.PolicyEvaluationRequest(
                        "production",
                        ActionType.ROLLBACK_K8S_DEPLOYMENT,
                        Map.of("namespace", "payments", "deployment", "checkout-api", "revision", 3),
                        ActionRiskLevel.HIGH,
                        "checkout-api",
                        "operator",
                        Set.of("incident:approve", "env:production"),
                        ApprovalDecisionType.APPROVED
                )
        );
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.ruleId()).isEqualTo("RULE_PROD_HIGH_RISK_ELEVATED_POLICY_REQUIRED");
    }
}
