package com.example.Openstack_ai_agent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.OffsetDateTime;
import java.util.Optional;

@Component
public class ObservabilityBackendClient {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityBackendClient.class);

    private final WebClient prometheusClient;
    private final WebClient lokiClient;
    private final WebClient jaegerClient;
    private final ObservabilityQueryProperties properties;

    public ObservabilityBackendClient(
            WebClient.Builder webClientBuilder,
            ObservabilityQueryProperties properties
    ) {
        this.properties = properties;
        this.prometheusClient = configured(webClientBuilder, properties.getPrometheusBaseUrl(), properties);
        this.lokiClient = configured(webClientBuilder, properties.getLokiBaseUrl(), properties);
        this.jaegerClient = configured(webClientBuilder, properties.getJaegerBaseUrl(), properties);
    }

    public boolean isLiveQueryEnabled() {
        return properties.isLiveQueryEnabled();
    }

    public Optional<JsonNode> queryPrometheusRange(
            String allowlistedPromQl,
            OffsetDateTime from,
            OffsetDateTime to,
            int stepSeconds
    ) {
        if (!properties.isLiveQueryEnabled()) {
            return Optional.empty();
        }
        try {
            JsonNode response = prometheusClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/query_range")
                            .queryParam("query", allowlistedPromQl)
                            .queryParam("start", from.toEpochSecond())
                            .queryParam("end", to.toEpochSecond())
                            .queryParam("step", stepSeconds)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(properties.getTimeout())
                    .block();
            return Optional.ofNullable(response);
        } catch (Exception ex) {
            log.warn("Prometheus range query failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<JsonNode> queryLokiRange(
            String constructedSelector,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit
    ) {
        if (!properties.isLiveQueryEnabled()) {
            return Optional.empty();
        }
        long startNs = from.toInstant().getEpochSecond() * 1_000_000_000L + from.toInstant().getNano();
        long endNs = to.toInstant().getEpochSecond() * 1_000_000_000L + to.toInstant().getNano();
        try {
            JsonNode response = lokiClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/loki/api/v1/query_range")
                            .queryParam("query", constructedSelector)
                            .queryParam("start", startNs)
                            .queryParam("end", endNs)
                            .queryParam("limit", limit)
                            .queryParam("direction", "backward")
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(properties.getTimeout())
                    .block();
            return Optional.ofNullable(response);
        } catch (Exception ex) {
            log.warn("Loki range query failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<JsonNode> queryJaegerV3Traces(
            String service,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit
    ) {
        if (!properties.isLiveQueryEnabled()) {
            return Optional.empty();
        }
        try {
            JsonNode response = jaegerClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/api/v3/traces")
                                .queryParam("query.start_time_min", from.toInstant().toString())
                                .queryParam("query.start_time_max", to.toInstant().toString())
                                .queryParam("query.search_depth", limit);
                        if (service != null && !service.isBlank()) {
                            builder = builder.queryParam("query.service_name", service);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(properties.getTimeout())
                    .block();
            return Optional.ofNullable(response);
        } catch (Exception ex) {
            log.warn("Jaeger v3 trace list query failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<JsonNode> queryJaegerV3TraceById(String traceId) {
        if (!properties.isLiveQueryEnabled()) {
            return Optional.empty();
        }
        try {
            JsonNode response = jaegerClient.get()
                    .uri("/api/v3/traces/{traceId}", traceId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(properties.getTimeout())
                    .block();
            return Optional.ofNullable(response);
        } catch (Exception ex) {
            log.warn("Jaeger v3 trace detail query failed for {}: {}", traceId, ex.getMessage());
            return Optional.empty();
        }
    }

    private WebClient configured(
            WebClient.Builder builder,
            String baseUrl,
            ObservabilityQueryProperties properties
    ) {
        return builder.clone()
                .baseUrl(baseUrl)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(properties.getMaxResponseBytes()))
                .build();
    }
}
