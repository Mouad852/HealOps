package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.conversation.ConversationSanitizer;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.observability.ObservabilityBackendClient;
import com.example.Openstack_ai_agent.observability.ObservabilityQueryProperties;
import com.example.Openstack_ai_agent.observability.ObservabilityQueryValidator;
import com.example.Openstack_ai_agent.observability.TraceAnalysisCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JaegerTraceEvidenceCollectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObservabilityQueryValidator validator = new ObservabilityQueryValidator(new ObservabilityQueryProperties());
    private final TraceAnalysisCalculator calculator = new TraceAnalysisCalculator();
    private final ConversationSanitizer sanitizer = new ConversationSanitizer();

    @Test
    void supportsReturnsFalseWhenDisabledAndDoesNotSupportVerification() {
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        when(backendClient.isLiveQueryEnabled()).thenReturn(false);

        JaegerTraceEvidenceCollector collector = new JaegerTraceEvidenceCollector(
                backendClient, validator, calculator, sanitizer, objectMapper
        );
        IncidentResponse incident = sampleIncident("demo-workload", "4bf92f3577b34da6a3ce929d0e0e4736");

        assertThat(collector.supports(incident)).isFalse();
        assertThat(collector.supportsVerification()).isFalse();
    }

    @Test
    void collectsMultiServiceCriticalPathSlowestSpansAndRedactedErrorSpansByTraceId() throws Exception {
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        when(backendClient.isLiveQueryEnabled()).thenReturn(true);

        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        JsonNode otlp = objectMapper.readTree("""
                {
                  "result": {
                    "resourceSpans": [
                      {
                        "resource": { "attributes": [ { "key": "service.name", "value": { "stringValue": "demo-workload" } } ] },
                        "scopeSpans": [ { "spans": [ {
                          "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                          "spanId": "span-root",
                          "name": "GET /work",
                          "startTimeUnixNano": "1700000000000000000",
                          "endTimeUnixNano":   "1700000001250000000",
                          "attributes": [
                            { "key": "http.status_code", "value": { "intValue": "500" } }
                          ],
                          "status": { "code": 2 }
                        } ] } ]
                      },
                      {
                        "resource": { "attributes": [ { "key": "service.name", "value": { "stringValue": "demo-inventory-db" } } ] },
                        "scopeSpans": [ { "spans": [ {
                          "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                          "spanId": "span-db",
                          "parentSpanId": "span-root",
                          "name": "SELECT inventory_items",
                          "startTimeUnixNano": "1700000000100000000",
                          "endTimeUnixNano":   "1700000001100000000",
                          "attributes": [
                            { "key": "db.system", "value": { "stringValue": "postgresql" } },
                            { "key": "error.message", "value": { "stringValue": "timeout token=db-secret-123" } }
                          ],
                          "status": { "code": 2 }
                        } ] } ]
                      }
                    ]
                  }
                }
                """);
        when(backendClient.queryJaegerV3TraceById(traceId)).thenReturn(Optional.of(otlp));

        JaegerTraceEvidenceCollector collector = new JaegerTraceEvidenceCollector(
                backendClient, validator, calculator, sanitizer, objectMapper
        );
        IncidentResponse incident = sampleIncident("demo-workload", traceId);

        assertThat(collector.supports(incident)).isTrue();
        List<EvidenceCandidate> candidates = collector.collect(incident);

        assertThat(candidates).hasSize(1);
        EvidenceCandidate candidate = candidates.getFirst();
        assertThat(candidate.type()).isEqualTo(EvidenceType.TRACE);
        assertThat(candidate.source()).isEqualTo("jaeger");
        assertThat(candidate.target()).isEqualTo("demo-workload");
        assertThat(candidate.traceId()).isEqualTo(traceId);
        assertThat(candidate.metadata())
                .containsEntry("adapter", "jaeger")
                .containsEntry("traceId", traceId)
                .containsEntry("spanCount", "2")
                .containsEntry("errorSpanCount", "2")
                .containsEntry("totalDurationMs", "1250.0");

        JsonNode summary = objectMapper.readTree(candidate.excerpt());
        assertThat(summary.path("rootService").asText()).isEqualTo("demo-workload");
        assertThat(summary.path("totalDurationMs").asDouble()).isEqualTo(1250.0);
        assertThat(summary.path("latency_ms").asDouble()).isEqualTo(1250.0);
        assertThat(summary.path("errorSpanCount").asInt()).isEqualTo(2);
        assertThat(summary.path("services").toString()).contains("demo-workload", "demo-inventory-db");
        assertThat(summary.path("criticalPath").get(0).asText()).contains("demo-workload/GET /work");
        assertThat(summary.path("criticalPath").get(1).asText()).contains("demo-inventory-db/SELECT inventory_items");
        assertThat(summary.path("errorSpans")).hasSize(2);
        assertThat(summary.path("errorSpans").toString())
                .contains("token=[REDACTED]")
                .doesNotContain("db-secret-123");
    }

    @Test
    void fallsBackToServiceTracesSelectingFailingTraceAndReturnsEmptyWhenJaegerHasNone() throws Exception {
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        when(backendClient.isLiveQueryEnabled()).thenReturn(true);

        JsonNode serviceTraces = objectMapper.readTree("""
                {
                  "result": {
                    "resourceSpans": [
                      {
                        "resource": { "attributes": [ { "key": "service.name", "value": { "stringValue": "demo-workload" } } ] },
                        "scopeSpans": [ { "spans": [
                          {
                            "traceId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "spanId": "ok-1",
                            "name": "GET /health",
                            "startTimeUnixNano": "1700000000000000000",
                            "endTimeUnixNano":   "1700000000050000000",
                            "status": { "code": 1 }
                          },
                          {
                            "traceId": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            "spanId": "err-1",
                            "name": "GET /work",
                            "startTimeUnixNano": "1700000001000000000",
                            "endTimeUnixNano":   "1700000002100000000",
                            "status": { "code": 2 }
                          }
                        ] } ]
                      }
                    ]
                  }
                }
                """);
        when(backendClient.queryJaegerV3Traces(eq("demo-workload"), any(), any(), anyInt()))
                .thenReturn(Optional.of(serviceTraces));
        when(backendClient.queryJaegerV3Traces(eq("demo-rollback-api"), any(), any(), anyInt()))
                .thenReturn(Optional.empty());

        JaegerTraceEvidenceCollector collector = new JaegerTraceEvidenceCollector(
                backendClient, validator, calculator, sanitizer, objectMapper
        );

        List<EvidenceCandidate> picked = collector.collect(sampleIncident("demo-workload", null));
        assertThat(picked).hasSize(1);
        assertThat(picked.getFirst().traceId()).isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(picked.getFirst().metadata()).containsEntry("errorSpanCount", "1");

        List<EvidenceCandidate> empty = collector.collect(sampleIncident("demo-rollback-api", null));
        assertThat(empty).isEmpty();
    }

    private IncidentResponse sampleIncident(String service, String traceId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new IncidentResponse(
                UUID.randomUUID(),
                "alertmanager",
                "ext-1",
                "fp-" + service,
                "demo",
                IncidentSeverity.HIGH,
                service,
                IncidentStatus.GATHERING_EVIDENCE,
                now.minusMinutes(2),
                now.minusMinutes(2),
                1L,
                Map.of("runtime", "docker"),
                Map.of(),
                traceId
        );
    }
}
