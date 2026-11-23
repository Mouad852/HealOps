package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.conversation.ConversationSanitizer;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.observability.ObservabilityBackendClient;
import com.example.Openstack_ai_agent.observability.ObservabilityQueryValidator;
import com.example.Openstack_ai_agent.observability.TraceAnalysisCalculator;
import com.example.Openstack_ai_agent.observability.TraceResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Collects bounded Jaeger v3 distributed trace summaries (critical path, slowest spans,
 * and error spans) during {@code GATHERING_EVIDENCE}. Disabled during {@code VERIFYING}
 * and {@code ROLLING_BACK} so pre-remediation trace errors never corrupt post-fix checks.
 */
@Component
@Order(45)
public class JaegerTraceEvidenceCollector implements EvidenceCollector {
    private static final Logger log = LoggerFactory.getLogger(JaegerTraceEvidenceCollector.class);
    private static final Pattern HEX_TRACE_ID = Pattern.compile("^[0-9a-fA-F]{16,32}$");
    private static final int MAX_SPANS_TO_PARSE = 200;
    private static final int SERVICE_TRACE_LIMIT = 10;
    private static final int MAX_ERROR_SPANS = 5;
    private static final int MAX_EXCERPT_CHARS = 7000;

    private final ObservabilityBackendClient observabilityBackendClient;
    private final ObservabilityQueryValidator validator;
    private final TraceAnalysisCalculator analysisCalculator;
    private final ConversationSanitizer sanitizer;
    private final ObjectMapper objectMapper;

