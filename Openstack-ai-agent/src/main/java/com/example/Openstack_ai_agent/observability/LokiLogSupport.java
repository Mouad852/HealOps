package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.conversation.ConversationSanitizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shared Loki query selector builders, fallback query helpers, and redacted record parsers
 * used by {@link LogsQueryService}, evidence collectors, and Copilot tools.
 */
public final class LokiLogSupport {
    public static final int MAX_LOG_MESSAGE_CHARS = 2048;

    private LokiLogSupport() {
    }

    public static String buildStrictLokiSelector(String environmentId, String service, String traceId) {
        List<String> matchers = new ArrayList<>();
        if (environmentId != null && !environmentId.isBlank()) {
            matchers.add("deployment_environment=\"" + environmentId + "\"");
        }
        if (service != null && !service.isBlank()) {
            matchers.add("service_name=\"" + service + "\"");
        }
        String selector = "{" + String.join(", ", matchers) + "}";
        if (traceId != null && !traceId.isBlank()) {
            selector = selector + " |= \"" + traceId + "\"";
        }
        return selector;
    }

    public static String buildServiceOnlyLokiSelector(String service, String traceId) {
        String selector = "{service_name=\"" + service + "\"}";
        if (traceId != null && !traceId.isBlank()) {
            selector = selector + " |= \"" + traceId + "\"";
        }
        return selector;
    }

    /**
     * Queries Loki using the strict environment+service selector first and falls back to
     * service-only selector while preserving any explicit {@code traceId} filter.
     */
    public static List<LogQueryResponse.LogRecordDto> queryLokiStrictWithServiceFallback(
            ObservabilityBackendClient backendClient,
            String environmentId,
            String service,
            String traceId,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit,
            ConversationSanitizer sanitizer
    ) {
        if (backendClient == null || !backendClient.isLiveQueryEnabled()) {
            return List.of();
        }
        String selector = buildStrictLokiSelector(environmentId, service, traceId);
        List<LogQueryResponse.LogRecordDto> records = backendClient.queryLokiRange(selector, from, to, limit)
                .map(json -> parseLokiRecords(json, service, traceId, sanitizer))
                .orElse(List.of());

        if (records.isEmpty() && service != null && !service.isBlank()) {
            String serviceOnlySelector = buildServiceOnlyLokiSelector(service, traceId);
            Optional<JsonNode> fallbackLoki = backendClient.queryLokiRange(serviceOnlySelector, from, to, limit);
            if (fallbackLoki.isPresent()) {
                records = parseLokiRecords(fallbackLoki.get(), service, traceId, sanitizer);
            }
        }
        return records;
    }

    /**
     * Queries Loki for incident/copilot workflows:
     * 1) {deployment_environment, service_name} |= traceId (if traceId present)
     * 2) {deployment_environment, service_name}
     * 3) {service_name} |= traceId (if traceId present)
     * 4) {service_name}
     */
    public static List<LogQueryResponse.LogRecordDto> queryLokiIncidentOrServiceLogs(
            ObservabilityBackendClient backendClient,
            String environmentId,
            String service,
            String traceId,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit,
            ConversationSanitizer sanitizer
    ) {
        if (backendClient == null || !backendClient.isLiveQueryEnabled() || service == null || service.isBlank()) {
            return List.of();
        }
        boolean hasEnv = environmentId != null && !environmentId.isBlank();
        boolean hasTrace = traceId != null && !traceId.isBlank();

        if (hasEnv) {
            if (hasTrace) {
                List<LogQueryResponse.LogRecordDto> strictTrace = querySelector(
                        backendClient,
                        buildStrictLokiSelector(environmentId, service, traceId),
                        service,
                        traceId,
                        from,
                        to,
                        limit,
                        sanitizer
                );
                if (!strictTrace.isEmpty()) {
                    return strictTrace;
                }
            }
            List<LogQueryResponse.LogRecordDto> strictService = querySelector(
                    backendClient,
                    buildStrictLokiSelector(environmentId, service, null),
                    service,
                    traceId,
                    from,
                    to,
                    limit,
                    sanitizer
            );
            if (!strictService.isEmpty()) {
                return strictService;
            }
        }

        if (hasTrace) {
            List<LogQueryResponse.LogRecordDto> serviceTrace = querySelector(
                    backendClient,
                    buildServiceOnlyLokiSelector(service, traceId),
                    service,
                    traceId,
                    from,
                    to,
                    limit,
                    sanitizer
            );
            if (!serviceTrace.isEmpty()) {
                return serviceTrace;
            }
        }

        return querySelector(
                backendClient,
                buildServiceOnlyLokiSelector(service, null),
                service,
                traceId,
                from,
                to,
                limit,
                sanitizer
        );
    }

