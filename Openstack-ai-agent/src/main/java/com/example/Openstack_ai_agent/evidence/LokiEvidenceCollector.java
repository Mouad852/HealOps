package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.conversation.ConversationSanitizer;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.observability.LogQueryResponse;
import com.example.Openstack_ai_agent.observability.LokiLogSupport;
import com.example.Openstack_ai_agent.observability.ObservabilityBackendClient;
import com.example.Openstack_ai_agent.observability.ObservabilityQueryValidator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects bounded, secret-redacted Loki application logs during {@code GATHERING_EVIDENCE}.
 * Disabled during {@code VERIFYING} and {@code ROLLING_BACK} so historical alert-window errors
 * never pollute post-remediation health verification.
 */
@Component
@Order(25)
public class LokiEvidenceCollector implements EvidenceCollector {
    private static final Logger log = LoggerFactory.getLogger(LokiEvidenceCollector.class);
    private static final int QUERY_LIMIT = 40;
    private static final int MAX_INCLUDED_LINES = 25;
    private static final int MAX_LINE_MESSAGE_CHARS = 500;
    private static final int MAX_EXCERPT_CHARS = 6500;

    private final ObservabilityBackendClient observabilityBackendClient;
    private final ObservabilityQueryValidator validator;
    private final ConversationSanitizer sanitizer;
    private final ObjectMapper objectMapper;

    @Autowired
    public LokiEvidenceCollector(
            ObservabilityBackendClient observabilityBackendClient,
            ObservabilityQueryValidator validator,
            ConversationSanitizer sanitizer,
            ObjectMapper objectMapper
    ) {
        this.observabilityBackendClient = observabilityBackendClient;
        this.validator = validator;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(IncidentResponse incident) {
        return observabilityBackendClient.isLiveQueryEnabled()
                && incident.service() != null
                && !incident.service().isBlank();
    }

    @Override
    public boolean supportsVerification() {
        return false;
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        String service = validateServiceSafe(incident.service());
        if (service == null) {
            return List.of();
        }
        String traceId = validateTraceIdSafe(incident.traceId());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime from = incident.startedAt() != null
                ? incident.startedAt().minusMinutes(10)
                : now.minusMinutes(15);
        OffsetDateTime to = now.plusMinutes(1);
        if (from.isBefore(to.minusHours(23))) {
            from = to.minusHours(23);
        }

        List<LogQueryResponse.LogRecordDto> records = LokiLogSupport.queryLokiIncidentOrServiceLogs(
                observabilityBackendClient,
                incident.environmentId(),
                service,
                traceId,
                from,
                to,
                QUERY_LIMIT,
                sanitizer
        );
        if (records.isEmpty()) {
            return List.of();
        }

        List<LogQueryResponse.LogRecordDto> prioritized = LokiLogSupport.prioritizeBySeverity(records, MAX_INCLUDED_LINES);
        String excerpt = buildBoundedExcerptJson(service, prioritized);
        if (excerpt == null || excerpt.isBlank()) {
            return List.of();
        }

        int includedCount = 0;
        int errorCount = 0;
        String matchedTraceId = traceId;
        for (LogQueryResponse.LogRecordDto rec : prioritized) {
            includedCount++;
            if (isErrorLevel(rec.level())) {
                errorCount++;
            }
            if ((matchedTraceId == null || matchedTraceId.isBlank()) && rec.traceId() != null && !rec.traceId().isBlank()) {
                matchedTraceId = rec.traceId();
            }
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("adapter", "loki");
        metadata.put("logCount", String.valueOf(includedCount));
        metadata.put("errorCount", String.valueOf(errorCount));
        if (matchedTraceId != null && !matchedTraceId.isBlank()) {
            metadata.put("traceId", matchedTraceId);
        }

        return List.of(new EvidenceCandidate(
                EvidenceType.LOG,
                "loki",
                service,
                now,
                from,
                to,
                incident.traceId(),
                excerpt,
                metadata
        ));
    }

    private String buildBoundedExcerptJson(String service, List<LogQueryResponse.LogRecordDto> prioritized) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("adapter", "loki");
            root.put("service", service);
            ArrayNode lines = objectMapper.createArrayNode();
            int includedCount = 0;
            int errorCount = 0;

            for (LogQueryResponse.LogRecordDto rec : prioritized) {
                ObjectNode lineNode = objectMapper.createObjectNode();
                if (rec.timestamp() != null) {
                    lineNode.put("ts", rec.timestamp().toString());
                }
                String level = rec.level() == null || rec.level().isBlank() ? "INFO" : rec.level();
                lineNode.put("level", level);
                if (rec.traceId() != null && !rec.traceId().isBlank()) {
                    lineNode.put("traceId", rec.traceId());
                }
                String msg = sanitizer.sanitizeContent(rec.message());
                if (msg.length() > MAX_LINE_MESSAGE_CHARS) {
                    msg = msg.substring(0, MAX_LINE_MESSAGE_CHARS) + "...";
                }
                lineNode.put("message", msg);

                lines.add(lineNode);
                root.put("logCount", includedCount + 1);
                root.put("errorCount", errorCount + (isErrorLevel(level) ? 1 : 0));
                root.set("lines", lines);

                String candidate = objectMapper.writeValueAsString(root);
                if (candidate.length() > MAX_EXCERPT_CHARS && includedCount > 0) {
                    lines.remove(lines.size() - 1);
                    break;
                }
                includedCount++;
                if (isErrorLevel(level)) {
                    errorCount++;
                }
            }

            root.put("logCount", includedCount);
            root.put("errorCount", errorCount);
            root.set("lines", lines);
            String json = objectMapper.writeValueAsString(root);
            return json.length() <= MAX_EXCERPT_CHARS ? json : json.substring(0, MAX_EXCERPT_CHARS);
        } catch (Exception ex) {
            log.warn("Failed to serialize Loki evidence summary for service {}: {}", service, ex.getMessage());
            return null;
        }
    }

    private boolean isErrorLevel(String level) {
        if (level == null) {
            return false;
        }
        String upper = level.toUpperCase(Locale.ROOT);
        return upper.contains("ERROR") || upper.contains("FATAL") || upper.contains("CRITICAL");
    }

    private String validateServiceSafe(String rawService) {
        try {
            return validator.validateOptionalService(rawService);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String validateTraceIdSafe(String rawTraceId) {
        try {
            return validator.validateTraceId(rawTraceId, false);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