    @Autowired
    public JaegerTraceEvidenceCollector(
            ObservabilityBackendClient observabilityBackendClient,
            ObservabilityQueryValidator validator,
            TraceAnalysisCalculator analysisCalculator,
            ConversationSanitizer sanitizer,
            ObjectMapper objectMapper
    ) {
        this.observabilityBackendClient = observabilityBackendClient;
        this.validator = validator;
        this.analysisCalculator = analysisCalculator;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(IncidentResponse incident) {
        if (!observabilityBackendClient.isLiveQueryEnabled()) {
            return false;
        }
        boolean hasValidTraceId = isValidHexTraceId(incident.traceId());
        boolean hasService = incident.service() != null && !incident.service().isBlank();
        return hasValidTraceId || hasService;
    }

    @Override
    public boolean supportsVerification() {
        return false;
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime from = incident.startedAt() != null
                ? incident.startedAt().minusMinutes(10)
                : now.minusMinutes(15);
        OffsetDateTime to = now.plusMinutes(1);
        if (from.isBefore(to.minusHours(23))) {
            from = to.minusHours(23);
        }

        String selectedTraceId = null;
        List<TraceAnalysisCalculator.RawSpan> selectedSpans = List.of();

        if (isValidHexTraceId(incident.traceId())) {
            String candidateTraceId = incident.traceId().trim();
            Optional<JsonNode> byIdJson = observabilityBackendClient.queryJaegerV3TraceById(candidateTraceId);
            if (byIdJson.isPresent()) {
                List<TraceAnalysisCalculator.RawSpan> parsed =
                        analysisCalculator.parseJaegerV3OtlpSpans(byIdJson.get(), MAX_SPANS_TO_PARSE);
                if (!parsed.isEmpty()) {
                    selectedTraceId = candidateTraceId;
                    selectedSpans = parsed;
                }
            }
        }

        String validatedService = validateServiceSafe(incident.service());
        if (selectedSpans.isEmpty() && validatedService != null) {
            Optional<JsonNode> byServiceJson = observabilityBackendClient.queryJaegerV3Traces(
                    validatedService,
                    from,
                    to,
                    SERVICE_TRACE_LIMIT
            );
            if (byServiceJson.isPresent()) {
                List<TraceAnalysisCalculator.RawSpan> allSpans =
                        analysisCalculator.parseJaegerV3OtlpSpans(byServiceJson.get(), MAX_SPANS_TO_PARSE);
                List<TraceResponses.TraceSummaryDto> summaries = analysisCalculator.summarizeByTrace(allSpans);
                if (!summaries.isEmpty()) {
                    TraceResponses.TraceSummaryDto best = summaries.stream()
                            .min(Comparator
                                    .comparingInt((TraceResponses.TraceSummaryDto s) -> s.errorSpanCount() > 0 ? 0 : 1)
                                    .thenComparing(TraceResponses.TraceSummaryDto::errorSpanCount, Comparator.reverseOrder())
                                    .thenComparing(TraceResponses.TraceSummaryDto::durationMs, Comparator.reverseOrder())
                                    .thenComparing(TraceResponses.TraceSummaryDto::startedAt, Comparator.reverseOrder()))
                            .orElse(summaries.getFirst());
                    selectedTraceId = best.traceId();
                    String chosenId = selectedTraceId;
                    selectedSpans = allSpans.stream()
                            .filter(s -> chosenId.equals(s.traceId()))
                            .toList();
                }
            }
        }

        if (selectedSpans.isEmpty() || selectedTraceId == null) {
            return List.of();
        }

        TraceResponses.TraceDetailResponse detail = analysisCalculator.buildDetail(
                selectedTraceId,
                incident.environmentId(),
                "JAEGER_V3",
                selectedSpans
        );
        List<String> services = selectedSpans.stream()
                .map(TraceAnalysisCalculator.RawSpan::service)
                .distinct()
                .sorted()
                .toList();
        List<String> criticalPath = TraceAnalysisCalculator.formatCriticalPath(detail.criticalPath());
        List<TraceAnalysisCalculator.ErrorSpanDetail> errorSpans =
                analysisCalculator.extractErrorSpans(selectedSpans, MAX_ERROR_SPANS);

        String excerpt = buildBoundedTraceSummaryJson(detail, services, criticalPath, errorSpans);
        if (excerpt == null || excerpt.isBlank()) {
            return List.of();
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("adapter", "jaeger");
        metadata.put("traceId", selectedTraceId);
        metadata.put("spanCount", String.valueOf(detail.spanCount()));
        metadata.put("errorSpanCount", String.valueOf(detail.errorSpanCount()));
        metadata.put("totalDurationMs", String.valueOf(detail.totalDurationMs()));

        String target = validatedService != null ? validatedService : detail.rootService();
        return List.of(new EvidenceCandidate(
                EvidenceType.TRACE,
                "jaeger",
                target,
                now,
                from,
                to,
                selectedTraceId,
                excerpt,
                metadata
        ));
    }

    private String buildBoundedTraceSummaryJson(
            TraceResponses.TraceDetailResponse detail,
            List<String> services,
            List<String> criticalPath,
            List<TraceAnalysisCalculator.ErrorSpanDetail> errorSpans
    ) {
        try {
            ObjectNode root = serializeTraceNode(detail, services, criticalPath, errorSpans, true);
            String json = objectMapper.writeValueAsString(root);
            if (json.length() <= MAX_EXCERPT_CHARS) {
                return json;
            }
            // Retry without span attributes if excerpt exceeds bounded size
            ObjectNode compact = serializeTraceNode(detail, services, criticalPath, errorSpans, false);
            String compactJson = objectMapper.writeValueAsString(compact);
            return compactJson.length() <= MAX_EXCERPT_CHARS
                    ? compactJson
                    : compactJson.substring(0, MAX_EXCERPT_CHARS);
        } catch (Exception ex) {
            log.warn("Failed to serialize Jaeger trace summary for trace {}: {}", detail.traceId(), ex.getMessage());
            return null;
        }
    }

    private ObjectNode serializeTraceNode(
            TraceResponses.TraceDetailResponse detail,
            List<String> services,
            List<String> criticalPath,
            List<TraceAnalysisCalculator.ErrorSpanDetail> errorSpans,
            boolean includeErrorAttributes
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("traceId", detail.traceId());
        root.put("rootService", detail.rootService());
        root.put("rootOperation", detail.rootOperation());
        root.put("totalDurationMs", detail.totalDurationMs());
        root.put("latency_ms", detail.totalDurationMs());
        root.put("spanCount", detail.spanCount());
        root.put("errorSpanCount", detail.errorSpanCount());

        ArrayNode servicesNode = objectMapper.createArrayNode();
        services.forEach(servicesNode::add);
        root.set("services", servicesNode);

        ArrayNode criticalNode = objectMapper.createArrayNode();
        criticalPath.forEach(criticalNode::add);
        root.set("criticalPath", criticalNode);

        ArrayNode slowestNode = objectMapper.createArrayNode();
        for (TraceResponses.TraceSpanSummary span : detail.slowestSpans()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("service", span.service());
            item.put("operation", span.operation());
            item.put("durationMs", span.durationMs());
            item.put("status", span.status());
            slowestNode.add(item);
        }
        root.set("slowestSpans", slowestNode);

        ArrayNode errorsNode = objectMapper.createArrayNode();
        for (TraceAnalysisCalculator.ErrorSpanDetail err : errorSpans) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("service", err.service());
            item.put("operation", err.operation());
            item.put("durationMs", err.durationMs());
            item.put("status", err.status());
            if (includeErrorAttributes && !err.attributes().isEmpty()) {
                ObjectNode attrsNode = objectMapper.createObjectNode();
                err.attributes().forEach((k, v) -> attrsNode.put(k, sanitizer.sanitizeContent(v)));
                item.set("attributes", attrsNode);
            }
            errorsNode.add(item);
        }
        root.set("errorSpans", errorsNode);
        return root;
    }

    private boolean isValidHexTraceId(String raw) {
        return raw != null && HEX_TRACE_ID.matcher(raw.trim()).matches();
    }

    private String validateServiceSafe(String rawService) {
        if (rawService == null || rawService.isBlank()) {
            return null;
        }
        try {
            return validator.validateOptionalService(rawService);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
