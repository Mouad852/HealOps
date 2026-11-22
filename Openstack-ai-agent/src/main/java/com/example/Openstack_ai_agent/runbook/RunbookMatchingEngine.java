package com.example.Openstack_ai_agent.runbook;

import com.example.Openstack_ai_agent.remediation.ActionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure relational/tag runbook matching engine.
 * Scores {@code PUBLISHED} runbooks against an incident's service, environment, target labels,
 * deterministic finding codes, proposed action type, and historical remediation outcomes.
 * Suggestions are strictly read-only proposals and never auto-publish or bypass approval/policy.
 */
@Component
public class RunbookMatchingEngine {

    public List<RunbookSuggestionResponse> rankSuggestions(
            IncidentMatchContext incident,
            List<PublishedRunbookCandidate> candidates,
            List<HistoricalOutcomeRecord> historicalOutcomes
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RunbookSuggestionResponse> suggestions = new ArrayList<>();
        for (PublishedRunbookCandidate candidate : candidates) {
            if (candidate.runbook().getStatus() != RunbookStatus.PUBLISHED || candidate.version() == null) {
                continue;
            }
            RunbookSuggestionResponse scored = scoreCandidate(incident, candidate, historicalOutcomes);
            if (scored != null && scored.matchScore() > 0.0) {
                suggestions.add(scored);
            }
        }

        suggestions.sort(Comparator
                .comparingDouble(RunbookSuggestionResponse::matchScore).reversed()
                .thenComparing(RunbookSuggestionResponse::name));
        return List.copyOf(suggestions);
    }

    private RunbookSuggestionResponse scoreCandidate(
            IncidentMatchContext incident,
            PublishedRunbookCandidate candidate,
            List<HistoricalOutcomeRecord> historicalOutcomes
    ) {
        RunbookEntity runbook = candidate.runbook();
        RunbookVersionEntity version = candidate.version();
        Map<String, Object> criteria = runbook.getMatchCriteria() == null ? Map.of() : runbook.getMatchCriteria();

        double score = 0.0;
        List<String> reasons = new ArrayList<>();

        // 1. Service match
        String criteriaService = asLowerString(criteria.get("service"));
        if (criteriaService != null && incident.service() != null
                && criteriaService.equals(incident.service().trim().toLowerCase(Locale.ROOT))) {
            score += 30.0;
            reasons.add("service=" + incident.service());
        }

        // 2. Environment match
        String criteriaEnv = asLowerString(criteria.get("environmentId"));
        if (criteriaEnv != null && incident.environmentId() != null
                && criteriaEnv.equals(incident.environmentId().trim().toLowerCase(Locale.ROOT))) {
            score += 10.0;
            reasons.add("environment=" + incident.environmentId());
        }

        // 3. Target labels overlap
        Map<String, String> criteriaLabels = extractStringMap(criteria.get("targetLabels"));
        if (!criteriaLabels.isEmpty() && incident.labels() != null && !incident.labels().isEmpty()) {
            int labelMatches = 0;
            for (Map.Entry<String, String> entry : criteriaLabels.entrySet()) {
                String actual = incident.labels().get(entry.getKey());
                if (actual != null && actual.equalsIgnoreCase(entry.getValue())) {
                    labelMatches++;
                    reasons.add("label:" + entry.getKey() + "=" + entry.getValue());
                }
            }
            score += Math.min(30.0, labelMatches * 10.0);
        }

        // 4. Deterministic finding codes overlap
        Set<String> criteriaFindings = extractUpperStringSet(criteria.get("findingCodes"));
        if (!criteriaFindings.isEmpty() && incident.findingCodes() != null && !incident.findingCodes().isEmpty()) {
            int findingMatches = 0;
            for (String code : incident.findingCodes()) {
                if (code != null && criteriaFindings.contains(code.trim().toUpperCase(Locale.ROOT))) {
                    findingMatches++;
                    reasons.add("finding:" + code.trim().toUpperCase(Locale.ROOT));
                }
            }
            score += Math.min(30.0, findingMatches * 15.0);
        }

        // 5. Action type match
        Set<ActionType> runbookActionTypes = new LinkedHashSet<>();
        String criteriaActionType = asLowerString(criteria.get("actionType"));
        if (criteriaActionType != null) {
            parseActionType(criteriaActionType).ifPresent(runbookActionTypes::add);
        }
        if (version.getTypedSteps() != null) {
            for (RunbookStep step : version.getTypedSteps()) {
                if (step.actionType() != null) {
                    runbookActionTypes.add(step.actionType());
                }
            }
        }
        if (incident.proposedActionType() != null && runbookActionTypes.contains(incident.proposedActionType())) {
            score += 20.0;
            reasons.add("actionType=" + incident.proposedActionType().name());
        }

        if (score <= 0.0) {
            return null;
        }

        // 6. Prior remediation outcomes adjustment (only when structural match > 0)
        OutcomeStats stats = computeOutcomeStats(incident.service(), runbookActionTypes, historicalOutcomes);
        if (stats.totalCount() > 0) {
            double bonus = (stats.successRate() - 0.5) * 20.0; // -10 to +10 based on historical success
            score = Math.max(0.0, score + bonus);
            reasons.add("priorOutcomes=" + stats.successCount() + "/" + stats.totalCount()
                    + " (" + Math.round(stats.successRate() * 100) + "% success)");
        }

        return new RunbookSuggestionResponse(
                runbook.getId(),
                runbook.getName(),
                version.getVersionNumber(),
                runbook.getStatus(),
                round2(score),
                List.copyOf(reasons),
                stats.totalCount() > 0 ? round4(stats.successRate()) : null,
                stats.totalCount(),
                criteria,
                version.getTypedSteps(),
                version.getPolicyRequirements(),
                true,
                true
        );
    }

