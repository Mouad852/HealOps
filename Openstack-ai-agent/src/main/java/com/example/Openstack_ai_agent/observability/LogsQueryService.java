package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import com.example.Openstack_ai_agent.conversation.ConversationSanitizer;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.evidence.RedactionStatus;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
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
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LogsQueryService {
    private final ObservabilityQueryValidator validator;
    private final ObservabilityBackendClient backendClient;
    private final IncidentRepository incidentRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final ConversationSanitizer sanitizer;
    private final TransactionOperations readOnlyTx;

    public LogsQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityBackendClient backendClient,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            ConversationSanitizer sanitizer
    ) {
        this(validator, backendClient, incidentRepository, evidenceItemRepository, sanitizer, TransactionOperations.withoutTransaction());
    }

    @Autowired
    public LogsQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityBackendClient backendClient,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            ConversationSanitizer sanitizer,
            PlatformTransactionManager transactionManager
    ) {
        this(validator, backendClient, incidentRepository, evidenceItemRepository, sanitizer, readOnlyTemplate(transactionManager));
    }

    private LogsQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityBackendClient backendClient,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            ConversationSanitizer sanitizer,
            TransactionOperations readOnlyTx
    ) {
        this.validator = validator;
        this.backendClient = backendClient;
        this.incidentRepository = incidentRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.sanitizer = sanitizer;
        this.readOnlyTx = readOnlyTx;
    }

    private static TransactionOperations readOnlyTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

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
            List<LogQueryResponse.LogRecordDto> lokiRecords = LokiLogSupport.queryLokiStrictWithServiceFallback(
                    backendClient,
                    authorizedEnv,
                    validatedService,
                    validatedTraceId,
                    range.from(),
                    range.to(),
                    effectiveLimit + 1,
                    sanitizer
            );

            if (!lokiRecords.isEmpty()) {
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

    private List<LogQueryResponse.LogRecordDto> collectEvidenceLogs(
            String environmentId,
            String service,
            UUID incidentId,
            String traceId,
            ObservabilityQueryValidator.TimeRange range
    ) {
        return readOnlyTx.execute(status -> {
            List<IncidentEntity> scopedIncidents;
            if (incidentId != null) {
                scopedIncidents = incidentRepository.findById(incidentId)
                        .filter(i -> environmentId.equals(i.getEnvironmentId()))
                        .filter(i -> service == null || service.equals(i.getService()))
                        .map(List::of)
                        .orElse(List.of());
            } else if (service != null) {
                scopedIncidents = incidentRepository.findByEnvironmentIdAndService(environmentId, service);
            } else {
                scopedIncidents = incidentRepository.findByEnvironmentId(environmentId);
            }

            Map<UUID, IncidentEntity> envIncidents = scopedIncidents.stream()
                    .collect(Collectors.toMap(IncidentEntity::getId, i -> i));

            if (envIncidents.isEmpty()) {
                return List.of();
            }

            List<EvidenceItemEntity> items = incidentId != null
                    ? evidenceItemRepository.findByIncidentIdOrderByObservedAtDesc(incidentId)
                    : evidenceItemRepository.findByIncidentIdInOrderByObservedAtDesc(envIncidents.keySet());

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

                String sanitized = LokiLogSupport.sanitizeMessage(item.getExcerpt(), sanitizer);
                boolean redacted = item.getRedactionStatus() == RedactionStatus.REDACTED
                        || !sanitized.equals(LokiLogSupport.truncate(item.getExcerpt()));

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
                    case LOG -> LokiLogSupport.inferLevel(sanitized);
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
        });
    }
}
