package com.example.Openstack_ai_agent.remediation;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RestartContainerExecutor implements ActionExecutor {
    private final ActionCatalogValidator validator;
    private final RemediationAdapterClient adapterClient;

    public RestartContainerExecutor(
            ActionCatalogValidator validator,
            RemediationAdapterClient adapterClient
    ) {
        this.validator = validator;
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.RESTART_CONTAINER;
    }

    @Override
    public boolean isReversible() {
        return false;
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionContext context) {
        ValidatedActionProposal validated = validator.validateAndNormalize(
                ActionType.RESTART_CONTAINER,
                context.arguments()
        );
        String container = String.valueOf(validated.arguments().get("container"));
        RemediationAdapterClient.AdapterCallResult callResult = adapterClient.restartContainer(
                container,
                context.idempotencyKey(),
                context.timeout()
        );
        return ActionExecutionResult.fromAdapterResult(callResult, Map.of(), false);
    }
}
