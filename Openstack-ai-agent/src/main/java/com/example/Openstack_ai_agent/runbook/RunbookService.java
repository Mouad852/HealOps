package com.example.Openstack_ai_agent.runbook;

import com.example.Openstack_ai_agent.audit.AuditOutcome;
import com.example.Openstack_ai_agent.audit.AuditService;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisEntity;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisHypothesis;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository;
import com.example.Openstack_ai_agent.diagnosis.HealthComparisonResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotService;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.remediation.ProposedActionEntity;
import com.example.Openstack_ai_agent.remediation.ProposedActionRepository;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeEntity;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeRepository;
import com.example.Openstack_ai_agent.remediation.ValidatedActionProposal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class RunbookService {
    private final RunbookRepository runbookRepository;
    private final RunbookVersionRepository runbookVersionRepository;
    private final IncidentService incidentService;
    private final IncidentRepository incidentRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final ProposedActionRepository proposedActionRepository;
    private final HealthSnapshotService healthSnapshotService;
    private final RemediationOutcomeRepository remediationOutcomeRepository;
    private final ActionCatalogValidator actionCatalogValidator;
    private final RunbookMatchingEngine matchingEngine;
    private final AuditService auditService;

    public RunbookService(
            RunbookRepository runbookRepository,
            RunbookVersionRepository runbookVersionRepository,
            IncidentService incidentService,
            IncidentRepository incidentRepository,
            DiagnosisRepository diagnosisRepository,
            ProposedActionRepository proposedActionRepository,
            HealthSnapshotService healthSnapshotService,
            RemediationOutcomeRepository remediationOutcomeRepository,
            ActionCatalogValidator actionCatalogValidator,
            RunbookMatchingEngine matchingEngine,
            AuditService auditService
    ) {
        this.runbookRepository = runbookRepository;
        this.runbookVersionRepository = runbookVersionRepository;
        this.incidentService = incidentService;
        this.incidentRepository = incidentRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.proposedActionRepository = proposedActionRepository;
        this.healthSnapshotService = healthSnapshotService;
        this.remediationOutcomeRepository = remediationOutcomeRepository;
        this.actionCatalogValidator = actionCatalogValidator;
        this.matchingEngine = matchingEngine;
        this.auditService = auditService;
    }

    @Transactional
    public RunbookDetailResponse draftFromIncident(
            UUID incidentId,
            DraftRunbookFromIncidentRequest request,
            Authentication authentication
    ) {
        String actor = requireAuthenticatedActor(authentication);
        IncidentResponse incident = incidentService.findById(incidentId);
        if (incident.status() != IncidentStatus.CLOSED) {
            throw new RunbookConflictException(
                    "Runbook drafts can only be created from CLOSED incidents (incident "
                            + incidentId + " is " + incident.status() + ")"
            );
        }

        List<ProposedActionEntity> actions = proposedActionRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
        ProposedActionEntity primaryAction = actions.stream()
                .filter(action -> action.getActionType() != ActionType.NO_ACTION)
                .findFirst()
                .orElseThrow(() -> new RunbookConflictException(
                        "Incident " + incidentId + " has no validated remediation action to draft a runbook from"
                ));

        ValidatedActionProposal validated = actionCatalogValidator.validateAndNormalize(
                primaryAction.getActionType(),
                primaryAction.getArguments()
        );

        RunbookStep initialStep = new RunbookStep(
                1,
                validated.actionType(),
                validated.arguments(),
                "Validated remediation derived from closed incident " + incidentId,
                validated.commandPreview(),
                validated.riskLevel(),
                validated.rollbackStrategy()
        );

        Set<String> findingCodes = extractFindingCodes(incidentId);

        Map<String, Object> matchCriteria = new LinkedHashMap<>();
        matchCriteria.put("service", incident.service());
        matchCriteria.put("environmentId", incident.environmentId());
        matchCriteria.put("targetLabels", incident.labels() == null ? Map.of() : new TreeMap<>(incident.labels()));
        matchCriteria.put("findingCodes", List.copyOf(findingCodes));
        matchCriteria.put("actionType", validated.actionType().name());
        matchCriteria.put("sourceIncidentId", incidentId.toString());

        Map<String, Object> policyRequirements = buildPolicyRequirements(
                request != null ? request.policyRequirements() : null,
                incident.environmentId(),
                validated.riskLevel().name()
        );

        String runbookName = (request != null && request.name() != null && !request.name().isBlank())
                ? request.name().trim()
                : incident.service() + " - " + validated.actionType().name();

        RunbookEntity runbook = runbookRepository.saveAndFlush(
                RunbookEntity.createDraft(runbookName, matchCriteria)
        );
        RunbookVersionEntity version = runbookVersionRepository.saveAndFlush(
                RunbookVersionEntity.create(
                        runbook.getId(),
                        1,
                        List.of(initialStep),
                        policyRequirements,
                        actor
                )
        );

        return RunbookDetailResponse.from(runbook, List.of(version));
    }

    @Transactional
    public RunbookDetailResponse updateRunbook(
            UUID runbookId,
            UpdateRunbookRequest request,
            Authentication authentication
    ) {
        String actor = requireAuthenticatedActor(authentication);
        RunbookEntity runbook = runbookRepository.findById(runbookId)
                .orElseThrow(() -> new RunbookNotFoundException(runbookId));

        List<RunbookStep> validatedSteps = validateSteps(request.typedSteps());

        Map<String, Object> mergedCriteria = request.matchCriteria() != null
                ? sanitizeMatchCriteria(request.matchCriteria())
                : runbook.getMatchCriteria();

        int nextVersionNumber = runbook.applyNewDraftVersion(request.name(), mergedCriteria);
        RunbookEntity savedRunbook = runbookRepository.saveAndFlush(runbook);

        String highestRisk = validatedSteps.stream()
                .map(RunbookStep::riskLevel)
                .max(java.util.Comparator.comparingInt(Enum::ordinal))
                .map(Enum::name)
                .orElse("MEDIUM");

        Map<String, Object> policyRequirements = buildPolicyRequirements(
                request.policyRequirements(),
                mergedCriteria.get("environmentId") != null ? String.valueOf(mergedCriteria.get("environmentId")) : null,
                highestRisk
        );

        runbookVersionRepository.saveAndFlush(RunbookVersionEntity.create(
                savedRunbook.getId(),
                nextVersionNumber,
                validatedSteps,
                policyRequirements,
                actor
        ));

        List<RunbookVersionEntity> versions = runbookVersionRepository.findByRunbookIdOrderByVersionNumberDesc(runbookId);
        return RunbookDetailResponse.from(savedRunbook, versions);
    }

    @Transactional
    public RunbookDetailResponse publishRunbook(UUID runbookId, Authentication authentication) {
        String publisher = requireAuthenticatedActor(authentication);
        RunbookEntity runbook = runbookRepository.findById(runbookId)
                .orElseThrow(() -> new RunbookNotFoundException(runbookId));

        RunbookVersionEntity currentVersion = runbookVersionRepository
                .findByRunbookIdAndVersionNumber(runbookId, runbook.getCurrentVersion())
                .orElseThrow(() -> new RunbookConflictException(
                        "Current version " + runbook.getCurrentVersion() + " missing for runbook " + runbookId
                ));

        if (currentVersion.getTypedSteps() == null || currentVersion.getTypedSteps().isEmpty()) {
            throw new RunbookConflictException("Runbook " + runbookId + " has no validated typed steps to publish");
        }
        // Re-validate every persisted step against the allowlisted action catalog before publication
        for (RunbookStep step : currentVersion.getTypedSteps()) {
            actionCatalogValidator.validateAndNormalize(step.actionType(), step.arguments());
        }

        runbook.markPublished();
        currentVersion.stampPublishedBy(publisher);

        RunbookEntity savedRunbook = runbookRepository.saveAndFlush(runbook);
        runbookVersionRepository.saveAndFlush(currentVersion);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("runbookId", savedRunbook.getId().toString());
        auditDetails.put("runbookName", savedRunbook.getName());
        auditDetails.put("versionNumber", currentVersion.getVersionNumber());
        auditDetails.put("publishedBy", publisher);
        auditDetails.put("stepCount", currentVersion.getTypedSteps().size());
        auditDetails.put("actionTypes", currentVersion.getTypedSteps().stream()
                .map(step -> step.actionType().name())
                .toList());
        auditService.record(
                publisher,
                "RUNBOOK_PUBLISHED",
                "runbook:" + savedRunbook.getId(),
                AuditOutcome.SUCCESS,
                auditDetails,
                null
        );

        List<RunbookVersionEntity> versions = runbookVersionRepository.findByRunbookIdOrderByVersionNumberDesc(runbookId);
        return RunbookDetailResponse.from(savedRunbook, versions);
    }

    @Transactional(readOnly = true)
    public List<RunbookDetailResponse> listRunbooks(RunbookStatus status) {
        List<RunbookEntity> runbooks = status != null
                ? runbookRepository.findByStatusOrderByUpdatedAtDesc(status)
                : runbookRepository.findAllByOrderByUpdatedAtDesc();
        List<RunbookDetailResponse> responses = new ArrayList<>();
        for (RunbookEntity runbook : runbooks) {
            List<RunbookVersionEntity> versions = runbookVersionRepository
                    .findByRunbookIdOrderByVersionNumberDesc(runbook.getId());
            responses.add(RunbookDetailResponse.from(runbook, versions));
        }
        return List.copyOf(responses);
    }

    @Transactional(readOnly = true)
    public RunbookDetailResponse getRunbook(UUID runbookId) {
        RunbookEntity runbook = runbookRepository.findById(runbookId)
                .orElseThrow(() -> new RunbookNotFoundException(runbookId));
        List<RunbookVersionEntity> versions = runbookVersionRepository
                .findByRunbookIdOrderByVersionNumberDesc(runbookId);
        return RunbookDetailResponse.from(runbook, versions);
    }

    @Transactional(readOnly = true)
    public List<RunbookSuggestionResponse> suggestForIncident(UUID incidentId) {
        IncidentResponse incident = incidentService.findById(incidentId);
        Set<String> findingCodes = extractFindingCodes(incidentId);
        ActionType proposedActionType = proposedActionRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId)
                .stream()
                .map(ProposedActionEntity::getActionType)
                .filter(type -> type != ActionType.NO_ACTION)
                .findFirst()
                .orElse(null);

        List<RunbookEntity> published = runbookRepository.findByStatusOrderByUpdatedAtDesc(RunbookStatus.PUBLISHED);
        List<RunbookMatchingEngine.PublishedRunbookCandidate> candidates = new ArrayList<>();
        for (RunbookEntity runbook : published) {
            runbookVersionRepository.findByRunbookIdAndVersionNumber(runbook.getId(), runbook.getCurrentVersion())
                    .ifPresent(version -> candidates.add(
                            new RunbookMatchingEngine.PublishedRunbookCandidate(runbook, version)
                    ));
        }

        List<RemediationOutcomeEntity> allOutcomes = remediationOutcomeRepository.findAll();
        Map<UUID, IncidentEntity> incidentsById = new LinkedHashMap<>();
        if (!allOutcomes.isEmpty()) {
            Set<UUID> outcomeIncidentIds = allOutcomes.stream()
                    .map(RemediationOutcomeEntity::getIncidentId)
                    .collect(java.util.stream.Collectors.toSet());
            incidentRepository.findAllById(outcomeIncidentIds)
                    .forEach(inc -> incidentsById.put(inc.getId(), inc));
        }
        List<RunbookMatchingEngine.HistoricalOutcomeRecord> historicalRecords = allOutcomes.stream()
                .map(outcome -> {
                    IncidentEntity inc = incidentsById.get(outcome.getIncidentId());
                    return new RunbookMatchingEngine.HistoricalOutcomeRecord(
                            inc != null ? inc.getService() : null,
                            inc != null ? inc.getEnvironmentId() : null,
                            outcome.getActionType(),
                            outcome.isSuccess(),
                            outcome.isRollbackTriggered()
                    );
                })
                .toList();

        return matchingEngine.rankSuggestions(
                new RunbookMatchingEngine.IncidentMatchContext(
                        incident.service(),
                        incident.environmentId(),
                        incident.labels(),
                        findingCodes,
                        proposedActionType
                ),
                candidates,
                historicalRecords
        );
    }

    private List<RunbookStep> validateSteps(List<RunbookStepRequest> requestedSteps) {
        List<RunbookStep> validated = new ArrayList<>();
        int order = 1;
        for (RunbookStepRequest stepReq : requestedSteps) {
            ValidatedActionProposal proposal = actionCatalogValidator.validateAndNormalize(
                    stepReq.actionType(),
                    stepReq.arguments()
            );
            String description = stepReq.description() != null && !stepReq.description().isBlank()
                    ? stepReq.description().trim()
                    : proposal.commandPreview();
            validated.add(new RunbookStep(
                    order++,
                    proposal.actionType(),
                    proposal.arguments(),
                    description,
                    proposal.commandPreview(),
                    proposal.riskLevel(),
                    proposal.rollbackStrategy()
            ));
        }
        return List.copyOf(validated);
    }

    private Set<String> extractFindingCodes(UUID incidentId) {
        Set<String> codes = new LinkedHashSet<>();
        for (DiagnosisEntity diagnosis : diagnosisRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId)) {
            if (diagnosis.getHypotheses() != null) {
                for (DiagnosisHypothesis hypothesis : diagnosis.getHypotheses()) {
                    if (hypothesis.ruleCode() != null && !hypothesis.ruleCode().isBlank()) {
                        codes.add(hypothesis.ruleCode().trim());
                    }
                }
            }
        }
        HealthComparisonResponse health = healthSnapshotService.getHealthComparisons(incidentId);
        if (health.before() != null && health.before().signals() != null) {
            Object rawFindings = health.before().signals().get("activeFindings");
            if (rawFindings instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        codes.add(String.valueOf(item).trim());
                    }
                }
            }
        }
        return codes;
    }

    private Map<String, Object> sanitizeMatchCriteria(Map<String, Object> raw) {
        Map<String, Object> clean = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                clean.put(key.trim(), value);
            }
        });
        return Map.copyOf(clean);
    }

    private Map<String, Object> buildPolicyRequirements(
            Map<String, Object> requested,
            String environmentId,
            String riskLevel
    ) {
        Map<String, Object> reqs = new LinkedHashMap<>();
        if (requested != null) {
            requested.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    reqs.put(key.trim(), value);
                }
            });
        }
        // Always require policy evaluation and operator approval; never allow auto-execution bypass
        reqs.put("requiresApproval", true);
        reqs.put("requiresPolicyEvaluation", true);
        reqs.putIfAbsent("riskLevel", riskLevel);
        if (environmentId != null && !environmentId.isBlank()) {
            reqs.putIfAbsent("environmentId", environmentId);
        }
        return Map.copyOf(reqs);
    }

    private String requireAuthenticatedActor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new AccessDeniedException("Authenticated principal is required");
        }
        return authentication.getName();
    }
}
