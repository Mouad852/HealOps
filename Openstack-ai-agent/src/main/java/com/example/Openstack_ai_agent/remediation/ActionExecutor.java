package com.example.Openstack_ai_agent.remediation;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Typed remediation execution SPI.
 * Implementations must validate arguments through {@link ActionCatalogValidator},
 * never execute arbitrary shell strings or command preview text, enforce bounded timeouts,
 * and capture inverse pre-state whenever {@link #isReversible()} is true.
 */
public interface ActionExecutor {
    boolean supports(ActionType actionType);

    boolean isReversible();

    ActionExecutionResult execute(ActionExecutionContext context);

    default ActionExecutionResult rollback(ActionRollbackContext context) {
        return ActionExecutionResult.failed(
                "Action type " + context.actionType() + " is not reversible",
                Map.of("reversible", false),
                Map.of(),
                false
        );
    }

    record ActionExecutionContext(
            UUID incidentId,
            UUID actionId,
            String environmentId,
            ActionType actionType,
            Map<String, Object> arguments,
            String idempotencyKey,
            String traceId,
            Duration timeout
    ) {
    }

    record ActionRollbackContext(
            UUID incidentId,
            UUID actionId,
            String environmentId,
            ActionType actionType,
            Map<String, Object> originalArguments,
            Map<String, Object> preState,
            String idempotencyKey,
            String traceId,
            Duration timeout
    ) {
    }

    record ActionExecutionResult(
            ToolExecutionStatus status,
            String summary,
            Map<String, Object> sanitizedOutput,
            Map<String, Object> capturedPreState,
            boolean rollbackCapable
    ) {
        public static ActionExecutionResult succeeded(
                String summary,
                Map<String, Object> sanitizedOutput,
                Map<String, Object> capturedPreState,
                boolean rollbackCapable
        ) {
            return new ActionExecutionResult(
                    ToolExecutionStatus.SUCCEEDED,
                    summary,
                    sanitizedOutput == null ? Map.of() : Map.copyOf(sanitizedOutput),
                    capturedPreState == null ? Map.of() : Map.copyOf(capturedPreState),
                    rollbackCapable
            );
        }

        public static ActionExecutionResult failed(
                String summary,
                Map<String, Object> sanitizedOutput,
                Map<String, Object> capturedPreState,
                boolean rollbackCapable
        ) {
            return new ActionExecutionResult(
                    ToolExecutionStatus.FAILED,
                    summary,
                    sanitizedOutput == null ? Map.of() : Map.copyOf(sanitizedOutput),
                    capturedPreState == null ? Map.of() : Map.copyOf(capturedPreState),
                    rollbackCapable
            );
        }

        public static ActionExecutionResult timedOut(
                String summary,
                Map<String, Object> sanitizedOutput,
                Map<String, Object> capturedPreState,
                boolean rollbackCapable
        ) {
            return new ActionExecutionResult(
                    ToolExecutionStatus.TIMED_OUT,
                    summary,
                    sanitizedOutput == null ? Map.of() : Map.copyOf(sanitizedOutput),
                    capturedPreState == null ? Map.of() : Map.copyOf(capturedPreState),
                    rollbackCapable
            );
        }

        public static ActionExecutionResult fromAdapterResult(
                RemediationAdapterClient.AdapterCallResult adapterResult,
                Map<String, Object> capturedPreState,
                boolean rollbackCapable
        ) {
            Map<String, Object> output = adapterResult.details();
            if (adapterResult.succeeded()) {
                return succeeded(adapterResult.message(), output, capturedPreState, rollbackCapable);
            }
            if (adapterResult.timedOut()) {
                return timedOut(adapterResult.message(), output, capturedPreState, rollbackCapable);
            }
            return failed(adapterResult.message(), output, capturedPreState, rollbackCapable);
        }
    }
}
