package com.example.Openstack_ai_agent.analytics;

import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentEventEntity;
import com.example.Openstack_ai_agent.incident.IncidentEventRepository;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.remediation.ApprovalDecisionEntity;
import com.example.Openstack_ai_agent.remediation.ApprovalDecisionRepository;
import com.example.Openstack_ai_agent.remediation.ProposedActionEntity;
import com.example.Openstack_ai_agent.remediation.ProposedActionRepository;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeEntity;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RemediationAnalyticsService {
    private static final String ENV_AUTHORITY_PREFIX = "env:";

    private final RemediationOutcomeRepository outcomeRepository;
    private final ApprovalDecisionRepository approvalDecisionRepository;
    private final ProposedActionRepository proposedActionRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentEventRepository incidentEventRepository;
    private final RemediationAnalyticsCalculator calculator;

    public RemediationAnalyticsService(
            RemediationOutcomeRepository outcomeRepository,
            ApprovalDecisionRepository approvalDecisionRepository,
            ProposedActionRepository proposedActionRepository,
            IncidentRepository incidentRepository,
            IncidentEventRepository incidentEventRepository,
            RemediationAnalyticsCalculator calculator
    ) {
        this.outcomeRepository = outcomeRepository;
        this.approvalDecisionRepository = approvalDecisionRepository;
        this.proposedActionRepository = proposedActionRepository;
        this.incidentRepository = incidentRepository;
        this.incidentEventRepository = incidentEventRepository;
        this.calculator = calculator;
    }

    @Transactional(readOnly = true)
    public RemediationAnalyticsResponse getRemediationAnalytics(
            String environmentId,
            Authentication authentication
    ) {
        Set<String> authorizedEnvs = extractAuthorizedEnvironments(authentication);
        String normalizedFilter = (environmentId == null || environmentId.isBlank()) ? null : environmentId.trim();

        Set<String> scopedEnvs;
        if (normalizedFilter != null) {
            scopedEnvs = authorizedEnvs.contains(normalizedFilter) ? Set.of(normalizedFilter) : Set.of();
        } else {
            scopedEnvs = authorizedEnvs;
        }

        if (scopedEnvs.isEmpty()) {
            return calculator.calculate(normalizedFilter, List.copyOf(authorizedEnvs), List.of(), List.of());
        }

        Map<UUID, IncidentEntity> scopedIncidents = incidentRepository.findAll().stream()
                .filter(incident -> scopedEnvs.contains(incident.getEnvironmentId()))
                .collect(Collectors.toMap(IncidentEntity::getId, incident -> incident));

        if (scopedIncidents.isEmpty()) {
            return calculator.calculate(normalizedFilter, List.copyOf(authorizedEnvs), List.of(), List.of());
        }

        List<RemediationOutcomeEntity> scopedOutcomes = outcomeRepository.findAll().stream()
                .filter(outcome -> scopedIncidents.containsKey(outcome.getIncidentId()))
                .toList();

        List<RemediationAnalyticsCalculator.OutcomeSample> outcomeSamples = new ArrayList<>();
        for (RemediationOutcomeEntity outcome : scopedOutcomes) {
            IncidentEntity incident = scopedIncidents.get(outcome.getIncidentId());
            Long recoveryTimeMs = resolveRecoveryTimeMs(incident, outcome);
            outcomeSamples.add(new RemediationAnalyticsCalculator.OutcomeSample(
                    outcome.getActionType(),
                    incident.getService(),
                    incident.getEnvironmentId(),
                    outcome.isSuccess(),
                    outcome.getDurationMs(),
                    recoveryTimeMs,
                    outcome.isRollbackTriggered(),
                    outcome.getRollbackSuccess()
            ));
        }

        Map<UUID, ProposedActionEntity> scopedActions = new LinkedHashMap<>();
        for (ProposedActionEntity action : proposedActionRepository.findAll()) {
            if (scopedIncidents.containsKey(action.getIncidentId())) {
                scopedActions.put(action.getId(), action);
            }
        }

        List<RemediationAnalyticsCalculator.ApprovalDelaySample> approvalDelays = new ArrayList<>();
        if (!scopedActions.isEmpty()) {
            for (ApprovalDecisionEntity decision : approvalDecisionRepository.findAll()) {
                ProposedActionEntity action = scopedActions.get(decision.getActionId());
                if (action != null && action.getCreatedAt() != null && decision.getDecidedAt() != null) {
                    long delayMs = Math.max(0L, Duration.between(action.getCreatedAt(), decision.getDecidedAt()).toMillis());
                    approvalDelays.add(new RemediationAnalyticsCalculator.ApprovalDelaySample(delayMs));
                }
            }
        }

        return calculator.calculate(
                normalizedFilter,
                List.copyOf(authorizedEnvs),
                outcomeSamples,
                approvalDelays
        );
    }

    private Long resolveRecoveryTimeMs(IncidentEntity incident, RemediationOutcomeEntity outcome) {
        if (!outcome.isSuccess()) {
            return null;
        }
        List<IncidentEventEntity> timeline = incidentEventRepository
                .findByIncidentIdOrderByEventSequenceAsc(incident.getId());
        OffsetDateTime closedAt = timeline.stream()
                .filter(event -> event.getToStatus() == IncidentStatus.CLOSED)
                .map(IncidentEventEntity::getOccurredAt)
                .reduce((first, second) -> second)
                .orElse(null);

        OffsetDateTime startAt = incident.getCreatedAt() != null
                ? incident.getCreatedAt()
                : (timeline.isEmpty() ? null : timeline.getFirst().getOccurredAt());

        if (startAt != null && closedAt != null) {
            return Math.max(0L, Duration.between(startAt, closedAt).toMillis());
        }
        return outcome.getDurationMs();
    }

    private Set<String> extractAuthorizedEnvironments(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(auth -> auth != null && auth.startsWith(ENV_AUTHORITY_PREFIX) && auth.length() > ENV_AUTHORITY_PREFIX.length())
                .map(auth -> auth.substring(ENV_AUTHORITY_PREFIX.length()))
                .collect(Collectors.toSet());
    }
}
