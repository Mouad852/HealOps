package com.example.Openstack_ai_agent.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class EvidenceAdapterClient {
    private final WebClient dockerClient;
    private final WebClient prometheusClient;
    private final WebClient kubernetesClient;
    private final WebClient openstackClient;
    private final WebClient tracingClient;
    private final Duration timeout;

    public EvidenceAdapterClient(
            WebClient.Builder builder,
            EvidenceAdapterProperties properties,
            @Value("${mcp.server1.url}") String dockerBaseUrl,
            @Value("${mcp.server2.url}") String prometheusBaseUrl,
            @Value("${mcp.server3.url}") String kubernetesBaseUrl,
            @Value("${mcp.server4.url}") String openstackBaseUrl,
            @Value("${mcp.trace.url}") String tracingBaseUrl
    ) {
        this.dockerClient = configured(builder, dockerBaseUrl, properties);
        this.prometheusClient = configured(builder, prometheusBaseUrl, properties);
        this.kubernetesClient = configured(builder, kubernetesBaseUrl, properties);
        this.openstackClient = configured(builder, openstackBaseUrl, properties);
        this.tracingClient = configured(builder, tracingBaseUrl, properties);
        this.timeout = properties.getTimeout();
    }

    public JsonNode dockerContainers() {
        return get(dockerClient, "/docker/list?all=true");
    }

    public JsonNode prometheusContainerMetrics(String service) {
        return get(prometheusClient, "/metrics/container/{service}", service);
    }

    public JsonNode kubernetesPods(String namespace) {
        return get(kubernetesClient, "/k8s/pods?namespace={namespace}", namespace);
    }

    public JsonNode openstackEvents() {
        return get(openstackClient, "/api/v1/openstack/events");
    }

    public JsonNode traceDetails(String traceId) {
        return get(tracingClient, "/trace/{traceId}", traceId);
    }

    private WebClient configured(WebClient.Builder builder, String baseUrl, EvidenceAdapterProperties properties) {
        return builder.clone()
                .baseUrl(baseUrl)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(properties.getMaxResponseBytes()))
                .build();
    }

    private JsonNode get(WebClient client, String uriTemplate, Object... variables) {
        JsonNode response = client.get()
                .uri(uriTemplate, variables)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .block();
        if (response == null) {
            throw new IllegalStateException("Adapter returned an empty response");
        }
        return response;
    }
}
