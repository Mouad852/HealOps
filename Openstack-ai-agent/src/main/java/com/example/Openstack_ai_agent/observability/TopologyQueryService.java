package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotEntity;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TopologyQueryService {
    private static final Set<IncidentStatus> TERMINAL_STATUSES = EnumSet.of(
            IncidentStatus.CLOSED,
            IncidentStatus.ROLLED_BACK,
            IncidentStatus.FAILED,
            IncidentStatus.CANCELLED
    );

    private final ObservabilityQueryValidator validator;
    private final TracesQueryService tracesQueryService;
    private final TopologyDerivationService derivationService;
    private final IncidentRepository incidentRepository;
    private final HealthSnapshotRepository healthSnapshotRepository;
    private final ObservabilityBackendClient backendClient;
    private final TransactionOperations readOnlyTx;

    public TopologyQueryService(
            ObservabilityQueryValidator validator,
            TracesQueryService tracesQueryService,
            TopologyDerivationService derivationService,
            IncidentRepository incidentRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            ObservabilityBackendClient backendClient
    ) {
        this(validator, tracesQueryService, derivationService, incidentRepository, healthSnapshotRepository, backendClient, TransactionOperations.withoutTransaction());
    }

    @Autowired
    public TopologyQueryService(
            ObservabilityQueryValidator validator,
            TracesQueryService tracesQueryService,
            TopologyDerivationService derivationService,
            IncidentRepository incidentRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            ObservabilityBackendClient backendClient,
            PlatformTransactionManager transactionManager
    ) {
        this(validator, tracesQueryService, derivationService, incidentRepository, healthSnapshotRepository, backendClient, readOnlyTemplate(transactionManager));
    }

    private TopologyQueryService(
            ObservabilityQueryValidator validator,
            TracesQueryService tracesQueryService,
            TopologyDerivationService derivationService,
            IncidentRepository incidentRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            ObservabilityBackendClient backendClient,
            TransactionOperations readOnlyTx
    ) {
        this.validator = validator;
        this.tracesQueryService = tracesQueryService;
        this.derivationService = derivationService;
        this.incidentRepository = incidentRepository;
        this.healthSnapshotRepository = healthSnapshotRepository;
        this.backendClient = backendClient;
        this.readOnlyTx = readOnlyTx;
    }

    private static TransactionOperations readOnlyTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    public TopologyResponse getTopology(
            String environmentId,
            OffsetDateTime from,
            OffsetDateTime to,
            Authentication authentication
    ) {
        String authorizedEnv = EnvironmentAuthorizationSupport.requireAuthorizedEnvironment(environmentId, authentication);
        ObservabilityQueryValidator.TimeRange range = validator.resolveAndValidateRange(from, to);

        List<TraceAnalysisCalculator.RawSpan> spans = tracesQueryService.collectSpansForTopology(authorizedEnv, range);
        List<TopologyDerivationService.ServiceIncidentSignal> incidentSignals = collectIncidentSignals(authorizedEnv, range);
        String dataSource = backendClient.isLiveQueryEnabled() && !spans.isEmpty()
                ? "DERIVED_FROM_JAEGER_V3_AND_INCIDENTS"
                : "DERIVED_FROM_EVIDENCE_AND_INCIDENTS";

        return derivationService.derive(
                authorizedEnv,
                range.from(),
                range.to(),
                dataSource,
                spans,
                incidentSignals
        );
    }

    private List<TopologyDerivationService.ServiceIncidentSignal> collectIncidentSignals(
            String environmentId,
            ObservabilityQueryValidator.TimeRange range
    ) {
        return readOnlyTx.execute(status -> {
            List<IncidentEntity> envIncidents = incidentRepository.findForTopology(
                    environmentId,
                    TERMINAL_STATUSES,
                    range.from(),
                    range.to()
            );
            if (envIncidents.isEmpty()) {
                return List.of();
            }

            Map<UUID, IncidentEntity> byId = envIncidents.stream()
                    .collect(Collectors.toMap(IncidentEntity::getId, i -> i));

            Map<String, HealthSnapshotEntity> latestSnapshotByService = new LinkedHashMap<>();
            for (HealthSnapshotEntity snapshot : healthSnapshotRepository.findByIncidentIdIn(byId.keySet())) {
                IncidentEntity incident = byId.get(snapshot.getIncidentId());
                if (incident == null) {
                    continue;
                }
                HealthSnapshotEntity current = latestSnapshotByService.get(incident.getService());
                if (current == null || snapshot.getCapturedAt().isAfter(current.getCapturedAt())) {
                    latestSnapshotByService.put(incident.getService(), snapshot);
                }
            }

            Map<String, List<IncidentEntity>> byService = envIncidents.stream()
                    .collect(Collectors.groupingBy(IncidentEntity::getService, LinkedHashMap::new, Collectors.toList()));

            List<TopologyDerivationService.ServiceIncidentSignal> signals = new ArrayList<>();
            for (Map.Entry<String, List<IncidentEntity>> entry : byService.entrySet()) {
                String service = entry.getKey();
                List<IncidentEntity> group = entry.getValue();
                long activeCount = group.stream().filter(i -> !i.getStatus().isTerminal()).count();
                IncidentSeverity highest = group.stream()
                        .filter(i -> !i.getStatus().isTerminal())
                        .map(IncidentEntity::getSeverity)
                        .min(Comparator.comparingInt(this::severityRank))
                        .orElse(null);

                HealthSnapshotEntity snap = latestSnapshotByService.get(service);
                String snapHealth = snap != null && snap.getSignals() != null && snap.getSignals().get("healthStatus") != null
                        ? String.valueOf(snap.getSignals().get("healthStatus"))
                        : null;

                signals.add(new TopologyDerivationService.ServiceIncidentSignal(
                        service,
                        activeCount,
                        highest,
                        snapHealth
                ));
            }
            return signals;
        });
    }

    private int severityRank(IncidentSeverity severity) {
        return switch (severity) {
            case CRITICAL -> 0;
            case HIGH -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
        };
    }
}
