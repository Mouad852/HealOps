package com.example.Openstack_ai_agent.remediation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionCatalogValidatorTest {

    private final ActionCatalogValidator validator = new ActionCatalogValidator();

    @Test
    void validatesEveryAllowlistedActionAndRendersSafeCommandPreviews() {
        ValidatedActionProposal noAction = validator.validateAndNormalize(
                "NO_ACTION",
                Map.of("reason", "Transient spike resolved")
        );
        assertThat(noAction.actionType()).isEqualTo(ActionType.NO_ACTION);
        assertThat(noAction.riskLevel()).isEqualTo(ActionRiskLevel.LOW);
        assertThat(noAction.rollbackStrategy()).isEqualTo("NONE");
        assertThat(noAction.commandPreview()).contains("No automated action required");

        ValidatedActionProposal restartContainer = validator.validateAndNormalize(
                "RESTART_CONTAINER",
                Map.of("container", "checkout_api-1.prod")
        );
        assertThat(restartContainer.actionType()).isEqualTo(ActionType.RESTART_CONTAINER);
        assertThat(restartContainer.riskLevel()).isEqualTo(ActionRiskLevel.MEDIUM);
        assertThat(restartContainer.commandPreview()).isEqualTo("docker restart checkout_api-1.prod");

        ValidatedActionProposal restartK8s = validator.validateAndNormalize(
                "RESTART_K8S_WORKLOAD",
                Map.of("namespace", "payments", "workload", "checkout-api", "kind", "deployment")
        );
        assertThat(restartK8s.actionType()).isEqualTo(ActionType.RESTART_K8S_WORKLOAD);
        assertThat(restartK8s.arguments().get("kind")).isEqualTo("Deployment");
        assertThat(restartK8s.commandPreview()).isEqualTo("kubectl rollout restart deployment/checkout-api -n payments");

        ValidatedActionProposal scaleK8s = validator.validateAndNormalize(
                "SCALE_K8S_DEPLOYMENT",
                Map.of("namespace", "payments", "deployment", "checkout-api", "replicas", 4)
        );
        assertThat(scaleK8s.actionType()).isEqualTo(ActionType.SCALE_K8S_DEPLOYMENT);
        assertThat(scaleK8s.riskLevel()).isEqualTo(ActionRiskLevel.MEDIUM);
        assertThat(scaleK8s.rollbackStrategy()).isEqualTo("RESTORE_PREVIOUS_REPLICAS");
        assertThat(scaleK8s.commandPreview()).isEqualTo("kubectl scale deployment/checkout-api --replicas=4 -n payments");

        ValidatedActionProposal rollbackK8s = validator.validateAndNormalize(
                "ROLLBACK_K8S_DEPLOYMENT",
                Map.of("namespace", "payments", "deployment", "checkout-api", "revision", 3)
        );
        assertThat(rollbackK8s.actionType()).isEqualTo(ActionType.ROLLBACK_K8S_DEPLOYMENT);
        assertThat(rollbackK8s.riskLevel()).isEqualTo(ActionRiskLevel.HIGH);
        assertThat(rollbackK8s.commandPreview()).isEqualTo("kubectl rollout undo deployment/checkout-api -n payments --to-revision=3");

        ValidatedActionProposal restartOpenstack = validator.validateAndNormalize(
                "RESTART_OPENSTACK_SERVICE",
                Map.of("node", "compute-01.local", "service", "nova-compute")
        );
        assertThat(restartOpenstack.actionType()).isEqualTo(ActionType.RESTART_OPENSTACK_SERVICE);
        assertThat(restartOpenstack.riskLevel()).isEqualTo(ActionRiskLevel.HIGH);
        assertThat(restartOpenstack.commandPreview()).isEqualTo("systemctl restart nova-compute on OpenStack node compute-01.local");
    }

    @Test
    void rejectsUnsupportedActionTypesAndUnexpectedShellArguments() {
        assertThatThrownBy(() -> validator.validateAndNormalize("EXEC_SHELL", Map.of("command", "rm -rf /")))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("Unsupported action type");

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "RESTART_CONTAINER",
                Map.of("container", "web-1", "shell", "sh -c 'whoami'")
        ))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("Unsupported argument 'shell'");

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "RESTART_K8S_WORKLOAD",
                Map.of("namespace", "prod", "workload", "api", "yaml", "apiVersion: v1")
        ))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("Unsupported argument 'yaml'");
    }

    @Test
    void rejectsShellMetacharactersAndInvalidIdentifiers() {
        assertThatThrownBy(() -> validator.validateAndNormalize(
                "RESTART_CONTAINER",
                Map.of("container", "web-1; rm -rf /")
        ))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("Invalid Docker container identifier");

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "RESTART_K8S_WORKLOAD",
                Map.of("namespace", "Prod_Upper", "workload", "checkout-api")
        ))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("Invalid Kubernetes namespace");

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "RESTART_OPENSTACK_SERVICE",
                Map.of("node", "compute-01 && id", "service", "nova-compute")
        ))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("Invalid OpenStack node identifier");
    }

    @Test
    void rejectsOutOfRangeReplicasAndMissingRequiredFields() {
        assertThatThrownBy(() -> validator.validateAndNormalize(
                "SCALE_K8S_DEPLOYMENT",
                Map.of("namespace", "payments", "deployment", "checkout-api", "replicas", 0)
        ))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("out of allowed bounds");

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "SCALE_K8S_DEPLOYMENT",
                Map.of("namespace", "payments", "deployment", "checkout-api", "replicas", 11)
        ))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("out of allowed bounds");

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "SCALE_K8S_DEPLOYMENT",
                Map.of("namespace", "payments", "deployment", "checkout-api", "replicas", 2.5)
        ))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("whole integer");

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "RESTART_OPENSTACK_SERVICE",
                Map.of("node", "compute-01")
        ))
                .isInstanceOf(ActionCatalogValidationException.class)
                .hasMessageContaining("Missing required argument 'service'");
    }
}
