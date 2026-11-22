package com.example.Openstack_ai_agent.remediation;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RestartK8sWorkloadExecutor implements ActionExecutor {
    private final ActionCatalogValidator validator;
    private final RemediationAdapterClient adapterClient;

    public RestartK8sWorkloadExecutor(
            ActionCatalogValidator validator,
            RemediationAdapterClient adapterClient
    ) {
        this.validator = validator;
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.RESTART_K8S_WORKLOAD;
    }

    @Override
    public boolean isReversible() {
        return false;
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionContext context) {
        ValidatedActionProposal validated = validator.validateAndNormalize(
                ActionType.RESTART_K8S_WORKLOAD,
                context.arguments()
        );
        String namespace = String.valueOf(validated.arguments().get("namespace"));
        String workload = String.valueOf(validated.arguments().get("workload"));
        String kind = String.valueOf(validated.arguments().get("kind"));

        RemediationAdapterClient.AdapterCallResult callResult = adapterClient.restartK8sWorkload(
                namespace,
                kind,
                workload,
                context.idempotencyKey(),
                context.timeout()
        );
        return ActionExecutionResult.fromAdapterResult(callResult, Map.of(), false);
    }
}