    private static List<LogQueryResponse.LogRecordDto> querySelector(
            ObservabilityBackendClient backendClient,
            String selector,
            String service,
            String traceId,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit,
            ConversationSanitizer sanitizer
    ) {
        return backendClient.queryLokiRange(selector, from, to, limit)
                .map(json -> parseLokiRecords(json, service, traceId, sanitizer))
                .orElse(List.of());
    }

    public static List<LogQueryResponse.LogRecordDto> parseLokiRecords(
            JsonNode root,
            String defaultService,
            String defaultTraceId,
            ConversationSanitizer sanitizer
    ) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) {
            return List.of();
        }
        List<LogQueryResponse.LogRecordDto> records = new ArrayList<>();
        for (JsonNode streamEntry : result) {
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode streamNode = streamEntry.path("stream");
            if (streamNode.isObject()) {
                streamNode.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isTextual()) {
                        labels.put(entry.getKey(), entry.getValue().asText());
                    }
                });
            }
            String streamService = labels.getOrDefault("service_name",
                    labels.getOrDefault("service", defaultService != null ? defaultService : "unknown"));
            String explicitLevel = labels.getOrDefault("level",
                    labels.getOrDefault("severity_text", labels.getOrDefault("detected_level", "")));
            String streamTraceId = labels.getOrDefault("trace_id", defaultTraceId);

            JsonNode values = streamEntry.path("values");
            if (values.isArray()) {
                for (JsonNode tuple : values) {
                    if (tuple.isArray() && tuple.size() >= 2) {
                        OffsetDateTime ts = parseEpochNanos(tuple.get(0).asText());
                        String rawMessage = tuple.get(1).asText("");
                        String sanitized = sanitizeMessage(rawMessage, sanitizer);
                        boolean wasRedacted = !sanitized.equals(truncate(rawMessage));
                        String resolvedLevel = explicitLevel != null && !explicitLevel.isBlank()
                                ? explicitLevel.toUpperCase(Locale.ROOT)
                                : inferLevel(sanitized);
                        records.add(new LogQueryResponse.LogRecordDto(
                                ts,
                                streamService,
                                resolvedLevel,
                                sanitized,
                                streamTraceId,
                                null,
                                "loki",
                                wasRedacted,
                                labels
                        ));
                    }
                }
            }
        }
        records.sort(Comparator.comparing(LogQueryResponse.LogRecordDto::timestamp).reversed());
        return records;
    }

    /**
     * Orders logs so ERROR/FATAL lines come first, then WARN, then recent INFO context lines.
     */
    public static List<LogQueryResponse.LogRecordDto> prioritizeBySeverity(
            List<LogQueryResponse.LogRecordDto> records,
            int maxRecords
    ) {
        if (records == null || records.isEmpty() || maxRecords <= 0) {
            return List.of();
        }
        return records.stream()
                .sorted(Comparator
                        .comparingInt((LogQueryResponse.LogRecordDto r) -> levelPriority(r.level()))
                        .thenComparing(LogQueryResponse.LogRecordDto::timestamp, Comparator.reverseOrder()))
                .limit(maxRecords)
                .toList();
    }

    public static String sanitizeMessage(String raw, ConversationSanitizer sanitizer) {
        return sanitizer.sanitizeContent(truncate(raw == null ? "" : raw));
    }

    public static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= MAX_LOG_MESSAGE_CHARS ? raw : raw.substring(0, MAX_LOG_MESSAGE_CHARS);
    }

    public static String inferLevel(String message) {
        if (message == null || message.isBlank()) {
            return "INFO";
        }
        String upper = message.toUpperCase(Locale.ROOT);
        if (upper.contains("ERROR") || upper.contains("EXCEPTION") || upper.contains("FATAL") || upper.contains("OOM")) {
            return "ERROR";
        }
        if (upper.contains("WARN")) {
            return "WARN";
        }
        return "INFO";
    }

    private static int levelPriority(String level) {
        if (level == null) {
            return 2;
        }
        String upper = level.toUpperCase(Locale.ROOT);
        if (upper.contains("ERROR") || upper.contains("FATAL") || upper.contains("CRITICAL")) {
            return 0;
        }
        if (upper.contains("WARN")) {
            return 1;
        }
        return 2;
    }

    private static OffsetDateTime parseEpochNanos(String nanosRaw) {
        try {
            long nanos = Long.parseLong(nanosRaw);
            long secs = nanos / 1_000_000_000L;
            long remNanos = nanos % 1_000_000_000L;
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(secs, remNanos), ZoneOffset.UTC);
        } catch (NumberFormatException ex) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
