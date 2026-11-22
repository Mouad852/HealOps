package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TracesQueryService {
    private final ObservabilityQueryValidator validator;
    private final ObservabilityQueryProperties properties;
    private final ObservabilityBackendClient backendClient;
    private final TraceAnalysisCalculator analysisCalculator;
    private final IncidentRepository incidentRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final ObjectMapper objectMapper;

    public TracesQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityQueryProperties properties,
            ObservabilityBackendClient backendClient,
            TraceAnalysisCalculator analysisCalculator,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            ObjectMapper objectMapper
    ) {
        this.validator = validator;
        this.properties = properties;
        this.backendClient = backendClient;
        this.analysisCalculator = analysisCalculator;
        this.incidentRepository = incidentRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public TraceResponses.TraceListResponse listTraces(
            String environmentId,
            String service,
            OffsetDateTime from,
            OffsetDateTime to,
            Integer limit,
            Authentication authentication
    ) {
        String authorizedEnv = EnvironmentAuthorizationSupport.requireAuthorizedEnvironment(environmentId, authentication);
        String validatedService = validator.validateOptionalService(service);
        ObservabilityQueryValidator.TimeRange range = validator.resolveAndValidateRange(from, to);
        int effectiveLimit = validator.resolveTraceLimit(limit);

        if (backendClient.isLiveQueryEnabled()) {
            Optional<JsonNode> liveJson = backendClient.queryJaegerV3Traces(
                    validatedService,
                    range.from(),
                    range.to(),
                    effectiveLimit
            );
            if (liveJson.isPresent()) {
                List<TraceAnalysisCalculator.RawSpan> liveSpans = analysisCalculator.parseJaegerV3OtlpSpans(
                        liveJson.get(),
                        effectiveLimit * properties.getMaxSpansPerTrace()
                );
                List<TraceResponses.TraceSummaryDto> summaries = analysisCalculator.summarizeByTrace(liveSpans);
                if (summaries.size() > effectiveLimit) {
                    summaries = summaries.subList(0, effectiveLimit);
                }
                return new TraceResponses.TraceListResponse(
                        authorizedEnv,
                        range.from(),
                        range.to(),
                        effectiveLimit,
                        "JAEGER_V3",
                        summaries
                );
            }
        }

        List<TraceResponses.TraceSummaryDto> localSummaries = collectLocalTraceSummaries(
                authorizedEnv,
                validatedService,
                range,
                effectiveLimit
        );
        return new TraceResponses.TraceListResponse(
                authorizedEnv,
                range.from(),
                range.to(),
                effectiveLimit,
                "LOCAL_EVIDENCE",
                localSummaries
        );
    }

    @Transactional(readOnly = true)
    public TraceResponses.TraceDetailResponse getTrace(
            String traceId,
            String environmentId,
            Authentication authentication
    ) {
        String authorizedEnv = EnvironmentAuthorizationSupport.requireAuthorizedEnvironment(environmentId, authentication);
        String validatedTraceId = validator.validateTraceId(traceId, true);

        if (backendClient.isLiveQueryEnabled()) {
            Optional<JsonNode> liveJson = backendClient.queryJaegerV3TraceById(validatedTraceId);
            if (liveJson.isPresent()) {
                List<TraceAnalysisCalculator.RawSpan> spans = analysisCalculator.parseJaegerV3OtlpSpans(
                        liveJson.get(),
                        properties.getMaxSpansPerTrace()
                );
                if (!spans.isEmpty()) {
                    return analysisCalculator.buildDetail(validatedTraceId, authorizedEnv, "JAEGER_V3", spans);
                }
            }
        }

        List<TraceAnalysisCalculator.RawSpan> localSpans = collectLocalSpansForTrace(authorizedEnv, validatedTraceId);
        if (localSpans.isEmpty()) {
            throw new TraceNotFoundException(validatedTraceId);
        }
        return analysisCalculator.buildDetail(validatedTraceId, authorizedEnv, "LOCAL_EVIDENCE", localSpans);
    }

    @Transactional(readOnly = true)
    public List<TraceAnalysisCalculator.RawSpan> collectSpansForTopology(
            String environmentId,
            ObservabilityQueryValidator.TimeRange range
    ) {
        if (backendClient.isLiveQueryEnabled()) {
            Optional<JsonNode> liveJson = backendClient.queryJaegerV3Traces(
                    null,
                    range.from(),
                    range.to(),
                    properties.getMaxTraceLimit()
            );
            if (liveJson.isPresent()) {
                List<TraceAnalysisCalculator.RawSpan> spans = analysisCalculator.parseJaegerV3OtlpSpans(
                        liveJson.get(),
                        properties.getMaxTraceLimit() * properties.getMaxSpansPerTrace()
                );
                if (!spans.isEmpty()) {
                    return spans;
                }
            }
        }
        return collectLocalSpansInRange(environmentId, range);
    }

    private List<TraceResponses.TraceSummaryDto> collectLocalTraceSummaries(
            String environmentId,
            String service,
            ObservabilityQueryValidator.TimeRange range,
            int limit
    ) {
        Map<String, TraceResponses.TraceSummaryDto> byTraceId = new LinkedHashMap<>();
        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
                .filter(i -> environmentId.equals(i.getEnvironmentId()))
                .filter(i -> service == null || service.equals(i.getService()))
                .filter(i -> i.getTraceId() != null && !i.getTraceId().isBlank())
                .filter(i -> !i.getCreatedAt().isBefore(range.from()) && !i.getCreatedAt().isAfter(range.to()))
                .sorted(Comparator.comparing(IncidentEntity::getCreatedAt).reversed())
                .toList();

        for (IncidentEntity incident : incidents) {
            String tid = incident.getTraceId();
            if (byTraceId.containsKey(tid)) {
                continue;
            }
            List<TraceAnalysisCalculator.RawSpan> spans = extractSpansFromIncident(incident);
            if (!spans.isEmpty()) {
                List<TraceResponses.TraceSummaryDto> summaries = analysisCalculator.summarizeByTrace(spans);
                if (!summaries.isEmpty()) {
                    TraceResponses.TraceSummaryDto s = summaries.getFirst();
                    byTraceId.put(tid, new TraceResponses.TraceSummaryDto(
                            s.traceId(),
                            s.rootService(),
                            s.rootOperation(),
                            s.startedAt(),
                            s.durationMs(),
                            s.spanCount(),
                            s.errorSpanCount(),
                            s.services(),
                            incident.getId()
                    ));
                }
            } else {
                byTraceId.put(tid, new TraceResponses.TraceSummaryDto(
                        tid,
                        incident.getService(),
                        "incident.alert",
                        incident.getStartedAt() != null ? incident.getStartedAt() : incident.getCreatedAt(),
                        0.0,
                        1,
                        1,
                        List.of(incident.getService()),
                        incident.getId()
                ));
            }
            if (byTraceId.size() >= limit) {
                break;
            }
        }
        return List.copyOf(byTraceId.values());
    }

    private List<TraceAnalysisCalculator.RawSpan> collectLocalSpansForTrace(String environmentId, String traceId) {
        List<IncidentEntity> matchingIncidents = incidentRepository.findAll().stream()
                .filter(i -> environmentId.equals(i.getEnvironmentId()))
                .filter(i -> traceId.equals(i.getTraceId()))
                .toList();

        List<TraceAnalysisCalculator.RawSpan> spans = new ArrayList<>();
        for (IncidentEntity incident : matchingIncidents) {
            List<TraceAnalysisCalculator.RawSpan> extracted = extractSpansFromIncident(incident);
            if (!extracted.isEmpty()) {
                spans.addAll(extracted);
            } else {
                spans.add(new TraceAnalysisCalculator.RawSpan(
                        traceId,
                        incident.getId().toString().substring(0, 16),
                        null,
                        incident.getService(),
                        "incident.alert",
                        incident.getStartedAt() != null ? incident.getStartedAt() : incident.getCreatedAt(),
                        0.0,
                        "ERROR",
                        Map.of("incidentId", incident.getId().toString(), "severity", incident.getSeverity().name())
                ));
            }
        }
        return spans;
    }

    private List<TraceAnalysisCalculator.RawSpan> collectLocalSpansInRange(
            String environmentId,
            ObservabilityQueryValidator.TimeRange range
    ) {
        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
                .filter(i -> environmentId.equals(i.getEnvironmentId()))
                .filter(i -> !i.getCreatedAt().isBefore(range.from()) && !i.getCreatedAt().isAfter(range.to()))
                .toList();

        List<TraceAnalysisCalculator.RawSpan> spans = new ArrayList<>();
        for (IncidentEntity incident : incidents) {
            spans.addAll(extractSpansFromIncident(incident));
        }
        return spans;
    }

    private List<TraceAnalysisCalculator.RawSpan> extractSpansFromIncident(IncidentEntity incident) {
        List<EvidenceItemEntity> traceItems = evidenceItemRepository
                .findByIncidentIdOrderByObservedAtDesc(incident.getId())
                .stream()
                .filter(item -> item.getType() == EvidenceType.TRACE)
                .toList();

        List<TraceAnalysisCalculator.RawSpan> spans = new ArrayList<>();
        for (EvidenceItemEntity item : traceItems) {
            try {
                JsonNode parsed = objectMapper.readTree(item.getExcerpt());
                spans.addAll(analysisCalculator.parseJaegerV3OtlpSpans(parsed, properties.getMaxSpansPerTrace()));
            } catch (Exception ignored) {
                // Non-OTLP trace excerpt; synthesize single trace span below if needed
            }
        }
        return spans;
    }
}
