package com.example.Openstack_ai_agent.McpClient;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class McpClientTrace {

        private final WebClient webClient;

        public McpClientTrace(@Value("${mcp.trace.url}") String baseUrl) {
                this.webClient = WebClient.builder()
                                .baseUrl(baseUrl)
                                .build();
        }
        // -------------------- APM MCP (Jaeger) --------------------

        public Mono<String> listServices() {
                return webClient.get()
                                .uri("/services")
                                .retrieve()
                                .bodyToMono(String.class);
        }

        public Mono<String> getTraces(String service, int limit) {
                return webClient.get()
                                .uri(uriBuilder -> uriBuilder.path("/traces")
                                                .queryParam("service", service)
                                                .queryParam("limit", limit)
                                                .build())
                                .retrieve()
                                .bodyToMono(String.class);
        }

        public Mono<String> getTraceDetails(String traceId) {
                return webClient.get()
                                .uri("/trace/{traceId}", traceId)
                                .retrieve()
                                .bodyToMono(String.class);
        }

        public Mono<String> analyzeTrace(String traceId) {
                return webClient.get()
                                .uri("/trace/{traceId}/analyze", traceId)
                                .retrieve()
                                .bodyToMono(String.class);
        }

        public Mono<String> getServiceHealth(String service) {
                return webClient.get()
                                .uri("/service/{service}/health", service)
                                .retrieve()
                                .bodyToMono(String.class);
        }
}
