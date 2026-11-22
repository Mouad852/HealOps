package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.remediation.ActionCatalogValidationException;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ValidatedActionProposal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Strictly validates every diagnosis before persistence:
 * - Rejects hypotheses citing nonexistent or malformed evidence_items UUIDs.
 * - Rejects missing citations when persisted evidence exists.
 * - Rejects unsupported action types or out-of-range/invalid parameters.
 */
@Component
public class DiagnosisValidationService {
    private static final BigDecimal MIN_CONFIDENCE = BigDecimal.ZERO;
    private static final BigDecimal MAX_CONFIDENCE = BigDecimal.ONE;

    private final ActionCatalogValidator actionCatalogValidator;

    public DiagnosisValidationService(ActionCatalogValidator actionCatalogValidator) {
        this.actionCatalogValidator = actionCatalogValidator;
    }

    public ValidatedDiagnosis validate(
            LlmDiagnosisDraft draft,
            Set<UUID> validEvidenceIds,
            String modelVersion,
            String ruleVersion
    ) {
        if (draft == null) {
            throw new DiagnosisValidationException("Diagnosis draft cannot be null");
        }
        if (draft.summary() == null || draft.summary().isBlank()) {
            throw new DiagnosisValidationException("Diagnosis summary is required");
        }
        BigDecimal confidence = validateConfidence(draft.confidence(), "diagnosis confidence");

        if (draft.hypotheses() == null || draft.hypotheses().isEmpty()) {
            throw new DiagnosisValidationException("Diagnosis must include at least one evidence-backed hypothesis");
        }

        Set<UUID> allowedIds = validEvidenceIds == null ? Set.of() : validEvidenceIds;
        Set<UUID> citedIds = new LinkedHashSet<>();
        List<DiagnosisHypothesis> validatedHypotheses = new ArrayList<>();

        for (LlmDiagnosisDraft.HypothesisDraft h : draft.hypotheses()) {
            if (h == null) {
                throw new DiagnosisValidationException("Hypothesis entry cannot be null");
            }
            if (h.title() == null || h.title().isBlank()) {
                throw new DiagnosisValidationException("Hypothesis title is required");
            }
            if (h.description() == null || h.description().isBlank()) {
                throw new DiagnosisValidationException("Hypothesis description is required");
            }
            BigDecimal hypothesisConfidence = validateConfidence(h.confidence(), "hypothesis confidence");

            List<String> rawEvidenceIds = h.evidenceIds();
            if (!allowedIds.isEmpty() && (rawEvidenceIds == null || rawEvidenceIds.isEmpty())) {
                throw new DiagnosisValidationException(
                        "Hypothesis '" + h.title() + "' must cite at least one persisted evidence ID"
                );
            }

            List<UUID> parsedHypothesisIds = new ArrayList<>();
            if (rawEvidenceIds != null) {
                for (String rawId : rawEvidenceIds) {
                    if (rawId == null || rawId.isBlank()) {
                        throw new DiagnosisValidationException("Hypothesis '" + h.title() + "' contains a blank evidence ID");
                    }
                    UUID parsed;
                    try {
                        parsed = UUID.fromString(rawId.trim());
                    } catch (IllegalArgumentException exception) {
                        throw new DiagnosisValidationException(
                                "Hypothesis '" + h.title() + "' cites invalid UUID '" + rawId + "'",
                                exception
                        );
                    }
                    if (!allowedIds.contains(parsed)) {
                        throw new DiagnosisValidationException(
                                "Hypothesis '" + h.title() + "' cites nonexistent evidence ID: " + parsed
                        );
                    }
                    parsedHypothesisIds.add(parsed);
                    citedIds.add(parsed);
                }
            }

            validatedHypotheses.add(new DiagnosisHypothesis(
                    h.title().trim(),
                    h.description().trim(),
                    hypothesisConfidence,
                    List.copyOf(new LinkedHashSet<>(parsedHypothesisIds)),
                    h.ruleCode() == null || h.ruleCode().isBlank() ? "LLM_SYNTHESIS" : h.ruleCode().trim()
            ));
        }

        if (!allowedIds.isEmpty() && citedIds.isEmpty()) {
            throw new DiagnosisValidationException("Diagnosis must cite at least one persisted evidence ID");
        }

        if (draft.recommendedAction() == null) {
            throw new DiagnosisValidationException("Diagnosis must include a recommended action (use NO_ACTION if none required)");
        }

        ValidatedActionProposal validatedAction;
        try {
            validatedAction = actionCatalogValidator.validateAndNormalize(
                    draft.recommendedAction().actionType(),
                    draft.recommendedAction().arguments()
            );
        } catch (ActionCatalogValidationException exception) {
            throw new DiagnosisValidationException("Invalid recommended action: " + exception.getMessage(), exception);
        }

        List<String> missingOrContradictory = draft.missingOrContradictoryEvidence() == null
                ? List.of()
                : draft.missingOrContradictoryEvidence().stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(String::trim)
                .toList();

        return new ValidatedDiagnosis(
                draft.summary().trim(),
                confidence,
                List.copyOf(validatedHypotheses),
                List.copyOf(citedIds),
                validatedAction,
                missingOrContradictory,
                modelVersion == null || modelVersion.isBlank() ? "unknown" : modelVersion.trim(),
                ruleVersion == null || ruleVersion.isBlank() ? DeterministicDiagnosticsEngine.RULE_VERSION : ruleVersion.trim()
        );
    }

    private BigDecimal validateConfidence(BigDecimal value, String label) {
        if (value == null) {
            throw new DiagnosisValidationException("Missing " + label);
        }
        if (value.compareTo(MIN_CONFIDENCE) < 0 || value.compareTo(MAX_CONFIDENCE) > 0) {
            throw new DiagnosisValidationException(
                    "Invalid " + label + " " + value + ": must be between 0.00 and 1.00"
            );
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record ValidatedDiagnosis(
            String summary,
            BigDecimal confidence,
            List<DiagnosisHypothesis> hypotheses,
            List<UUID> citedEvidenceIds,
            ValidatedActionProposal actionProposal,
            List<String> missingOrContradictoryEvidence,
            String modelVersion,
            String ruleVersion
    ) {
    }
}
