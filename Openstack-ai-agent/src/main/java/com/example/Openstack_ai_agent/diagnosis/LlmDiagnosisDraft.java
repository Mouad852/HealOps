package com.example.Openstack_ai_agent.diagnosis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Structured output schema returned by the LLM diagnosis step (or produced by the deterministic fallback)
 * prior to strict evidence-citation and action-catalog validation.
 */
public record LlmDiagnosisDraft(
        String summary,
        BigDecimal confidence,
        List<HypothesisDraft> hypotheses,
        List<String> missingOrContradictoryEvidence,
        ActionRecommendationDraft recommendedAction
) {
    public record HypothesisDraft(
            String title,
            String description,
            BigDecimal confidence,
            List<String> evidenceIds,
            String ruleCode
    ) {
    }

    public record ActionRecommendationDraft(
            String actionType,
            Map<String, Object> arguments
    ) {
    }
}
