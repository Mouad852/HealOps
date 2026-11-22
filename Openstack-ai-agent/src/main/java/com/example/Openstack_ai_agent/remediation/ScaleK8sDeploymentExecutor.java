package com.example.Openstack_ai_agent.remediation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class ScaleK8sDeploymentExecutor implements ActionExecutor {
    private final ActionCatalogValidator validator;
    private final RemediationAdapterClient adapterClient;

    public ScaleK8sDeploymentExecutor(
            ActionCatalogValidator validator,
            RemediationAdapterClient adapterClient
    ) {
        this.validator = validator;
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.SCALE_K8S_DEPLOYMENT;
    }

    @Override
    public boolean isReversible() {
        return true;
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionContext context) {
        ValidatedActionProposal validated = validator.validateAndNormalize(
                ActionType.SCALE_K8S_DEPLOYMENT,
                context.arguments()
        );
        String namespace = String.valueOf(validated.arguments().get("namespace"));
        String deployment = String.valueOf(validated.arguments().get("deployment"));
        int targetReplicas = ((Number) validated.arguments().get("replicas")).intValue();

        Optional<Integer> priorReplicas = adapterClient.getK8sDeploymentReplicas(
                namespace,
                deployment,
                context.timeout()
        );
        Map<String, Object> preState = new LinkedHashMap<>();
        boolean rollbackCapable = false;
        if (priorReplicas.isPresent()
                && priorReplicas.get() >= ActionCatalogValidator.MIN_K8S_REPLICAS
                && priorReplicas.get() <= ActionCatalogValidator.MAX_K8S_REPLICAS) {
            preState.put("namespace", namespace);
            preState.put("deployment", deployment);
            preState.put("previousReplicas", priorReplicas.get());
            rollbackCapable = true;
        }

        RemediationAdapterClient.AdapterCallResult callResult = adapterClient.scaleK8sDeployment(
                namespace,
                deployment,
                targetReplicas,
                context.idempotencyKey(),
                context.timeout()
        );
        return ActionExecutionResult.fromAdapterResult(callResult, preState, rollbackCapable);
    }

    @Override
    public ActionExecutionResult rollback(ActionRollbackContext context) {
        Map<String, Object> preState = context.preState() == null ? Map.of() : context.preState();
        Object rawPrior = preState.get("previousReplicas");
        if (rawPrior == null) {
            return ActionExecutionResult.failed(
                    "Cannot rollback SCALE_K8S_DEPLOYMENT: prior replica count was not captured",
                    Map.of("reason", "missing_previous_replicas"),
                    Map.of(),
                    false
            );
        }

        Map<String, Object> rollbackArgs = new LinkedHashMap<>();
        rollbackArgs.put("namespace", preState.getOrDefault("namespace", context.originalArguments().get("namespace")));
        rollbackArgs.put("deployment", preState.getOrDefault("deployment", context.originalArguments().get("deployment")));
        rollbackArgs.put("replicas", rawPrior);

        ValidatedActionProposal validatedRollback = validator.validateAndNormalize(
                ActionType.SCALE_K8S_DEPLOYMENT,
                rollbackArgs
        );
        String namespace = String.valueOf(validatedRollback.arguments().get("namespace"));
        String deployment = String.valueOf(validatedRollback.arguments().get("deployment"));
        int previousReplicas = ((Number) validatedRollback.arguments().get("replicas")).intValue();

        RemediationAdapterClient.AdapterCallResult callResult = adapterClient.scaleK8sDeployment(
                namespace,
                deployment,
                previousReplicas,
                context.idempotencyKey(),
                context.timeout()
        );
        return ActionExecutionResult.fromAdapterResult(callResult, Map.of(), false);
    }
}
