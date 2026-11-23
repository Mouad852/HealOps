package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.conversation.ConversationSanitizer;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotEntity;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotPhase;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObservabilityLiveFallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObservabilityQueryProperties properties = new ObservabilityQueryProperties();
    private final ObservabilityQueryValidator validator = new ObservabilityQueryValidator(properties);

    @Test
    void allowedMetricPromQlMatchesCAdvisorNameAndOtelDemoWorkloadMetrics() {
        String cpu = AllowedMetric.CPU_PERCENT.buildPromQl("demo-workload", "demo");
        String mem = AllowedMetric.MEMORY_PERCENT.buildPromQl("demo-workload", "demo");
        String err = AllowedMetric.ERROR_RATE.buildPromQl("demo-workload", "demo");
        String lat = AllowedMetric.LATENCY_MS.buildPromQl("demo-workload", "demo");
        String rst = AllowedMetric.RESTARTS.buildPromQl("demo-workload", "demo");

        assertThat(cpu).contains("name=~\".*demo-workload.*\"").contains("container_cpu_usage_seconds_total");
        assertThat(mem).contains("name=~\".*demo-workload.*\"").contains("container_memory_working_set_bytes")
                .contains("scalar(max(machine_memory_bytes))");
        assertThat(err).contains("healops_demo_workload_error_ratio").contains("http_server_requests_seconds_count");
        assertThat(lat).contains("healops_demo_workload_latency_ms_milliseconds").contains("http_server_requests_seconds_bucket");
        assertThat(rst).contains("kube_pod_container_status_restarts_total").contains("container_start_time_seconds");
    }

    @Test
    void metricsQueryFallsBackToLocalHealthSnapshotsWhenPrometheusReturnsEmptyResult() throws Exception {
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        IncidentRepository incidentRepo = mock(IncidentRepository.class);
        HealthSnapshotRepository snapshotRepo = mock(HealthSnapshotRepository.class);

        when(backendClient.isLiveQueryEnabled()).thenReturn(true);
        JsonNode emptyProm = objectMapper.readTree("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}");
        when(backendClient.queryPrometheusRange(any(), any(), any(), anyInt())).thenReturn(Optional.of(emptyProm));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        IncidentEntity incident = IncidentEntity.alertReceived(new CreateIncidentRequest(
                "manual-demo", "ext-1", "fp-1", "demo", IncidentSeverity.HIGH,
                "demo-workload", now.minusMinutes(5), Map.of(), Map.of(), "trace-1"
        ));
        when(incidentRepo.findByEnvironmentIdAndService("demo", "demo-workload")).thenReturn(List.of(incident));
        HealthSnapshotEntity snap = HealthSnapshotEntity.of(
                incident.getId(), HealthSnapshotPhase.BEFORE, Map.of("latencyMs", 420.0)
        );
        when(snapshotRepo.findByIncidentIdInAndCapturedAtBetweenOrderByCapturedAtAsc(any(), any(), any()))
                .thenReturn(List.of(snap));

        MetricsQueryService service = new MetricsQueryService(validator, properties, backendClient, incidentRepo, snapshotRepo);
        MetricRangeResponse response = service.queryRange(
                "demo", "demo-workload", "latency_ms", now.minusHours(1), now.plusMinutes(5), 60, demoAuth()
        );

        assertThat(response.dataSource()).isEqualTo("LOCAL_HEALTH_SNAPSHOTS");
        assertThat(response.series()).hasSize(1);
        assertThat(response.series().getFirst().points()).extracting(MetricRangeResponse.MetricPoint::value).containsExactly(420.0);
    }

    @Test
    void logsQueryRetriesServiceOnlySelectorWhenEnvironmentSelectorIsEmptyAndFallsBackToLocalEvidence() throws Exception {
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        IncidentRepository incidentRepo = mock(IncidentRepository.class);
        EvidenceItemRepository evidenceRepo = mock(EvidenceItemRepository.class);
        LogsQueryService service = new LogsQueryService(
                validator, backendClient, incidentRepo, evidenceRepo, new ConversationSanitizer()
        );

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(backendClient.isLiveQueryEnabled()).thenReturn(true);
        JsonNode emptyLoki = objectMapper.readTree("{\"status\":\"success\",\"data\":{\"result\":[]}}");
        JsonNode populatedLoki = objectMapper.readTree("""
                {
                  "status": "success",
                  "data": {
                    "result": [
                      {
                        "stream": { "service_name": "demo-workload", "level": "ERROR" },
                        "values": [ [ "1700000000000000000", "Synthetic failure token=secret999" ] ]
                      }
                    ]
                  }
                }
                """);
        when(backendClient.queryLokiRange(contains("deployment_environment=\"demo\""), any(), any(), anyInt()))
                .thenReturn(Optional.of(emptyLoki));
        when(backendClient.queryLokiRange(eq("{service_name=\"demo-workload\"}"), any(), any(), anyInt()))
                .thenReturn(Optional.of(populatedLoki));

        LogQueryResponse retrySuccess = service.queryLogs(
                "demo", "demo-workload", null, null, now.minusHours(1), now.plusMinutes(1), 20, demoAuth()
        );
        assertThat(retrySuccess.dataSource()).isEqualTo("LOKI");
        assertThat(retrySuccess.records()).hasSize(1);
        assertThat(retrySuccess.records().getFirst().message()).contains("[REDACTED]").doesNotContain("secret999");

        // When both Loki selectors return empty, fall back to LOCAL_EVIDENCE
        when(backendClient.queryLokiRange(eq("{service_name=\"demo-workload\"}"), any(), any(), anyInt()))
                .thenReturn(Optional.of(emptyLoki));
        IncidentEntity incident = IncidentEntity.alertReceived(new CreateIncidentRequest(
                "manual-demo", "ext-2", "fp-2", "demo", IncidentSeverity.HIGH,
                "demo-workload", now.minusMinutes(3), Map.of(), Map.of(), "trace-2"
        ));
        when(incidentRepo.findByEnvironmentIdAndService("demo", "demo-workload")).thenReturn(List.of(incident));
        EvidenceItemEntity evidenceItem = mock(EvidenceItemEntity.class);
        when(evidenceItem.getIncidentId()).thenReturn(incident.getId());
        when(evidenceItem.getObservedAt()).thenReturn(now.minusMinutes(1));
        when(evidenceItem.getTraceId()).thenReturn("trace-2");
        when(evidenceItem.getExcerpt()).thenReturn("ERROR local log fallback");
        when(evidenceItem.getRedactionStatus()).thenReturn(com.example.Openstack_ai_agent.evidence.RedactionStatus.NOT_REQUIRED);
        when(evidenceItem.getType()).thenReturn(EvidenceType.LOG);
        when(evidenceItem.getSource()).thenReturn("docker");
        when(evidenceItem.getTarget()).thenReturn("healops-demo-workload");
        when(evidenceItem.getMetadata()).thenReturn(Map.of());
        when(evidenceRepo.findByIncidentIdInOrderByObservedAtDesc(any())).thenReturn(List.of(evidenceItem));

        LogQueryResponse localFallback = service.queryLogs(
                "demo", "demo-workload", null, null, now.minusHours(1), now.plusMinutes(1), 20, demoAuth()
        );
        assertThat(localFallback.dataSource()).isEqualTo("LOCAL_EVIDENCE");
        assertThat(localFallback.records()).hasSize(1);
        assertThat(localFallback.records().getFirst().message()).contains("ERROR local log fallback");
    }

    @Test
    void backendClientParsesMultiLineJaegerNdjsonAndSkipsUnscopedTraceQueries() {
        ObservabilityQueryProperties liveProps = new ObservabilityQueryProperties();
        liveProps.setLiveQueryEnabled(true);
        ObservabilityBackendClient client = new ObservabilityBackendClient(WebClient.builder(), liveProps, objectMapper);

        assertThat(client.queryJaegerV3Traces(null, OffsetDateTime.now().minusHours(1), OffsetDateTime.now(), 10)).isEmpty();
        assertThat(client.queryJaegerV3Traces("   ", OffsetDateTime.now().minusHours(1), OffsetDateTime.now(), 10)).isEmpty();

        String ndjson = """
                {"result":{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"demo-workload"}}]},"scopeSpans":[{"spans":[{"traceId":"t1","spanId":"s1","name":"GET /work","startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000000100000000","status":{"code":1}}]}]}]}}
                {"result":{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"demo-inventory-db"}}]},"scopeSpans":[{"spans":[{"traceId":"t1","spanId":"s2","parentSpanId":"s1","name":"SELECT inventory_items","startTimeUnixNano":"1700000000020000000","endTimeUnixNano":"1700000000080000000","status":{"code":2}}]}]}]}}
                """;
        Optional<JsonNode> parsed = client.parseJaegerV3Response(ndjson);
        assertThat(parsed).isPresent();
        List<TraceAnalysisCalculator.RawSpan> spans = new TraceAnalysisCalculator().parseJaegerV3OtlpSpans(parsed.get(), 100);
        assertThat(spans).hasSize(2);
        assertThat(spans).extracting(TraceAnalysisCalculator.RawSpan::service)
                .containsExactly("demo-workload", "demo-inventory-db");
    }

    @Test
    void tracesAndTopologyQueryPerDiscoveredServiceAndDeduplicateMultiServiceSpans() throws Exception {
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        TraceAnalysisCalculator calculator = new TraceAnalysisCalculator();
        IncidentRepository incidentRepo = mock(IncidentRepository.class);
        EvidenceItemRepository evidenceRepo = mock(EvidenceItemRepository.class);
        HealthSnapshotRepository snapshotRepo = mock(HealthSnapshotRepository.class);

        when(backendClient.isLiveQueryEnabled()).thenReturn(true);
        when(backendClient.queryJaegerV3Services()).thenReturn(List.of("demo-workload", "demo-inventory-db"));

        JsonNode multiServiceTrace = objectMapper.readTree("""
                {
                  "result": {
                    "resourceSpans": [
                      {
                        "resource": { "attributes": [ { "key": "service.name", "value": { "stringValue": "demo-workload" } } ] },
                        "scopeSpans": [ { "spans": [ {
                          "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                          "spanId": "root-1",
                          "name": "GET /work",
                          "startTimeUnixNano": "1700000000000000000",
                          "endTimeUnixNano":   "1700000000200000000",
                          "status": { "code": 2 }
                        } ] } ]
                      },
                      {
                        "resource": { "attributes": [ { "key": "service.name", "value": { "stringValue": "demo-inventory-db" } } ] },
                        "scopeSpans": [ { "spans": [ {
                          "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                          "spanId": "child-1",
                          "parentSpanId": "root-1",
                          "name": "SELECT inventory_items",
                          "startTimeUnixNano": "1700000000030000000",
                          "endTimeUnixNano":   "1700000000180000000",
                          "status": { "code": 2 }
                        } ] } ]
                      }
                    ]
                  }
                }
                """);
        when(backendClient.queryJaegerV3Traces(eq("demo-workload"), any(), any(), anyInt()))
                .thenReturn(Optional.of(multiServiceTrace));
        when(backendClient.queryJaegerV3Traces(eq("demo-inventory-db"), any(), any(), anyInt()))
                .thenReturn(Optional.of(multiServiceTrace));
        when(incidentRepo.findForTopology(eq("demo"), any(), any(), any())).thenReturn(List.of());

        TracesQueryService tracesService = new TracesQueryService(
                validator, properties, backendClient, calculator, incidentRepo, evidenceRepo, objectMapper
        );
        TopologyQueryService topologyService = new TopologyQueryService(
                validator, tracesService, new TopologyDerivationService(), incidentRepo, snapshotRepo, backendClient
        );

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TraceResponses.TraceListResponse traceList = tracesService.listTraces(
                "demo", null, now.minusHours(1), now.plusMinutes(1), 10, demoAuth()
        );
        assertThat(traceList.dataSource()).isEqualTo("JAEGER_V3");
        assertThat(traceList.traces()).hasSize(1);
        assertThat(traceList.traces().getFirst().spanCount()).isEqualTo(2);

        TopologyResponse topology = topologyService.getTopology("demo", now.minusHours(1), now.plusMinutes(1), demoAuth());
        assertThat(topology.dataSource()).isEqualTo("DERIVED_FROM_JAEGER_V3_AND_INCIDENTS");
        assertThat(topology.edges()).hasSize(1);
        assertThat(topology.edges().getFirst().sourceService()).isEqualTo("demo-workload");
        assertThat(topology.edges().getFirst().targetService()).isEqualTo("demo-inventory-db");
        assertThat(topology.edges().getFirst().callCount()).isEqualTo(1L);

        verify(backendClient, never()).queryJaegerV3Traces(eq(null), any(), any(), anyInt());
    }

    @Test
    void backendClientProperlyEncodesCurlyBracesInLokiAndPrometheusQueries() {
        ObservabilityQueryProperties liveProps = new ObservabilityQueryProperties();
        liveProps.setLiveQueryEnabled(true);
        java.util.List<java.net.URI> capturedUris = new java.util.ArrayList<>();
        org.springframework.web.reactive.function.client.ExchangeFunction exchange = request -> {
            capturedUris.add(request.url());
            return reactor.core.publisher.Mono.just(
                    org.springframework.web.reactive.function.client.ClientResponse
                            .create(org.springframework.http.HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"status\":\"success\",\"data\":{\"result\":[]}}")
                            .build()
            );
        };
        WebClient.Builder webClientBuilder = WebClient.builder().exchangeFunction(exchange);
        ObservabilityBackendClient client = new ObservabilityBackendClient(webClientBuilder, liveProps, objectMapper);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<JsonNode> loki = client.queryLokiRange(
                "{deployment_environment=\"demo\", service_name=\"demo-workload\"} |= \"abc123\"",
                now.minusMinutes(10),
                now,
                20
        );
        Optional<JsonNode> prom = client.queryPrometheusRange(
                "avg(rate(http_server_requests_seconds_count{service=\"demo-workload\"}[5m]))",
                now.minusMinutes(10),
                now,
                60
        );

        assertThat(loki).isPresent();
        assertThat(prom).isPresent();
        assertThat(capturedUris).hasSize(2);
        assertThat(capturedUris.get(0).toString()).contains("/loki/api/v1/query_range").contains("query=");
        assertThat(capturedUris.get(1).toString()).contains("/api/v1/query_range").contains("query=");
    }

    private Authentication demoAuth() {
        return new UsernamePasswordAuthenticationToken(
                "viewer",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_VIEWER"),
                        new SimpleGrantedAuthority("incident:read"),
                        new SimpleGrantedAuthority("env:demo")
                )
        );
    }
}
