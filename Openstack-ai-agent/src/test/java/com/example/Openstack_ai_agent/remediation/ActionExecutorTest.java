package com.example.Openstack_ai_agent.remediation;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionExecutorTest {

    private final ActionCatalogValidator validator = new ActionCatalogValidator();

    @Test
    void scaleK8sDeploymentCapturesPreStateAndRestoresPreviousReplicasOnRollback() {
        RemediationAdapterClient adapter = Mockito.mock(RemediationAdapterClient.class);
        ScaleK8sDeploymentExecutor executor = new ScaleK8sDeploymentExecutor(validator, adapter);

        UUID incidentId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        String execKey = "exec:" + actionId + ":attempt:1";
        String rollbackKey = "rollback:" + actionId + ":attempt:1";

        when(adapter.getK8sDeploymentReplicas(eq("search"), eq("search-api"), any()))
                .thenReturn(Optional.of(2));
        when(adapter.scaleK8sDeployment(eq("search"), eq("search-api"), eq(4), eq(execKey), any()))
                .thenReturn(RemediationAdapterClient.AdapterCallResult.success("scaled to 4", Map.of("replicas", 4)));
        when(adapter.scaleK8sDeployment(eq("search"), eq("search-api"), eq(2), eq(rollbackKey), any()))
                .thenReturn(RemediationAdapterClient.AdapterCallResult.success("restored to 2", Map.of("replicas", 2)));

        assertThat(executor.supports(ActionType.SCALE_K8S_DEPLOYMENT)).isTrue();
        assertThat(executor.isReversible()).isTrue();

        ActionExecutor.ActionExecutionResult execResult = executor.execute(
                new ActionExecutor.ActionExecutionContext(
                        incidentId,
                        actionId,
                        "local",
                        ActionType.SCALE_K8S_DEPLOYMENT,
                        Map.of("namespace", "search", "deployment", "search-api", "replicas", 4),
                        execKey,
                        "trace-1",
                        Duration.ofSeconds(5)
                )
        );

        assertThat(execResult.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(execResult.rollbackCapable()).isTrue();
        assertThat(execResult.capturedPreState())
                .containsEntry("previousReplicas", 2)
                .containsEntry("namespace", "search")
                .containsEntry("deployment", "search-api");

        ActionExecutor.ActionExecutionResult rollbackResult = executor.rollback(
                new ActionExecutor.ActionRollbackContext(
                        incidentId,
                        actionId,
                        "local",
                        ActionType.SCALE_K8S_DEPLOYMENT,
                        Map.of("namespace", "search", "deployment", "search-api", "replicas", 4),
                        execResult.capturedPreState(),
                        rollbackKey,
                        "trace-1",
                        Duration.ofSeconds(5)
                )
        );

        assertThat(rollbackResult.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        verify(adapter).scaleK8sDeployment(eq("search"), eq("search-api"), eq(2), eq(rollbackKey), any());
    }

    @Test
    void rollbackK8sDeploymentIsReversibleOnlyWhenPriorRevisionIsCaptured() {
        RemediationAdapterClient adapter = Mockito.mock(RemediationAdapterClient.class);
        RollbackK8sDeploymentExecutor executor = new RollbackK8sDeploymentExecutor(validator, adapter);

        UUID actionId = UUID.randomUUID();
        when(adapter.getK8sDeploymentRevision(eq("web"), eq("frontend"), any()))
                .thenReturn(Optional.of(7));
        when(adapter.rollbackK8sDeployment(eq("web"), eq("frontend"), eq(6), any(), any()))
                .thenReturn(RemediationAdapterClient.AdapterCallResult.success("rolled back to 6", Map.of("revision", 6)));
        when(adapter.rollbackK8sDeployment(eq("web"), eq("frontend"), eq(7), any(), any()))
                .thenReturn(RemediationAdapterClient.AdapterCallResult.success("restored to 7", Map.of("revision", 7)));

        var result = executor.execute(new ActionExecutor.ActionExecutionContext(
                UUID.randomUUID(),
                actionId,
                "local",
                ActionType.ROLLBACK_K8S_DEPLOYMENT,
                Map.of("namespace", "web", "deployment", "frontend", "revision", 6),
                "exec:" + actionId + ":attempt:1",
                "trace-2",
                Duration.ofSeconds(5)
        ));

        assertThat(result.rollbackCapable()).isTrue();
        assertThat(result.capturedPreState()).containsEntry("previousRevision", 7);

        var undo = executor.rollback(new ActionExecutor.ActionRollbackContext(
                UUID.randomUUID(),
                actionId,
                "local",
                ActionType.ROLLBACK_K8S_DEPLOYMENT,
                Map.of("namespace", "web", "deployment", "frontend", "revision", 6),
                result.capturedPreState(),
                "rollback:" + actionId + ":attempt:1",
                "trace-2",
                Duration.ofSeconds(5)
        ));
        assertThat(undo.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);

        // When prior revision is missing, rollbackCapable must be false
        when(adapter.getK8sDeploymentRevision(eq("web"), eq("frontend"), any())).thenReturn(Optional.empty());
        var noPreState = executor.execute(new ActionExecutor.ActionExecutionContext(
                UUID.randomUUID(),
                actionId,
                "local",
                ActionType.ROLLBACK_K8S_DEPLOYMENT,
                Map.of("namespace", "web", "deployment", "frontend", "revision", 6),
                "exec:" + actionId + ":attempt:2",
                "trace-2",
                Duration.ofSeconds(5)
        ));
        assertThat(noPreState.rollbackCapable()).isFalse();
        assertThat(noPreState.capturedPreState()).isEmpty();
    }

    @Test
    void nonReversibleExecutorsRejectShellInjectionAndMapTimeouts() {
        RemediationAdapterClient adapter = Mockito.mock(RemediationAdapterClient.class);
        RestartContainerExecutor containerExecutor = new RestartContainerExecutor(validator, adapter);
        RestartK8sWorkloadExecutor k8sExecutor = new RestartK8sWorkloadExecutor(validator, adapter);
        RestartOpenstackServiceExecutor openstackExecutor = new RestartOpenstackServiceExecutor(validator, adapter);

        assertThat(containerExecutor.isReversible()).isFalse();
        assertThat(k8sExecutor.isReversible()).isFalse();
        assertThat(openstackExecutor.isReversible()).isFalse();

        // Reject shell injection in parameters
        assertThatThrownBy(() -> containerExecutor.execute(new ActionExecutor.ActionExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "local",
                ActionType.RESTART_CONTAINER,
                Map.of("container", "web; rm -rf /"),
                "exec:1:attempt:1",
                "trace",
                Duration.ofSeconds(3)
        ))).isInstanceOf(ActionCatalogValidationException.class);

        // Timeout mapping on OpenStack restart
        when(adapter.restartOpenstackService(eq("compute-01"), eq("nova-compute"), eq("exec:os:attempt:1"), any()))
                .thenReturn(RemediationAdapterClient.AdapterCallResult.timedOut("timed out", Map.of("adapter", "openstack")));

        var timeoutResult = openstackExecutor.execute(new ActionExecutor.ActionExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "local",
                ActionType.RESTART_OPENSTACK_SERVICE,
                Map.of("node", "compute-01", "service", "nova-compute"),
                "exec:os:attempt:1",
                "trace",
                Duration.ofSeconds(3)
        ));
        assertThat(timeoutResult.status()).isEqualTo(ToolExecutionStatus.TIMED_OUT);
        assertThat(timeoutResult.rollbackCapable()).isFalse();

        // Non-reversible rollback returns FAILED
        var rollbackAttempt = k8sExecutor.rollback(new ActionExecutor.ActionRollbackContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "local",
                ActionType.RESTART_K8S_WORKLOAD,
                Map.of("namespace", "payments", "workload", "checkout-api", "kind", "Deployment"),
                Map.of(),
                "rollback:k8s:attempt:1",
                "trace",
                Duration.ofSeconds(3)
        ));
        assertThat(rollbackAttempt.status()).isEqualTo(ToolExecutionStatus.FAILED);
    }
}