    private OutcomeStats computeOutcomeStats(
            String service,
            Set<ActionType> actionTypes,
            List<HistoricalOutcomeRecord> historicalOutcomes
    ) {
        if (historicalOutcomes == null || historicalOutcomes.isEmpty() || actionTypes.isEmpty()) {
            return new OutcomeStats(0, 0, 0.0);
        }

        List<HistoricalOutcomeRecord> serviceAndAction = historicalOutcomes.stream()
                .filter(o -> actionTypes.contains(o.actionType()))
                .filter(o -> service != null && service.equalsIgnoreCase(o.service()))
                .toList();
        List<HistoricalOutcomeRecord> pool = !serviceAndAction.isEmpty()
                ? serviceAndAction
                : historicalOutcomes.stream().filter(o -> actionTypes.contains(o.actionType())).toList();

        if (pool.isEmpty()) {
            return new OutcomeStats(0, 0, 0.0);
        }
        long successes = pool.stream().filter(HistoricalOutcomeRecord::success).count();
        return new OutcomeStats(pool.size(), successes, (double) successes / pool.size());
    }

    private java.util.Optional<ActionType> parseActionType(String raw) {
        try {
            return java.util.Optional.of(ActionType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return java.util.Optional.empty();
        }
    }

    private String asLowerString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text.toLowerCase(Locale.ROOT);
    }

    private Map<String, String> extractStringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                String key = String.valueOf(k).trim();
                String val = String.valueOf(v).trim();
                if (!key.isEmpty() && !val.isEmpty()) {
                    copy.put(key, val);
                }
            }
        });
        return copy;
    }

    private Set<String> extractUpperStringSet(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null) {
                String val = String.valueOf(item).trim();
                if (!val.isEmpty()) {
                    set.add(val.toUpperCase(Locale.ROOT));
                }
            }
        }
        return set;
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    public record IncidentMatchContext(
            String service,
            String environmentId,
            Map<String, String> labels,
            Set<String> findingCodes,
            ActionType proposedActionType
    ) {
    }

    public record PublishedRunbookCandidate(
            RunbookEntity runbook,
            RunbookVersionEntity version
    ) {
    }

    public record HistoricalOutcomeRecord(
            String service,
            String environmentId,
            ActionType actionType,
            boolean success,
            boolean rollbackTriggered
    ) {
    }

    private record OutcomeStats(long totalCount, long successCount, double successRate) {
    }
}
