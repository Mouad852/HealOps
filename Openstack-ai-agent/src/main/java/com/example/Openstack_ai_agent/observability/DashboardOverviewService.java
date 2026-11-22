package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.analytics.RemediationAnalyticsResponse;
import com.example.Openstack_ai_agent.analytics.RemediationAnalyticsService;
import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotEntity;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository;
import com.example.Openstack_ai_agent.environment.EnvironmentCatalogService;
import com.example.Openstack_ai_agent.environment.EnvironmentResponse;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.remediation.ProposedActionRepository;
import com.example.Openstack_ai_agent.remediation.ProposedActionStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DashboardOverviewService {
    private final EnvironmentCatalogService environmentCatalogService;
    private final IncidentRepository incidentRepository;
    private final ProposedActionRepository proposedActionRepository;
    private final HealthSnapshotRepository healthSnapshotRepository;
    private final RemediationAnalyticsService remediationAnalyticsService;

    public DashboardOverviewService(
            EnvironmentCatalogService environmentCatalogService,
            IncidentRepository incidentRepository,
            ProposedActionRepository proposedActionRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            RemediationAnalyticsService remediationAnalyticsService
    ) {
        this.environmentCatalogService = environmentCatalogService;
        this.incidentRepository = incidentRepository;
        this.proposedActionRepository = proposedActionRepository;
        this.healthSnapshotRepository = healthSnapshotRepository;
        this.remediationAnalyticsService = remediationAnalyticsService;
    }

    @Transactional(readOnly = true)
    public DashboardOverviewResponse getOverview(String environmentId, Authentication authentication) {
        String normalizedFilter = (environmentId == null || environmentId.isBlank()) ? null : environmentId.trim();
        Set<String> scopedEnvs = EnvironmentAuthorizationSupport.resolveAuthorizedScope(normalizedFilter, authentication);
        List<EnvironmentResponse> environments = environmentCatalogService.listAuthorizedEnvironmentsForScope(scopedEnvs);

        List<IncidentEntity> scopedIncidents = scopedEnvs.isEmpty()
                ? List.of()
                : incidentRepository.findByEnvironmentIdIn(scopedEnvs);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        long activeCount = 0;
        for (IncidentEntity incident : scopedIncidents) {
            byStatus.merge(incident.getStatus().name(), 1L, Long::sum);
            bySeverity.merge(incident.getSeverity().name(), 1L, Long::sum);
            if (!incident.getStatus().isTerminal()) {
                activeCount++;
            }
        }

        Set<UUID> scopedIncidentIds = scopedIncidents.stream()
                .map(IncidentEntity::getId)
                .collect(Collectors.toSet());

        long pendingApprovalCount = scopedIncidentIds.isEmpty()
                ? 0L
                : proposedActionRepository.countByIncidentIdInAndStatus(scopedIncidentIds, ProposedActionStatus.PROPOSED);

        List<DashboardOverviewResponse.ServiceHealthCard> serviceHealth = buildServiceHealthCards(scopedIncidents);
        RemediationAnalyticsResponse analytics = remediationAnalyticsService
                .getRemediationAnalytics(normalizedFilter, authentication);

        return new DashboardOverviewResponse(
                normalizedFilter,
                environments,
                new DashboardOverviewResponse.IncidentOverviewSummary(
                        scopedIncidents.size(),
                        activeCount,
                        Map.copyOf(byStatus),
                        Map.copyOf(bySeverity)
                ),
                pendingApprovalCount,
                serviceHealth,
                analytics,
                OffsetDateTime.now()
        );
    }

    private List<DashboardOverviewResponse.ServiceHealthCard> buildServiceHealthCards(List<IncidentEntity> incidents) {
        if (incidents.isEmpty()) {
            return List.of();
        }
        Map<UUID, IncidentEntity> byId = incidents.stream()
                .collect(Collectors.toMap(IncidentEntity::getId, i -> i));

        Map<String, HealthSnapshotEntity> latestSnapshotByEnvService = new LinkedHashMap<>();
        for (HealthSnapshotEntity snapshot : healthSnapshotRepository.findByIncidentIdIn(byId.keySet())) {
            IncidentEntity incident = byId.get(snapshot.getIncidentId());
            if (incident == null) {
                continue;
            }
            String key = incident.getEnvironmentId() + "::" + incident.getService();
            HealthSnapshotEntity current = latestSnapshotByEnvService.get(key);
            if (current == null || snapshot.getCapturedAt().isAfter(current.getCapturedAt())) {
                latestSnapshotByEnvService.put(key, snapshot);
            }
        }

        Map<String, List<IncidentEntity>> incidentsByEnvService = incidents.stream()
                .collect(Collectors.groupingBy(
                        i -> i.getEnvironmentId() + "::" + i.getService(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<DashboardOverviewResponse.ServiceHealthCard> cards = new ArrayList<>();
        for (Map.Entry<String, List<IncidentEntity>> entry : incidentsByEnvService.entrySet()) {
            List<IncidentEntity> group = entry.getValue();
            IncidentEntity sample = group.getFirst();
            long activeCount = group.stream().filter(i -> !i.getStatus().isTerminal()).count();
            IncidentSeverity highestSeverity = group.stream()
                    .filter(i -> !i.getStatus().isTerminal())
                    .map(IncidentEntity::getSeverity)
                    .min(Comparator.comparingInt(this::severityRank))
                    .orElse(null);

            HealthSnapshotEntity latestSnapshot = latestSnapshotByEnvService.get(entry.getKey());
            String healthStatus = resolveHealthStatus(latestSnapshot, activeCount, highestSeverity);
            OffsetDateTime lastUpdated = latestSnapshot != null
                    ? latestSnapshot.getCapturedAt()
                    : group.stream()
                            .map(IncidentEntity::getCreatedAt)
                            .max(Comparator.naturalOrder())
                            .orElse(OffsetDateTime.now());

            cards.add(new DashboardOverviewResponse.ServiceHealthCard(
                    sample.getEnvironmentId(),
                    sample.getService(),
                    healthStatus,
                    activeCount,
                    highestSeverity == null ? null : highestSeverity.name(),
                    lastUpdated
            ));
        }

        cards.sort(Comparator.comparing(DashboardOverviewResponse.ServiceHealthCard::environmentId)
                .thenComparing(DashboardOverviewResponse.ServiceHealthCard::service));
        return List.copyOf(cards);
    }

    private String resolveHealthStatus(HealthSnapshotEntity snapshot, long activeCount, IncidentSeverity highestSeverity) {
        if (snapshot != null && snapshot.getSignals() != null) {
            Object raw = snapshot.getSignals().get("healthStatus");
            if (raw != null && !String.valueOf(raw).isBlank()) {
                return String.valueOf(raw);
            }
        }
        if (activeCount == 0) {
            return "HEALTHY";
        }
        if (highestSeverity == IncidentSeverity.CRITICAL || highestSeverity == IncidentSeverity.HIGH) {
            return "UNHEALTHY";
        }
        return "DEGRADED";
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
