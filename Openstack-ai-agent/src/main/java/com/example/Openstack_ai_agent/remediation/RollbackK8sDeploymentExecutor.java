package com.example.Openstack_ai_agent.remediation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class RollbackK8sDeploymentExecutor implements ActionExecutor {
    private final ActionCatalogValidator validator;
    private final RemediationAdapterClient adapterClient;

    public RollbackK8sDeploymentExecutor(
            ActionCatalogValidator validator,
            RemediationAdapterClient adapterClient
    ) {
        this.validator = validator;
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.ROLLBACK_K8S_DEPLOYMENT;
    }

    @Override
    public boolean isReversible() {
        return true;
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionContext context) {
        ValidatedActionProposal validated = validator.validateAndNormalize(
                ActionType.ROLLBACK_K8S_DEPLOYMENT,
                context.arguments()
        );
        String namespace = String.valueOf(validated.arguments().get("namespace"));
        String deployment = String.valueOf(validated.arguments().get("deployment"));
        Integer targetRevision = validated.arguments().containsKey("revision")
                ? ((Number) validated.arguments().get("revision")).intValue()
                : null;

        Optional<Integer> priorRevision = adapterClient.getK8sDeploymentRevision(
                namespace,
                deployment,
                context.timeout()
        );
        Map<String, Object> preState = new LinkedHashMap<>();
        boolean rollbackCapable = false;
        if (priorRevision.isPresent()
                && priorRevision.get() >= ActionCatalogValidator.MIN_K8S_REVISION
                && priorRevision.get() <= ActionCatalogValidator.MAX_K8S_REVISION) {
            preState.put("namespace", namespace);
            preState.put("deployment", deployment);
            preState.put("previousRevision", priorRevision.get());
            rollbackCapable = true;
        }

        RemediationAdapterClient.AdapterCallResult callResult = adapterClient.rollbackK8sDeployment(
                namespace,
                deployment,
                targetRevision,
                context.idempotencyKey(),
                context.timeout()
        );
        return ActionExecutionResult.fromAdapterResult(callResult, preState, rollbackCapable);
    }

    @Override
    public ActionExecutionResult rollback(ActionRollbackContext context) {
        Map<String, Object> preState = context.preState() == null ? Map.of() : context.preState();
        Object rawPriorRevision = preState.get("previousRevision");
        if (rawPriorRevision == null) {
            return ActionExecutionResult.failed(
                    "Cannot rollback ROLLBACK_K8S_DEPLOYMENT: prior revision was not captured",
                    Map.of("reason", "missing_previous_revision"),
                    Map.of(),
                    false
            );
        }

        Map<String, Object> rollbackArgs = new LinkedHashMap<>();
        rollbackArgs.put("namespace", preState.getOrDefault("namespace", context.originalArguments().get("namespace")));
        rollbackArgs.put("deployment", preState.getOrDefault("deployment", context.originalArguments().get("deployment")));
        rollbackArgs.put("revision", rawPriorRevision);

        ValidatedActionProposal validatedRollback = validator.validateAndNormalize(
                ActionType.ROLLBACK_K8S_DEPLOYMENT,
                rollbackArgs
        );
        String namespace = String.valueOf(validatedRollback.arguments().get("namespace"));
        String deployment = String.valueOf(validatedRollback.arguments().get("deployment"));
        int previousRevision = ((Number) validatedRollback.arguments().get("revision")).intValue();

        RemediationAdapterClient.AdapterCallResult callResult = adapterClient.rollbackK8sDeployment(
                namespace,
                deployment,
                previousRevision,
                context.idempotencyKey(),
                context.timeout()
        );
        return ActionExecutionResult.fromAdapterResult(callResult, Map.of(), false);
    }
}
