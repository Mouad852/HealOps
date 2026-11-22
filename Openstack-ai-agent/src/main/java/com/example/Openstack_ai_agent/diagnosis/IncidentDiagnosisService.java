package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Coordinates the diagnosis pipeline WITHOUT holding an open database transaction during LLM I/O:
 * 1. Deterministic rule evaluation over persisted evidence.
 * 2. BEFORE health snapshot capture (short transaction).
 * 3. Optional LLM structured diagnosis call (outside any DB transaction) + strict validation.
 * 4. Short-transaction persistence of DiagnosisEntity, ProposedActionEntity, and approval.created dashboard event.
 */
@Service
public class IncidentDiagnosisService {
    private static final Logger log = LoggerFactory.getLogger(IncidentDiagnosisService.class);

    private final DeterministicDiagnosticsEngine deterministicEngine;
    private final HealthSnapshotService healthSnapshotService;
    private final LlmDiagnosisService llmDiagnosisService;
    private final DiagnosisValidationService validationService;
    private final DiagnosisPersistenceService persistenceService;

    public IncidentDiagnosisService(
            DeterministicDiagnosticsEngine deterministicEngine,
            HealthSnapshotService healthSnapshotService,
            LlmDiagnosisService llmDiagnosisService,
            DiagnosisValidationService validationService,
            DiagnosisPersistenceService persistenceService
    ) {
        this.deterministicEngine = deterministicEngine;
        this.healthSnapshotService = healthSnapshotService;
        this.llmDiagnosisService = llmDiagnosisService;
        this.validationService = validationService;
        this.persistenceService = persistenceService;
    }

    public DiagnosisExecutionResult diagnoseAndPropose(
            IncidentResponse incident,
            List<EvidenceItemResponse> evidence,
            CorrelationService.CorrelationSummary correlation
    ) {
        // 1. Run deterministic diagnostics first (pure in-memory, no DB tx)
        DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult deterministic =
                deterministicEngine.diagnose(incident, evidence, correlation);

        // 2. Capture BEFORE health snapshot before proposing remediation (short DB tx)
        HealthSnapshotResponse beforeSnapshot = healthSnapshotService.captureBeforeSnapshot(
                incident,
                evidence,
                correlation,
                deterministic
        );

        Set<UUID> validEvidenceIds = evidence.stream()
                .map(EvidenceItemResponse::id)
                .collect(Collectors.toSet());

        // 3. Optional LLM diagnosis outside any transaction, strictly validated with deterministic fallback
        DiagnosisValidationService.ValidatedDiagnosis validated = evaluateAndValidate(
                incident,
                evidence,
                correlation,
                deterministic,
                validEvidenceIds
        );

        // 4. Persist diagnosis + optional proposed action + approval.created dashboard event (short DB tx)
        DiagnosisPersistenceService.PersistedDiagnosisResult persisted =
                persistenceService.persist(incident, validated);

        return new DiagnosisExecutionResult(
                beforeSnapshot,
                persisted.diagnosis(),
                persisted.proposedAction()
        );
    }

    private DiagnosisValidationService.ValidatedDiagnosis evaluateAndValidate(
            IncidentResponse incident,
            List<EvidenceItemResponse> evidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult deterministic,
            Set<UUID> validEvidenceIds
    ) {
        if (!llmDiagnosisService.isEnabled()) {
            return validationService.validate(
                    deterministic.toDraft(),
                    validEvidenceIds,
                    "deterministic-only",
                    deterministic.ruleVersion()
            );
        }

        try {
            LlmDiagnosisDraft llmDraft = llmDiagnosisService.generateDraft(
                    incident,
                    evidence,
                    correlation,
                    deterministic
            );
            return validationService.validate(
                    llmDraft,
                    validEvidenceIds,
                    llmDiagnosisService.modelVersion(),
                    deterministic.ruleVersion()
            );
        } catch (DiagnosisValidationException exception) {
            log.warn("Rejected LLM diagnosis for incident {}: {}. Falling back to deterministic diagnosis.",
                    incident.id(), exception.getMessage());
            return validationService.validate(
                    deterministic.toDraft(),
                    validEvidenceIds,
                    "deterministic-fallback:llm-rejected",
                    deterministic.ruleVersion()
            );
        } catch (RuntimeException exception) {
            log.warn("LLM diagnosis unavailable for incident {}: {}. Falling back to deterministic diagnosis.",
                    incident.id(), exception.getMessage());
            return validationService.validate(
                    deterministic.toDraft(),
                    validEvidenceIds,
                    "deterministic-fallback:llm-unavailable",
                    deterministic.ruleVersion()
            );
        }
    }

    public record DiagnosisExecutionResult(
            HealthSnapshotResponse beforeSnapshot,
            DiagnosisResponse diagnosis,
            ProposedActionResponse proposedAction
    ) {
    }
}
