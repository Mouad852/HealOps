package com.example.Openstack_ai_agent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ObservabilityBackendClient {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityBackendClient.class);

    private final WebClient prometheusClient;
    private final WebClient lokiClient;
    private final WebClient jaegerClient;
    private final ObservabilityQueryProperties properties;
    private final ObjectMapper objectMapper;

    public ObservabilityBackendClient(
            WebClient.Builder webClientBuilder,
            ObservabilityQueryProperties properties
    ) {
        this(webClientBuilder, properties, new ObjectMapper());
    }

    @Autowired
    public ObservabilityBackendClient(
            WebClient.Builder webClientBuilder,
            ObservabilityQueryProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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

    public List<String> queryJaegerV3Services() {
        if (!properties.isLiveQueryEnabled()) {
            return List.of();
        }
        try {
            String raw = jaegerClient.get()
                    .uri("/api/v3/services")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(properties.getTimeout())
                    .block();
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(raw);
            JsonNode servicesNode = root.has("services") ? root.path("services") : root.path("data");
            if (!servicesNode.isArray()) {
                return List.of();
            }
            Set<String> services = new LinkedHashSet<>();
            for (JsonNode entry : servicesNode) {
                String name = entry.asText("").trim();
                if (!name.isBlank()) {
                    services.add(name);
                }
            }
            return List.copyOf(services);
        } catch (Exception ex) {
            log.warn("Jaeger v3 services query failed: {}", ex.getMessage());
            return List.of();
        }
    }

    public Optional<JsonNode> queryJaegerV3Traces(
            String service,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit
    ) {
        if (!properties.isLiveQueryEnabled() || service == null || service.isBlank()) {
            return Optional.empty();
        }
        try {
            String rawBody = jaegerClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v3/traces")
                            .queryParam("query.service_name", service)
                            .queryParam("query.start_time_min", from.toInstant().toString())
                            .queryParam("query.start_time_max", to.toInstant().toString())
                            .queryParam("query.search_depth", limit)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(properties.getTimeout())
                    .block();
            return parseJaegerV3Response(rawBody);
        } catch (Exception ex) {
            log.warn("Jaeger v3 trace list query failed for service '{}': {}", service, ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<JsonNode> queryJaegerV3TraceById(String traceId) {
        if (!properties.isLiveQueryEnabled()) {
            return Optional.empty();
        }
        try {
            String rawBody = jaegerClient.get()
                    .uri("/api/v3/traces/{traceId}", traceId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(properties.getTimeout())
                    .block();
            return parseJaegerV3Response(rawBody);
        } catch (Exception ex) {
            log.warn("Jaeger v3 trace detail query failed for {}: {}", traceId, ex.getMessage());
            return Optional.empty();
        }
    }

    Optional<JsonNode> parseJaegerV3Response(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Optional.empty();
        }
        List<JsonNode> parsedNodes = new ArrayList<>();
        try (MappingIterator<JsonNode> iterator = objectMapper.readerFor(JsonNode.class).readValues(rawBody.trim())) {
            while (iterator.hasNext()) {
                JsonNode node = iterator.next();
                if (node != null && !node.isNull() && !node.isMissingNode()) {
                    parsedNodes.add(node);
                }
            }
        } catch (Exception ex) {
            // Fall back to line-by-line NDJSON parsing so a malformed line doesn't discard valid chunks
            for (String line : rawBody.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode chunk = objectMapper.readTree(trimmed);
                    if (chunk != null && !chunk.isNull() && !chunk.isMissingNode()) {
                        parsedNodes.add(chunk);
                    }
                } catch (Exception lineEx) {
                    log.debug("Skipping unparseable Jaeger v3 NDJSON chunk: {}", lineEx.getMessage());
                }
            }
        }
        if (parsedNodes.isEmpty()) {
            return Optional.empty();
        }
        if (parsedNodes.size() == 1) {
            return Optional.of(parsedNodes.getFirst());
        }
        ArrayNode combined = objectMapper.createArrayNode();
        parsedNodes.forEach(combined::add);
        return Optional.of(combined);
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
