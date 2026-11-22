package com.example.Openstack_ai_agent.remediation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Versioned in-process policy evaluator ({@code policy-v1.0.0}).
 * Enforces:
 * 1. Approval authority ({@code incident:approve})
 * 2. Environment scope ({@code env:<environmentId>})
 * 3. Action allowlist & strict argument validation via {@link ActionCatalogValidator}
 * 4. Protected target guards (e.g., kube-system/kube-public/kube-node-lease)
 * 5. Risk/environment rules (CRITICAL requires {@code policy:critical-approve};
 *    HIGH in production requires {@code policy:prod-high-risk})
 */
@Component
public class InProcessPolicyEvaluator implements PolicyEvaluator {
    public static final String POLICY_VERSION = "policy-v1.0.0";
    public static final String AUTHORITY_APPROVE = "incident:approve";
    public static final String AUTHORITY_CRITICAL_APPROVE = "policy:critical-approve";
    public static final String AUTHORITY_PROD_HIGH_RISK = "policy:prod-high-risk";

    private static final Set<String> PROTECTED_K8S_NAMESPACES = Set.of(
            "kube-system",
            "kube-public",
            "kube-node-lease"
    );

    private static final Set<String> PRODUCTION_ENVIRONMENTS = Set.of(
            "prod",
            "production"
    );

    private final ActionCatalogValidator actionCatalogValidator;

    public InProcessPolicyEvaluator(ActionCatalogValidator actionCatalogValidator) {
        this.actionCatalogValidator = actionCatalogValidator;
    }

    @Override
    public String policyVersion() {
        return POLICY_VERSION;
    }

    @Override
    public PolicyDecision evaluate(PolicyEvaluationRequest request) {
        Set<String> authorities = request.authorities() == null ? Set.of() : request.authorities();
        String environmentId = request.environmentId() == null ? "" : request.environmentId().trim();
        Map<String, Object> baseContext = new LinkedHashMap<>();
        baseContext.put("environmentId", environmentId);
        baseContext.put("actor", request.actor() == null ? "anonymous" : request.actor());
        if (request.actionType() != null) {
            baseContext.put("actionType", request.actionType().name());
        }
        if (request.riskLevel() != null) {
            baseContext.put("riskLevel", request.riskLevel().name());
        }
        if (request.requestedDecision() != null) {
            baseContext.put("requestedDecision", request.requestedDecision().name());
        }

        if (!authorities.contains(AUTHORITY_APPROVE)) {
            return PolicyDecision.deny(
                    POLICY_VERSION,
                    "RULE_APPROVAL_AUTHORITY_REQUIRED",
                    "Caller lacks required authority '" + AUTHORITY_APPROVE + "'",
                    baseContext
            );
        }

        if (environmentId.isBlank() || !authorities.contains("env:" + environmentId)) {
            return PolicyDecision.deny(
                    POLICY_VERSION,
                    "RULE_ENVIRONMENT_SCOPE_DENIED",
                    "Caller is not authorized for environment '" + environmentId + "' (missing authority 'env:" + environmentId + "')",
                    baseContext
            );
        }

        // Operators authorized for the environment may always reject a proposed action.
        if (request.requestedDecision() == ApprovalDecisionType.REJECTED) {
            return PolicyDecision.allow(
                    POLICY_VERSION,
                    "RULE_ALLOW_REJECTION_V1",
                    "Authorized operator may reject proposed action in environment '" + environmentId + "'",
                    1,
                    baseContext
            );
        }

        if (request.actionType() == null || request.actionType() == ActionType.NO_ACTION) {
            return PolicyDecision.deny(
                    POLICY_VERSION,
                    "RULE_ACTION_NOT_EXECUTABLE",
                    "Action type " + request.actionType() + " is not an executable remediation action",
                    baseContext
            );
        }

        ValidatedActionProposal validated;
        try {
            validated = actionCatalogValidator.validateAndNormalize(request.actionType(), request.arguments());
        } catch (ActionCatalogValidationException exception) {
            baseContext.put("validationError", exception.getMessage());
            return PolicyDecision.deny(
                    POLICY_VERSION,
                    "RULE_ACTION_CATALOG_INVALID",
                    "Action arguments failed catalog validation: " + exception.getMessage(),
                    baseContext
            );
        }

        Object rawNamespace = validated.arguments().get("namespace");
        if (rawNamespace != null) {
            String ns = String.valueOf(rawNamespace).trim().toLowerCase(Locale.ROOT);
            if (PROTECTED_K8S_NAMESPACES.contains(ns)) {
                baseContext.put("protectedNamespace", ns);
                return PolicyDecision.deny(
                        POLICY_VERSION,
                        "RULE_PROTECTED_NAMESPACE_DENIED",
                        "Automated remediation is blocked for protected Kubernetes namespace '" + ns + "'",
                        baseContext
                );
            }
        }

        ActionRiskLevel effectiveRisk = request.riskLevel() != null ? request.riskLevel() : validated.riskLevel();
        if (effectiveRisk == ActionRiskLevel.CRITICAL && !authorities.contains(AUTHORITY_CRITICAL_APPROVE)) {
            return PolicyDecision.deny(
                    POLICY_VERSION,
                    "RULE_CRITICAL_RISK_ELEVATED_POLICY_REQUIRED",
                    "CRITICAL risk actions require elevated authority '" + AUTHORITY_CRITICAL_APPROVE + "'",
                    baseContext
            );
        }

        boolean isProduction = PRODUCTION_ENVIRONMENTS.contains(environmentId.toLowerCase(Locale.ROOT));
        if (isProduction && effectiveRisk == ActionRiskLevel.HIGH && !authorities.contains(AUTHORITY_PROD_HIGH_RISK)) {
            return PolicyDecision.deny(
                    POLICY_VERSION,
                    "RULE_PROD_HIGH_RISK_ELEVATED_POLICY_REQUIRED",
                    "HIGH risk actions in production environment '" + environmentId + "' require elevated authority '" + AUTHORITY_PROD_HIGH_RISK + "'",
                    baseContext
            );
        }

        return PolicyDecision.allow(
                POLICY_VERSION,
                "RULE_ALLOW_V1",
                "Action " + request.actionType() + " permitted under " + POLICY_VERSION + " for environment '" + environmentId + "'",
                1,
                baseContext
        );
    }
}
