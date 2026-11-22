package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionOperations readOnlyTx;

    public TracesQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityQueryProperties properties,
            ObservabilityBackendClient backendClient,
            TraceAnalysisCalculator analysisCalculator,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            ObjectMapper objectMapper
    ) {
        this(validator, properties, backendClient, analysisCalculator, incidentRepository, evidenceItemRepository, objectMapper, TransactionOperations.withoutTransaction());
    }

    @Autowired
    public TracesQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityQueryProperties properties,
            ObservabilityBackendClient backendClient,
            TraceAnalysisCalculator analysisCalculator,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this(validator, properties, backendClient, analysisCalculator, incidentRepository, evidenceItemRepository, objectMapper, readOnlyTemplate(transactionManager));
    }

    private TracesQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityQueryProperties properties,
            ObservabilityBackendClient backendClient,
            TraceAnalysisCalculator analysisCalculator,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            ObjectMapper objectMapper,
            TransactionOperations readOnlyTx
    ) {
        this.validator = validator;
        this.properties = properties;
        this.backendClient = backendClient;
        this.analysisCalculator = analysisCalculator;
        this.incidentRepository = incidentRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.objectMapper = objectMapper;
        this.readOnlyTx = readOnlyTx;
    }

    private static TransactionOperations readOnlyTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

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
            List<TraceAnalysisCalculator.RawSpan> liveSpans = queryLiveSpans(
                    validatedService,
                    range,
                    effectiveLimit,
                    effectiveLimit * properties.getMaxSpansPerTrace()
            );
            if (!liveSpans.isEmpty()) {
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

    public List<TraceAnalysisCalculator.RawSpan> collectSpansForTopology(
            String environmentId,
            ObservabilityQueryValidator.TimeRange range
    ) {
        if (backendClient.isLiveQueryEnabled()) {
            int maxSpans = properties.getMaxTraceLimit() * properties.getMaxSpansPerTrace();
            List<TraceAnalysisCalculator.RawSpan> liveSpans = queryLiveSpans(
                    null,
                    range,
                    properties.getMaxTraceLimit(),
                    maxSpans
            );
            if (!liveSpans.isEmpty()) {
                return liveSpans;
            }
        }
        return collectLocalSpansInRange(environmentId, range);
    }

    private List<TraceAnalysisCalculator.RawSpan> queryLiveSpans(
            String specificService,
            ObservabilityQueryValidator.TimeRange range,
            int perServiceLimit,
            int maxTotalSpans
    ) {
        List<String> targetServices = specificService != null
                ? List.of(specificService)
                : backendClient.queryJaegerV3Services();
        if (targetServices.isEmpty()) {
            return List.of();
        }
        Map<String, TraceAnalysisCalculator.RawSpan> deduplicated = new LinkedHashMap<>();
        for (String svc : targetServices) {
            if (deduplicated.size() >= maxTotalSpans) {
                break;
            }
            Optional<JsonNode> liveJson = backendClient.queryJaegerV3Traces(
                    svc,
                    range.from(),
                    range.to(),
                    perServiceLimit
            );
            if (liveJson.isEmpty()) {
                continue;
            }
            List<TraceAnalysisCalculator.RawSpan> parsed = analysisCalculator.parseJaegerV3OtlpSpans(
                    liveJson.get(),
                    maxTotalSpans
            );
            for (TraceAnalysisCalculator.RawSpan span : parsed) {
                deduplicated.putIfAbsent(span.traceId() + ":" + span.spanId(), span);
                if (deduplicated.size() >= maxTotalSpans) {
                    break;
                }
            }
        }
        return List.copyOf(deduplicated.values());
    }

    private List<TraceResponses.TraceSummaryDto> collectLocalTraceSummaries(
            String environmentId,
            String service,
            ObservabilityQueryValidator.TimeRange range,
            int limit
    ) {
        return readOnlyTx.execute(status -> {
            List<IncidentEntity> scoped = service != null
                    ? incidentRepository.findByEnvironmentIdAndServiceAndCreatedAtBetweenOrderByCreatedAtDesc(
                            environmentId, service, range.from(), range.to())
                    : incidentRepository.findByEnvironmentIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                            environmentId, range.from(), range.to());

            List<IncidentEntity> incidents = scoped.stream()
                    .filter(i -> i.getTraceId() != null && !i.getTraceId().isBlank())
                    .toList();
            if (incidents.isEmpty()) {
                return List.of();
            }

            Map<UUID, List<TraceAnalysisCalculator.RawSpan>> spansByIncident = extractSpansByIncident(incidents);
            Map<String, TraceResponses.TraceSummaryDto> byTraceId = new LinkedHashMap<>();

            for (IncidentEntity incident : incidents) {
                String tid = incident.getTraceId();
                if (byTraceId.containsKey(tid)) {
                    continue;
                }
                List<TraceAnalysisCalculator.RawSpan> spans = spansByIncident.getOrDefault(incident.getId(), List.of());
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
        });
    }

    private List<TraceAnalysisCalculator.RawSpan> collectLocalSpansForTrace(String environmentId, String traceId) {
        return readOnlyTx.execute(status -> {
            List<IncidentEntity> matchingIncidents = incidentRepository.findByEnvironmentIdAndTraceId(environmentId, traceId);
            if (matchingIncidents.isEmpty()) {
                return List.of();
            }

            Map<UUID, List<TraceAnalysisCalculator.RawSpan>> spansByIncident = extractSpansByIncident(matchingIncidents);
            List<TraceAnalysisCalculator.RawSpan> spans = new ArrayList<>();
            for (IncidentEntity incident : matchingIncidents) {
                List<TraceAnalysisCalculator.RawSpan> extracted = spansByIncident.getOrDefault(incident.getId(), List.of());
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
        });
    }

    private List<TraceAnalysisCalculator.RawSpan> collectLocalSpansInRange(
            String environmentId,
            ObservabilityQueryValidator.TimeRange range
    ) {
        return readOnlyTx.execute(status -> {
            List<IncidentEntity> incidents = incidentRepository
                    .findByEnvironmentIdAndCreatedAtBetweenOrderByCreatedAtDesc(environmentId, range.from(), range.to());
            if (incidents.isEmpty()) {
                return List.of();
            }

            Map<UUID, List<TraceAnalysisCalculator.RawSpan>> spansByIncident = extractSpansByIncident(incidents);
            List<TraceAnalysisCalculator.RawSpan> spans = new ArrayList<>();
            for (IncidentEntity incident : incidents) {
                spans.addAll(spansByIncident.getOrDefault(incident.getId(), List.of()));
            }
            return spans;
        });
    }

    private Map<UUID, List<TraceAnalysisCalculator.RawSpan>> extractSpansByIncident(List<IncidentEntity> incidents) {
        List<UUID> incidentIds = incidents.stream().map(IncidentEntity::getId).toList();
        if (incidentIds.isEmpty()) {
            return Map.of();
        }
        List<EvidenceItemEntity> traceItems = evidenceItemRepository
                .findByIncidentIdInAndTypeOrderByObservedAtDesc(incidentIds, EvidenceType.TRACE);
        Map<UUID, List<TraceAnalysisCalculator.RawSpan>> byIncident = new LinkedHashMap<>();
        for (EvidenceItemEntity item : traceItems) {
            try {
                JsonNode parsed = objectMapper.readTree(item.getExcerpt());
                List<TraceAnalysisCalculator.RawSpan> parsedSpans =
                        analysisCalculator.parseJaegerV3OtlpSpans(parsed, properties.getMaxSpansPerTrace());
                if (!parsedSpans.isEmpty()) {
                    byIncident.computeIfAbsent(item.getIncidentId(), ignored -> new ArrayList<>())
                            .addAll(parsedSpans);
                }
            } catch (Exception ignored) {
                // Non-OTLP trace excerpt; synthesize single trace span below if needed
            }
        }
        return byIncident;
    }
}
