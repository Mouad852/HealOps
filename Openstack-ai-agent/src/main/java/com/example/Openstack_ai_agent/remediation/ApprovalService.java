package com.example.Openstack_ai_agent.remediation;

import com.example.Openstack_ai_agent.audit.AuditOutcome;
import com.example.Openstack_ai_agent.audit.AuditService;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentWorkflowEvent;
import com.example.Openstack_ai_agent.incident.TransitionIncidentRequest;
import com.example.Openstack_ai_agent.observability.DashboardEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ApprovalService {
    private static final String ENV_AUTHORITY_PREFIX = "env:";

    private final ProposedActionRepository proposedActionRepository;
    private final ApprovalDecisionRepository approvalDecisionRepository;
    private final IncidentService incidentService;
    private final DashboardEventPublisher dashboardEventPublisher;
    private final PolicyEvaluator policyEvaluator;
    private final ActionCatalogValidator actionCatalogValidator;
    private final AuditService auditService;

    public ApprovalService(
            ProposedActionRepository proposedActionRepository,
            ApprovalDecisionRepository approvalDecisionRepository,
            IncidentService incidentService,
            DashboardEventPublisher dashboardEventPublisher,
            PolicyEvaluator policyEvaluator,
            ActionCatalogValidator actionCatalogValidator,
            AuditService auditService
    ) {
        this.proposedActionRepository = proposedActionRepository;
        this.approvalDecisionRepository = approvalDecisionRepository;
        this.incidentService = incidentService;
        this.dashboardEventPublisher = dashboardEventPublisher;
        this.policyEvaluator = policyEvaluator;
        this.actionCatalogValidator = actionCatalogValidator;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ApprovalQueueItemResponse> listApprovals(
            String environmentId,
            String rawStatus,
            Authentication authentication
    ) {
        Set<String> authorizedEnvironments = extractAuthorizedEnvironments(authentication);
        if (authorizedEnvironments.isEmpty()) {
            return List.of();
        }
        if (environmentId != null && !environmentId.isBlank() && !authorizedEnvironments.contains(environmentId.trim())) {
            return List.of();
        }

        ProposedActionStatus statusFilter = parseStatusFilter(rawStatus);
        List<ProposedActionEntity> candidates = statusFilter != null
                ? proposedActionRepository.findByStatusOrderByCreatedAtDesc(statusFilter)
                : proposedActionRepository.findAll().stream()
                        .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                        .toList();

        List<ApprovalQueueItemResponse> results = new ArrayList<>();
        for (ProposedActionEntity action : candidates) {
            IncidentResponse incident = incidentService.findById(action.getIncidentId());
            if (!authorizedEnvironments.contains(incident.environmentId())) {
                continue;
            }
            if (environmentId != null && !environmentId.isBlank() && !environmentId.trim().equals(incident.environmentId())) {
                continue;
            }
            ApprovalDecisionEntity latest = approvalDecisionRepository
                    .findByActionIdOrderByDecidedAtDesc(action.getId())
                    .stream()
                    .findFirst()
                    .orElse(null);
            results.add(ApprovalQueueItemResponse.from(
                    action,
                    incident,
                    actionCatalogValidator,
                    latest,
                    policyEvaluator.policyVersion()
            ));
        }
        return List.copyOf(results);
    }

    @Transactional
    public DecisionSubmissionResult submitDecision(
            UUID actionId,
            String idempotencyKeyHeader,
            SubmitApprovalDecisionRequest request,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new AccessDeniedException("Authenticated principal is required");
        }

        String actor = authentication.getName();
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        String effectiveIdempotencyKey = resolveIdempotencyKey(idempotencyKeyHeader, request.idempotencyKey());

        // Idempotent replay check before mutating state
        Optional<ApprovalDecisionEntity> existingDecision =
                approvalDecisionRepository.findByIdempotencyKey(effectiveIdempotencyKey);
        if (existingDecision.isPresent()) {
            ApprovalDecisionEntity existing = existingDecision.get();
            if (!existing.getActionId().equals(actionId)) {
                throw new ApprovalConflictException(
                        "Idempotency-Key '" + effectiveIdempotencyKey + "' is already bound to a different action"
                );
            }
            ProposedActionEntity existingAction = proposedActionRepository.findById(actionId)
                    .orElseThrow(() -> new ProposedActionNotFoundException(actionId));
            IncidentResponse incident = incidentService.findById(existingAction.getIncidentId());
            if (!authorities.contains(ENV_AUTHORITY_PREFIX + incident.environmentId())) {
                PolicyEvaluator.PolicyDecision denied = PolicyEvaluator.PolicyDecision.deny(
                        policyEvaluator.policyVersion(),
                        "RULE_ENVIRONMENT_SCOPE_DENIED",
                        "Caller is not authorized for environment '" + incident.environmentId() + "'",
                        Map.of("environmentId", incident.environmentId(), "actionId", actionId.toString())
                );
                auditService.record(
                        actor,
                        "APPROVAL_DECISION",
                        "proposed_action:" + actionId,
                        AuditOutcome.DENIED,
                        policyAuditDetails(actionId, incident, denied, effectiveIdempotencyKey, request.decision()),
                        incident.traceId()
                );
                throw new PolicyDeniedException(denied);
            }
            return new DecisionSubmissionResult(
                    ApprovalDecisionResponse.from(existing, existingAction, policyEvaluator.policyVersion()),
                    false
            );
        }

        ProposedActionEntity action = proposedActionRepository.findById(actionId)
                .orElseThrow(() -> new ProposedActionNotFoundException(actionId));
        IncidentResponse incident = incidentService.findById(action.getIncidentId());

        PolicyEvaluator.PolicyDecision policyDecision = policyEvaluator.evaluate(
                new PolicyEvaluator.PolicyEvaluationRequest(
                        incident.environmentId(),
                        action.getActionType(),
                        action.getArguments(),
                        action.getRiskLevel(),
                        incident.service(),
                        actor,
                        authorities,
                        request.decision()
                )
        );

        if (!policyDecision.allowed()) {
            auditService.record(
                    actor,
                    "APPROVAL_DECISION",
                    "proposed_action:" + actionId,
                    AuditOutcome.DENIED,
                    policyAuditDetails(actionId, incident, policyDecision, effectiveIdempotencyKey, request.decision()),
                    incident.traceId()
            );
            throw new PolicyDeniedException(policyDecision);
        }

        if (action.getStatus() != ProposedActionStatus.PROPOSED) {
            throw new ApprovalConflictException(
                    "Proposed action " + actionId + " has already been decided (current status: " + action.getStatus() + ")"
            );
        }
        if (action.getVersion() != request.expectedVersion()) {
            throw new ApprovalConflictException(
                    "Proposed action " + actionId + " version conflict: expected "
                            + request.expectedVersion() + " but current version is " + action.getVersion()
            );
        }
        if (incident.status() != IncidentStatus.AWAITING_APPROVAL) {
            throw new ApprovalConflictException(
                    "Incident " + incident.id() + " is not awaiting approval (current status: " + incident.status() + ")"
            );
        }

        if (request.decision() == ApprovalDecisionType.APPROVED) {
            action.markApproved();
        } else {
            action.markRejected();
        }
        ProposedActionEntity savedAction = proposedActionRepository.saveAndFlush(action);

        ApprovalDecisionEntity savedDecision = approvalDecisionRepository.saveAndFlush(
                ApprovalDecisionEntity.create(
                        actionId,
                        request.decision(),
                        actor,
                        request.reason(),
                        request.expectedVersion(),
                        effectiveIdempotencyKey
                )
        );

        Map<String, Object> dashboardPayload = new LinkedHashMap<>();
        dashboardPayload.put("actionId", savedAction.getId().toString());
        dashboardPayload.put("decisionId", savedDecision.getId().toString());
        dashboardPayload.put("incidentId", incident.id().toString());
        dashboardPayload.put("actionType", savedAction.getActionType().name());
        dashboardPayload.put("decision", savedDecision.getDecision().name());
        dashboardPayload.put("actor", actor);
        dashboardPayload.put("status", savedAction.getStatus().name());
        dashboardPayload.put("version", savedAction.getVersion());
        dashboardPayload.put("policyVersion", policyDecision.policyVersion());
        dashboardPayload.put("ruleId", policyDecision.ruleId());
        dashboardEventPublisher.publish(
                incident.environmentId(),
                "approval.updated",
                incident.id(),
                incident.traceId(),
                dashboardPayload
        );

        Map<String, String> transitionMetadata = new LinkedHashMap<>();
        transitionMetadata.put("actionId", savedAction.getId().toString());
        transitionMetadata.put("decisionId", savedDecision.getId().toString());
        transitionMetadata.put("actionType", savedAction.getActionType().name());
        transitionMetadata.put("policyVersion", policyDecision.policyVersion());
        transitionMetadata.put("ruleId", policyDecision.ruleId());
        transitionMetadata.put("idempotencyKey", effectiveIdempotencyKey);

        IncidentWorkflowEvent workflowEvent = request.decision() == ApprovalDecisionType.APPROVED
                ? IncidentWorkflowEvent.ACTION_APPROVED
                : IncidentWorkflowEvent.ACTION_REJECTED;
        String transitionReason = request.reason() != null && !request.reason().isBlank()
                ? request.reason().trim()
                : "Operator " + actor + " " + request.decision().name().toLowerCase(Locale.ROOT)
                + " action " + savedAction.getActionType();

        incidentService.transition(incident.id(), new TransitionIncidentRequest(
                workflowEvent,
                null,
                actor,
                transitionReason,
                incident.traceId(),
                Map.copyOf(transitionMetadata),
                incident.version()
        ));

        auditService.record(
                actor,
                "APPROVAL_DECISION",
                "proposed_action:" + actionId,
                AuditOutcome.SUCCESS,
                policyAuditDetails(actionId, incident, policyDecision, effectiveIdempotencyKey, request.decision()),
                incident.traceId()
        );

        return new DecisionSubmissionResult(
                ApprovalDecisionResponse.from(savedDecision, savedAction, policyDecision.policyVersion()),
                true
        );
    }

    private Map<String, Object> policyAuditDetails(
            UUID actionId,
            IncidentResponse incident,
            PolicyEvaluator.PolicyDecision decision,
            String idempotencyKey,
            ApprovalDecisionType requestedDecision
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("actionId", actionId.toString());
        details.put("incidentId", incident.id().toString());
        details.put("environmentId", incident.environmentId());
        details.put("decision", requestedDecision.name());
        details.put("policyVersion", decision.policyVersion());
        details.put("ruleId", decision.ruleId());
        details.put("reason", decision.reason());
        details.put("idempotencyKey", idempotencyKey);
        return details;
    }

    private String resolveIdempotencyKey(String headerValue, String bodyValue) {
        String key = (headerValue != null && !headerValue.isBlank()) ? headerValue.trim() : bodyValue;
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header or idempotencyKey request field is required");
        }
        String trimmed = key.trim();
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key exceeds maximum length of 200 characters");
        }
        return trimmed;
    }

    private ProposedActionStatus parseStatusFilter(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return ProposedActionStatus.PROPOSED;
        }
        String normalized = rawStatus.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return null;
        }
        if ("PENDING".equals(normalized)) {
            return ProposedActionStatus.PROPOSED;
        }
        try {
            return ProposedActionStatus.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported approval status filter: " + rawStatus);
        }
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

    public record DecisionSubmissionResult(ApprovalDecisionResponse response, boolean created) {
    }
}
