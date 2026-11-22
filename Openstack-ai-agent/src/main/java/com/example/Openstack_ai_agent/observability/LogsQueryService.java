package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import com.example.Openstack_ai_agent.conversation.ConversationSanitizer;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.evidence.RedactionStatus;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LogsQueryService {
    private static final int MAX_LOG_MESSAGE_CHARS = 2048;

    private final ObservabilityQueryValidator validator;
    private final ObservabilityBackendClient backendClient;
    private final IncidentRepository incidentRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final ConversationSanitizer sanitizer;

    public LogsQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityBackendClient backendClient,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            ConversationSanitizer sanitizer
    ) {
        this.validator = validator;
        this.backendClient = backendClient;
        this.incidentRepository = incidentRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.sanitizer = sanitizer;
    }

    @Transactional(readOnly = true)
    public LogQueryResponse queryLogs(
            String environmentId,
            String service,
            UUID incidentId,
            String traceId,
            OffsetDateTime from,
            OffsetDateTime to,
            Integer limit,
            Authentication authentication
    ) {
        String authorizedEnv = EnvironmentAuthorizationSupport.requireAuthorizedEnvironment(environmentId, authentication);
        String validatedService = validator.validateOptionalService(service);
        String validatedTraceId = validator.validateTraceId(traceId, false);
        ObservabilityQueryValidator.TimeRange range = validator.resolveAndValidateRange(from, to);
        int effectiveLimit = validator.resolveLogLimit(limit);

        if (backendClient.isLiveQueryEnabled() && incidentId == null) {
            String selector = buildStrictLokiSelector(authorizedEnv, validatedService, validatedTraceId);
            Optional<JsonNode> lokiResponse = backendClient.queryLokiRange(selector, range.from(), range.to(), effectiveLimit + 1);
            if (lokiResponse.isPresent()) {
                List<LogQueryResponse.LogRecordDto> lokiRecords = parseLokiRecords(
                        lokiResponse.get(),
                        validatedService,
                        validatedTraceId
                );
                boolean truncated = lokiRecords.size() > effectiveLimit;
                List<LogQueryResponse.LogRecordDto> bounded = truncated
                        ? lokiRecords.subList(0, effectiveLimit)
                        : lokiRecords;
                return new LogQueryResponse(
                        authorizedEnv,
                        range.from(),
                        range.to(),
                        effectiveLimit,
                        truncated,
                        "LOKI",
                        bounded
                );
            }
        }

        List<LogQueryResponse.LogRecordDto> evidenceRecords = collectEvidenceLogs(
                authorizedEnv,
                validatedService,
                incidentId,
                validatedTraceId,
                range
        );
        boolean truncated = evidenceRecords.size() > effectiveLimit;
        List<LogQueryResponse.LogRecordDto> bounded = truncated
                ? evidenceRecords.subList(0, effectiveLimit)
                : evidenceRecords;

        return new LogQueryResponse(
                authorizedEnv,
                range.from(),
                range.to(),
                effectiveLimit,
                truncated,
                "LOCAL_EVIDENCE",
                bounded
        );
    }

    private String buildStrictLokiSelector(String environmentId, String service, String traceId) {
        List<String> matchers = new ArrayList<>();
        matchers.add("deployment_environment=\"" + environmentId + "\"");
        if (service != null) {
            matchers.add("service_name=\"" + service + "\"");
        }
        String selector = "{" + String.join(", ", matchers) + "}";
        if (traceId != null) {
            selector = selector + " |= \"" + traceId + "\"";
        }
        return selector;
    }

    private List<LogQueryResponse.LogRecordDto> parseLokiRecords(
            JsonNode root,
            String defaultService,
            String defaultTraceId
    ) {
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
            String streamLevel = labels.getOrDefault("level",
                    labels.getOrDefault("severity_text", "INFO")).toUpperCase(Locale.ROOT);
            String streamTraceId = labels.getOrDefault("trace_id", defaultTraceId);

            JsonNode values = streamEntry.path("values");
            if (values.isArray()) {
                for (JsonNode tuple : values) {
                    if (tuple.isArray() && tuple.size() >= 2) {
                        OffsetDateTime ts = parseEpochNanos(tuple.get(0).asText());
                        String rawMessage = tuple.get(1).asText("");
                        String sanitized = sanitizeMessage(rawMessage);
                        boolean wasRedacted = !sanitized.equals(truncate(rawMessage));
                        records.add(new LogQueryResponse.LogRecordDto(
                                ts,
                                streamService,
                                streamLevel,
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

    private List<LogQueryResponse.LogRecordDto> collectEvidenceLogs(
            String environmentId,
            String service,
            UUID incidentId,
            String traceId,
            ObservabilityQueryValidator.TimeRange range
    ) {
        Map<UUID, IncidentEntity> envIncidents = incidentRepository.findAll().stream()
                .filter(i -> environmentId.equals(i.getEnvironmentId()))
                .filter(i -> service == null || service.equals(i.getService()))
                .filter(i -> incidentId == null || incidentId.equals(i.getId()))
                .collect(Collectors.toMap(IncidentEntity::getId, i -> i));

        if (envIncidents.isEmpty()) {
            return List.of();
        }

        List<EvidenceItemEntity> items = incidentId != null
                ? evidenceItemRepository.findByIncidentIdOrderByObservedAtDesc(incidentId)
                : evidenceItemRepository.findAll();

        List<LogQueryResponse.LogRecordDto> records = new ArrayList<>();
        for (EvidenceItemEntity item : items) {
            IncidentEntity incident = envIncidents.get(item.getIncidentId());
            if (incident == null) {
                continue;
            }
            OffsetDateTime observedAt = item.getObservedAt();
            if (observedAt == null || observedAt.isBefore(range.from()) || observedAt.isAfter(range.to())) {
                continue;
            }
            String effectiveTraceId = item.getTraceId() != null ? item.getTraceId() : incident.getTraceId();
            if (traceId != null && !traceId.equals(effectiveTraceId)) {
                continue;
            }

            String sanitized = sanitizeMessage(item.getExcerpt());
            boolean redacted = item.getRedactionStatus() == RedactionStatus.REDACTED
                    || !sanitized.equals(truncate(item.getExcerpt()));

            Map<String, String> attrs = new LinkedHashMap<>();
            if (item.getMetadata() != null) {
                attrs.putAll(item.getMetadata());
            }
            attrs.put("evidenceType", item.getType().name());
            if (item.getTarget() != null) {
                attrs.put("target", item.getTarget());
            }

            String level = switch (item.getType()) {
                case COLLECTION_ERROR -> "ERROR";
                case LOG -> inferLevel(sanitized);
                default -> "INFO";
            };

            records.add(new LogQueryResponse.LogRecordDto(
                    observedAt,
                    incident.getService(),
                    level,
                    sanitized,
                    effectiveTraceId,
                    incident.getId(),
                    item.getSource(),
                    redacted,
                    attrs
            ));
        }

        records.sort(Comparator.comparing(LogQueryResponse.LogRecordDto::timestamp).reversed());
        return records;
    }

    private String sanitizeMessage(String raw) {
        return sanitizer.sanitizeContent(truncate(raw == null ? "" : raw));
    }

    private String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= MAX_LOG_MESSAGE_CHARS ? raw : raw.substring(0, MAX_LOG_MESSAGE_CHARS);
    }

    private String inferLevel(String message) {
        String upper = message.toUpperCase(Locale.ROOT);
        if (upper.contains("ERROR") || upper.contains("EXCEPTION") || upper.contains("FATAL") || upper.contains("OOM")) {
            return "ERROR";
        }
        if (upper.contains("WARN")) {
            return "WARN";
        }
        return "INFO";
    }

    private OffsetDateTime parseEpochNanos(String nanosRaw) {
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
