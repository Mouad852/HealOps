package com.example.Openstack_ai_agent.remediation;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RestartOpenstackServiceExecutor implements ActionExecutor {
    private final ActionCatalogValidator validator;
    private final RemediationAdapterClient adapterClient;

    public RestartOpenstackServiceExecutor(
            ActionCatalogValidator validator,
            RemediationAdapterClient adapterClient
    ) {
        this.validator = validator;
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.RESTART_OPENSTACK_SERVICE;
    }

    @Override
    public boolean isReversible() {
        return false;
    }

    @Override
    public ActionExecutionResult execute(ActionExecutionContext context) {
        ValidatedActionProposal validated = validator.validateAndNormalize(
                ActionType.RESTART_OPENSTACK_SERVICE,
                context.arguments()
        );
        String node = String.valueOf(validated.arguments().get("node"));
        String service = String.valueOf(validated.arguments().get("service"));

        RemediationAdapterClient.AdapterCallResult callResult = adapterClient.restartOpenstackService(
                node,
                service,
                context.idempotencyKey(),
                context.timeout()
        );
        return ActionExecutionResult.fromAdapterResult(callResult, Map.of(), false);
    }
}
