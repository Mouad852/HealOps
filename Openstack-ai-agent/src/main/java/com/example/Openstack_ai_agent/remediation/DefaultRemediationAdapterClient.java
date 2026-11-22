package com.example.Openstack_ai_agent.remediation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Spring-managed {@link WebClient.Builder} adapter client for typed remediation calls.
 * When {@code healops.remediation.live-adapter-enabled=false}, executes deterministic
 * in-process simulation (tracking Kubernetes deployment replicas/revisions for pre-state
 * and rollback verification) so automated tests do not require live Docker/K8s/OpenStack daemons.
 */
@Component
public class DefaultRemediationAdapterClient implements RemediationAdapterClient {
    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
    private static final int DEFAULT_SIMULATED_REPLICAS = 2;
    private static final int DEFAULT_SIMULATED_REVISION = 5;

    private final WebClient dockerClient;
    private final WebClient kubernetesClient;
    private final WebClient openstackClient;
    private final RemediationProperties properties;

    private final Map<String, Integer> simulatedReplicas = new ConcurrentHashMap<>();
    private final Map<String, Integer> simulatedRevisions = new ConcurrentHashMap<>();

    public DefaultRemediationAdapterClient(
            WebClient.Builder builder,
            RemediationProperties properties,
            @Value("${mcp.server1.url}") String dockerBaseUrl,
            @Value("${mcp.server3.url}") String kubernetesBaseUrl,
            @Value("${mcp.server4.url}") String openstackBaseUrl
    ) {
        this.properties = properties;
        this.dockerClient = configured(builder, dockerBaseUrl, properties);
        this.kubernetesClient = configured(builder, kubernetesBaseUrl, properties);
        this.openstackClient = configured(builder, openstackBaseUrl, properties);
    }

    @Override
    public AdapterCallResult restartContainer(String container, String idempotencyKey, Duration timeout) {
        if (!properties.isLiveAdapterEnabled()) {
            return AdapterCallResult.success(
                    "Simulated Docker container restart for " + container,
                    Map.of("mode", "simulated", "adapter", "docker", "container", container, "idempotencyKey", idempotencyKey)
            );
        }
        return invokePost(
                dockerClient,
                "/docker/{container}/restart",
                null,
                idempotencyKey,
                timeout,
                "docker",
                container
        );
    }

    @Override
    public AdapterCallResult restartK8sWorkload(
            String namespace,
            String kind,
            String workload,
            String idempotencyKey,
            Duration timeout
    ) {
        if (!properties.isLiveAdapterEnabled()) {
            return AdapterCallResult.success(
                    "Simulated Kubernetes rollout restart for " + kind + "/" + workload + " in " + namespace,
                    Map.of(
                            "mode", "simulated",
                            "adapter", "kubernetes",
                            "namespace", namespace,
                            "kind", kind,
                            "workload", workload,
                            "idempotencyKey", idempotencyKey
                    )
            );
        }
        return invokePost(
                kubernetesClient,
                "/k8s/workloads/{namespace}/{kind}/{workload}/restart",
                Map.of("namespace", namespace, "kind", kind, "workload", workload),
                idempotencyKey,
                timeout,
                "kubernetes",
                namespace,
                kind,
                workload
        );
    }

