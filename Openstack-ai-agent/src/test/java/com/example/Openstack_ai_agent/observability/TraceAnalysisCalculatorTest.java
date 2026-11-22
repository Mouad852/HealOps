package com.example.Openstack_ai_agent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceAnalysisCalculatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TraceAnalysisCalculator calculator = new TraceAnalysisCalculator();

    @Test
    void parsesJaegerV3OtlpResourceSpansAndComputesSpanTreeCriticalPathAndSlowestSpans() throws Exception {
        String otlpJson = """
                {
                  "result": {
                    "resourceSpans": [
                      {
                        "resource": {
                          "attributes": [
                            { "key": "service.name", "value": { "stringValue": "api-gateway" } }
                          ]
                        },
                        "scopeSpans": [
                          {
                            "spans": [
                              {
                                "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                                "spanId": "span-root",
                                "name": "POST /checkout",
                                "startTimeUnixNano": "1700000000000000000",
                                "endTimeUnixNano":   "1700000000500000000",
                                "status": { "code": "STATUS_CODE_ERROR" }
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "resource": {
                          "attributes": [
                            { "key": "service.name", "value": { "stringValue": "auth-service" } }
                          ]
                        },
                        "scopeSpans": [
                          {
                            "spans": [
                              {
                                "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                                "spanId": "span-auth",
                                "parentSpanId": "span-root",
                                "name": "verifyToken",
                                "startTimeUnixNano": "1700000000010000000",
                                "endTimeUnixNano":   "1700000000050000000",
                                "status": { "code": "STATUS_CODE_OK" }
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "resource": {
                          "attributes": [
                            { "key": "service.name", "value": { "stringValue": "payments-db" } }
                          ]
                        },
                        "scopeSpans": [
                          {
                            "spans": [
                              {
                                "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                                "spanId": "span-db",
                                "parentSpanId": "span-root",
                                "name": "SELECT ledger",
                                "startTimeUnixNano": "1700000000060000000",
                                "endTimeUnixNano":   "1700000000480000000",
                                "status": { "code": 2 }
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        JsonNode root = objectMapper.readTree(otlpJson);
        List<TraceAnalysisCalculator.RawSpan> spans = calculator.parseJaegerV3OtlpSpans(root, 100);
        assertThat(spans).hasSize(3);

        TraceResponses.TraceDetailResponse detail = calculator.buildDetail(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "local",
                "JAEGER_V3",
                spans
        );

        assertThat(detail.rootService()).isEqualTo("api-gateway");
        assertThat(detail.spanCount()).isEqualTo(3);
        assertThat(detail.errorSpanCount()).isEqualTo(2);
        assertThat(detail.spanTree()).hasSize(1);
        assertThat(detail.spanTree().getFirst().children()).hasSize(2);

        // Critical path should follow root (500ms) -> payments-db (420ms), not auth-service (40ms)
        assertThat(detail.criticalPath())
                .extracting(TraceResponses.TraceSpanSummary::spanId)
                .containsExactly("span-root", "span-db");

        // Slowest spans descending
        assertThat(detail.slowestSpans())
                .extracting(TraceResponses.TraceSpanSummary::spanId)
                .containsExactly("span-root", "span-db", "span-auth");
    }
}
