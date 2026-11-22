package com.example.Openstack_ai_agent.remediation;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Typed outbound adapter contract for allowlisted remediation operations.
 * Intentionally exposes only structured, non-shell operations; arbitrary exec or shell strings are forbidden.
 */
public interface RemediationAdapterClient {
    AdapterCallResult restartContainer(String container, String idempotencyKey, Duration timeout);

    AdapterCallResult restartK8sWorkload(String namespace, String kind, String workload, String idempotencyKey, Duration timeout);

    Optional<Integer> getK8sDeploymentReplicas(String namespace, String deployment, Duration timeout);

    AdapterCallResult scaleK8sDeployment(String namespace, String deployment, int replicas, String idempotencyKey, Duration timeout);

    Optional<Integer> getK8sDeploymentRevision(String namespace, String deployment, Duration timeout);

    AdapterCallResult rollbackK8sDeployment(String namespace, String deployment, Integer revision, String idempotencyKey, Duration timeout);

    AdapterCallResult restartOpenstackService(String node, String service, String idempotencyKey, Duration timeout);

    record AdapterCallResult(
            boolean succeeded,
            boolean timedOut,
            String message,
            Map<String, Object> details
    ) {
        public static AdapterCallResult success(String message, Map<String, Object> details) {
            return new AdapterCallResult(true, false, message, details == null ? Map.of() : Map.copyOf(details));
        }

        public static AdapterCallResult failure(String message, Map<String, Object> details) {
            return new AdapterCallResult(false, false, message, details == null ? Map.of() : Map.copyOf(details));
        }

        public static AdapterCallResult timedOut(String message, Map<String, Object> details) {
            return new AdapterCallResult(false, true, message, details == null ? Map.of() : Map.copyOf(details));
        }
    }
}