    @Override
    public Optional<Integer> getK8sDeploymentReplicas(String namespace, String deployment, Duration timeout) {
        if (!properties.isLiveAdapterEnabled()) {
            String key = namespace + "/" + deployment;
            return Optional.of(simulatedReplicas.computeIfAbsent(key, ignored -> DEFAULT_SIMULATED_REPLICAS));
        }
        try {
            JsonNode response = kubernetesClient.get()
                    .uri("/k8s/deployments/{namespace}/{deployment}", namespace, deployment)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(effectiveTimeout(timeout))
                    .block();
            if (response == null) {
                return Optional.empty();
            }
            if (response.has("replicas") && response.get("replicas").canConvertToInt()) {
                return Optional.of(response.get("replicas").asInt());
            }
            JsonNode specReplicas = response.path("spec").path("replicas");
            if (specReplicas.canConvertToInt()) {
                return Optional.of(specReplicas.asInt());
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    @Override
    public AdapterCallResult scaleK8sDeployment(
            String namespace,
            String deployment,
            int replicas,
            String idempotencyKey,
            Duration timeout
    ) {
        if (!properties.isLiveAdapterEnabled()) {
            String key = namespace + "/" + deployment;
            int prior = simulatedReplicas.getOrDefault(key, DEFAULT_SIMULATED_REPLICAS);
            simulatedReplicas.put(key, replicas);
            return AdapterCallResult.success(
                    "Simulated Kubernetes scale of " + namespace + "/" + deployment + " to " + replicas + " replicas",
                    Map.of(
                            "mode", "simulated",
                            "adapter", "kubernetes",
                            "namespace", namespace,
                            "deployment", deployment,
                            "previousReplicas", prior,
                            "replicas", replicas,
                            "idempotencyKey", idempotencyKey
                    )
            );
        }
        return invokePost(
                kubernetesClient,
                "/k8s/deployments/{namespace}/{deployment}/scale",
                Map.of("replicas", replicas),
                idempotencyKey,
                timeout,
                "kubernetes",
                namespace,
                deployment
        );
    }

    @Override
    public Optional<Integer> getK8sDeploymentRevision(String namespace, String deployment, Duration timeout) {
        if (!properties.isLiveAdapterEnabled()) {
            String key = namespace + "/" + deployment;
            return Optional.of(simulatedRevisions.computeIfAbsent(key, ignored -> DEFAULT_SIMULATED_REVISION));
        }
        try {
            JsonNode response = kubernetesClient.get()
                    .uri("/k8s/deployments/{namespace}/{deployment}", namespace, deployment)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(effectiveTimeout(timeout))
                    .block();
            if (response == null) {
                return Optional.empty();
            }
            if (response.has("revision") && response.get("revision").canConvertToInt()) {
                return Optional.of(response.get("revision").asInt());
            }
            if (response.has("currentRevision") && response.get("currentRevision").canConvertToInt()) {
                return Optional.of(response.get("currentRevision").asInt());
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    @Override
    public AdapterCallResult rollbackK8sDeployment(
            String namespace,
            String deployment,
            Integer revision,
            String idempotencyKey,
            Duration timeout
    ) {
        if (!properties.isLiveAdapterEnabled()) {
            String key = namespace + "/" + deployment;
            int prior = simulatedRevisions.getOrDefault(key, DEFAULT_SIMULATED_REVISION);
            int target = revision != null ? revision : Math.max(1, prior - 1);
            simulatedRevisions.put(key, target);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("mode", "simulated");
            details.put("adapter", "kubernetes");
            details.put("namespace", namespace);
            details.put("deployment", deployment);
            details.put("previousRevision", prior);
            details.put("targetRevision", target);
            details.put("idempotencyKey", idempotencyKey);
            return AdapterCallResult.success(
                    "Simulated Kubernetes rollback of " + namespace + "/" + deployment + " to revision " + target,
                    details
            );
        }
        Map<String, Object> body = revision != null ? Map.of("revision", revision) : Map.of();
        return invokePost(
                kubernetesClient,
                "/k8s/deployments/{namespace}/{deployment}/rollback",
                body,
                idempotencyKey,
                timeout,
                "kubernetes",
                namespace,
                deployment
        );
    }

    @Override
    public AdapterCallResult restartOpenstackService(
            String node,
            String service,
            String idempotencyKey,
            Duration timeout
    ) {
        if (!properties.isLiveAdapterEnabled()) {
            return AdapterCallResult.success(
                    "Simulated OpenStack service restart for " + service + " on node " + node,
                    Map.of(
                            "mode", "simulated",
                            "adapter", "openstack",
                            "node", node,
                            "service", service,
                            "idempotencyKey", idempotencyKey
                    )
            );
        }
        return invokePost(
                openstackClient,
                "/api/v1/openstack/action/restart",
                Map.of("node", node, "service", service),
                idempotencyKey,
                timeout,
                "openstack"
        );
    }

    private AdapterCallResult invokePost(
            WebClient client,
            String uriTemplate,
            Object body,
            String idempotencyKey,
            Duration timeout,
            String adapterName,
            Object... uriVariables
    ) {
        try {
            WebClient.RequestBodySpec spec = client.post()
                    .uri(uriTemplate, uriVariables)
                    .header(IDEMPOTENCY_HEADER, idempotencyKey);
            WebClient.RequestHeadersSpec<?> request = body != null
                    ? spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                    : spec;

            JsonNode response = request.retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(effectiveTimeout(timeout))
                    .block();

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("mode", "live");
            details.put("adapter", adapterName);
            details.put("idempotencyKey", idempotencyKey);
            if (response != null) {
                String serialized = response.toString();
                details.put("response", serialized.length() <= 2048 ? serialized : serialized.substring(0, 2048));
            }
            return AdapterCallResult.success(adapterName + " adapter action succeeded", details);
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                return AdapterCallResult.timedOut(
                        adapterName + " adapter action timed out after " + effectiveTimeout(timeout),
                        Map.of(
                                "mode", "live",
                                "adapter", adapterName,
                                "idempotencyKey", idempotencyKey,
                                "errorType", exception.getClass().getSimpleName()
                        )
                );
            }
            return AdapterCallResult.failure(
                    adapterName + " adapter action failed: " + exception.getClass().getSimpleName(),
                    Map.of(
                            "mode", "live",
                            "adapter", adapterName,
                            "idempotencyKey", idempotencyKey,
                            "errorType", exception.getClass().getSimpleName()
                    )
            );
        }
    }

    private WebClient configured(WebClient.Builder builder, String baseUrl, RemediationProperties props) {
        return builder.clone()
                .baseUrl(baseUrl)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(props.getMaxResponseBytes()))
                .build();
    }

    private Duration effectiveTimeout(Duration timeout) {
        return timeout != null && !timeout.isNegative() && !timeout.isZero()
                ? timeout
                : properties.getExecutionTimeout();
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof TimeoutException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
