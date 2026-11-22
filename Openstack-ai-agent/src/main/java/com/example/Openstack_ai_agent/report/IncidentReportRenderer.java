package com.example.Openstack_ai_agent.report;

import com.example.Openstack_ai_agent.diagnosis.DiagnosisHypothesis;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthComparisonResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.incident.IncidentEventResponse;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.remediation.ApprovalDecisionEntity;
import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeEntity;
import com.example.Openstack_ai_agent.remediation.ToolExecutionResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Pure deterministic renderer that converts an incident's durable domain records into
 * a structured JSON map and a human-readable Markdown post-incident report.
 */
@Component
public class IncidentReportRenderer {
    private static final int MAX_EXCERPT_PREVIEW = 240;

    private final ActionCatalogValidator actionCatalogValidator;

    public IncidentReportRenderer(ActionCatalogValidator actionCatalogValidator) {
        this.actionCatalogValidator = actionCatalogValidator;
    }

    public RenderedIncidentReport render(IncidentReportInput input) {
        IncidentResponse incident = input.incident();
        String finalOutcome = incident.status().name();

        Map<String, Object> alertMetadata = buildAlertMetadata(incident);
        Map<String, Object> timelineSection = buildTimelineSection(input.timeline());
        Map<String, Object> evidenceSection = buildEvidenceSection(input.evidence());
        List<Map<String, Object>> diagnosesSection = buildDiagnosesSection(input.diagnoses());
        List<Map<String, Object>> actionsSection = buildProposedActionsSection(input.proposedActions());
        List<Map<String, Object>> approvalsSection = buildApprovalsSection(input.proposedActions(), input.approvals());
        List<Map<String, Object>> executionsSection = buildExecutionsSection(input.executions());
        Map<String, Object> healthSection = buildHealthComparisonSection(input.healthComparison());
        List<Map<String, Object>> outcomesSection = buildOutcomesSection(input.outcomes());
        Map<String, Object> runbookCandidate = buildRunbookCandidate(
                incident,
                input.diagnoses(),
                input.proposedActions(),
                input.healthComparison(),
                input.outcomes()
        );

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("reportVersion", "report-v1");
        structured.put("finalOutcome", finalOutcome);
        structured.put("alertMetadata", alertMetadata);
        structured.put("timeline", timelineSection);
        structured.put("evidenceInventory", evidenceSection);
        structured.put("diagnoses", diagnosesSection);
        structured.put("proposedActions", actionsSection);
        structured.put("approvalDecisions", approvalsSection);
        structured.put("toolExecutions", executionsSection);
        structured.put("healthComparison", healthSection);
        structured.put("remediationOutcomes", outcomesSection);
        structured.put("runbookCandidate", runbookCandidate);

        String markdown = renderMarkdown(
                incident,
                finalOutcome,
                timelineSection,
                evidenceSection,
                diagnosesSection,
                actionsSection,
                approvalsSection,
                executionsSection,
                healthSection,
                outcomesSection,
                runbookCandidate
        );

        return new RenderedIncidentReport(finalOutcome, Map.copyOf(structured), markdown);
    }

    private Map<String, Object> buildAlertMetadata(IncidentResponse incident) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("incidentId", incident.id().toString());
        map.put("source", incident.source());
        map.put("externalAlertId", incident.externalAlertId());
        map.put("service", incident.service());
        map.put("environmentId", incident.environmentId());
        map.put("severity", incident.severity().name());
        map.put("fingerprint", incident.fingerprint());
        map.put("traceId", incident.traceId());
        map.put("status", incident.status().name());
        map.put("startedAt", incident.startedAt() != null ? incident.startedAt().toString() : null);
        map.put("createdAt", incident.createdAt() != null ? incident.createdAt().toString() : null);
        map.put("labels", incident.labels() == null ? Map.of() : new TreeMap<>(incident.labels()));
        map.put("annotations", incident.annotations() == null ? Map.of() : new TreeMap<>(incident.annotations()));
        return map;
    }

    private Map<String, Object> buildTimelineSection(List<IncidentEventResponse> rawTimeline) {
        List<IncidentEventResponse> ordered = rawTimeline == null ? List.of() : rawTimeline.stream()
                .sorted(Comparator.comparingLong(IncidentEventResponse::sequence))
                .toList();

        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Long> phaseDurationsMs = new LinkedHashMap<>();
        long totalDurationMs = 0L;

        for (int i = 0; i < ordered.size(); i++) {
            IncidentEventResponse current = ordered.get(i);
            long deltaMs = 0L;
            if (i > 0 && ordered.get(i - 1).occurredAt() != null && current.occurredAt() != null) {
                deltaMs = Math.max(0L, Duration.between(ordered.get(i - 1).occurredAt(), current.occurredAt()).toMillis());
                IncidentStatus previousPhase = ordered.get(i - 1).toStatus();
                if (previousPhase != null) {
                    phaseDurationsMs.merge(previousPhase.name(), deltaMs, Long::sum);
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sequence", current.sequence());
            entry.put("fromStatus", current.fromStatus() != null ? current.fromStatus().name() : null);
            entry.put("toStatus", current.toStatus().name());
            entry.put("eventType", current.eventType().name());
            entry.put("actor", current.actor());
            entry.put("reason", current.reason());
            entry.put("traceId", current.traceId());
            entry.put("occurredAt", current.occurredAt() != null ? current.occurredAt().toString() : null);
            entry.put("durationFromPreviousMs", deltaMs);
            events.add(entry);
        }

        if (ordered.size() >= 2
                && ordered.getFirst().occurredAt() != null
                && ordered.getLast().occurredAt() != null) {
            totalDurationMs = Math.max(
                    0L,
                    Duration.between(ordered.getFirst().occurredAt(), ordered.getLast().occurredAt()).toMillis()
            );
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("eventCount", events.size());
        section.put("totalDurationMs", totalDurationMs);
        section.put("phaseDurationsMs", phaseDurationsMs);
        section.put("events", events);
        return section;
    }

    private Map<String, Object> buildEvidenceSection(List<EvidenceItemResponse> rawEvidence) {
        List<EvidenceItemResponse> ordered = rawEvidence == null ? List.of() : rawEvidence.stream()
                .sorted(Comparator.comparing(
                        EvidenceItemResponse::observedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ).thenComparing(item -> item.id().toString()))
                .toList();

        Map<String, Integer> countsByType = new TreeMap<>();
        int collectionErrorCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();

        for (EvidenceItemResponse item : ordered) {
            countsByType.merge(item.type().name(), 1, Integer::sum);
            if (item.type() == EvidenceType.COLLECTION_ERROR) {
                collectionErrorCount++;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.id().toString());
            entry.put("type", item.type().name());
            entry.put("source", item.source());
            entry.put("target", item.target());
            entry.put("observedAt", item.observedAt() != null ? item.observedAt().toString() : null);
            entry.put("traceId", item.traceId());
            entry.put("contentHash", item.contentHash());
            entry.put("redactionStatus", item.redactionStatus().name());
            entry.put("excerptPreview", preview(item.excerpt(), MAX_EXCERPT_PREVIEW));
            items.add(entry);
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("totalCount", items.size());
        section.put("collectionErrorCount", collectionErrorCount);
        section.put("countsByType", countsByType);
        section.put("items", items);
        return section;
    }

    private List<Map<String, Object>> buildDiagnosesSection(List<DiagnosisResponse> rawDiagnoses) {
        if (rawDiagnoses == null || rawDiagnoses.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (DiagnosisResponse diagnosis : rawDiagnoses) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", diagnosis.id().toString());
            entry.put("summary", diagnosis.summary());
            entry.put("confidence", diagnosis.confidence());
            entry.put("modelVersion", diagnosis.modelVersion());
            entry.put("ruleVersion", diagnosis.ruleVersion());
            entry.put("citedEvidenceIds", diagnosis.citedEvidenceIds().stream().map(UUID::toString).toList());
            entry.put("createdAt", diagnosis.createdAt() != null ? diagnosis.createdAt().toString() : null);

            List<Map<String, Object>> hypotheses = new ArrayList<>();
            if (diagnosis.hypotheses() != null) {
                for (DiagnosisHypothesis hypothesis : diagnosis.hypotheses()) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("title", hypothesis.title());
                    h.put("description", hypothesis.description());
                    h.put("confidence", hypothesis.confidence());
                    h.put("ruleCode", hypothesis.ruleCode());
                    h.put("evidenceIds", hypothesis.evidenceIds() == null
                            ? List.of()
                            : hypothesis.evidenceIds().stream().map(UUID::toString).toList());
                    hypotheses.add(h);
                }
            }
            entry.put("hypotheses", hypotheses);
            result.add(entry);
        }
        return result;
    }

    private List<Map<String, Object>> buildProposedActionsSection(List<ProposedActionResponse> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProposedActionResponse action : actions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", action.id().toString());
            entry.put("diagnosisId", action.diagnosisId() != null ? action.diagnosisId().toString() : null);
            entry.put("actionType", action.actionType().name());
            entry.put("arguments", action.arguments() == null ? Map.of() : new TreeMap<>(action.arguments()));
            entry.put("commandPreview", action.commandPreview() != null
                    ? action.commandPreview()
                    : actionCatalogValidator.renderCommandPreview(action.actionType(), action.arguments()));
            entry.put("riskLevel", action.riskLevel().name());
            entry.put("rollbackStrategy", action.rollbackStrategy());
            entry.put("status", action.status().name());
            entry.put("createdAt", action.createdAt() != null ? action.createdAt().toString() : null);
            result.add(entry);
        }
        return result;
    }

    private List<Map<String, Object>> buildApprovalsSection(
            List<ProposedActionResponse> actions,
            List<ApprovalDecisionEntity> approvals
    ) {
        if (approvals == null || approvals.isEmpty()) {
            return List.of();
        }
        Map<UUID, ProposedActionResponse> actionById = new LinkedHashMap<>();
        if (actions != null) {
            for (ProposedActionResponse action : actions) {
                actionById.put(action.id(), action);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApprovalDecisionEntity decision : approvals) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", decision.getId().toString());
            entry.put("actionId", decision.getActionId().toString());
            entry.put("decision", decision.getDecision().name());
            entry.put("actor", decision.getActor());
            entry.put("reason", decision.getReason());
            entry.put("decidedAt", decision.getDecidedAt() != null ? decision.getDecidedAt().toString() : null);
            entry.put("expectedVersion", decision.getExpectedVersion());
            entry.put("idempotencyKey", decision.getIdempotencyKey());
            ProposedActionResponse action = actionById.get(decision.getActionId());
            Long delayMs = null;
            if (action != null && action.createdAt() != null && decision.getDecidedAt() != null) {
                delayMs = Math.max(0L, Duration.between(action.createdAt(), decision.getDecidedAt()).toMillis());
            }
            entry.put("approvalDelayMs", delayMs);
            result.add(entry);
        }
        return result;
    }

    private List<Map<String, Object>> buildExecutionsSection(List<ToolExecutionResponse> executions) {
        if (executions == null || executions.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolExecutionResponse execution : executions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", execution.id().toString());
            entry.put("actionId", execution.actionId() != null ? execution.actionId().toString() : null);
            entry.put("idempotencyKey", execution.idempotencyKey());
            entry.put("attempt", execution.attempt());
            entry.put("status", execution.status().name());
            entry.put("startedAt", execution.startedAt() != null ? execution.startedAt().toString() : null);
            entry.put("endedAt", execution.endedAt() != null ? execution.endedAt().toString() : null);
            Long durationMs = null;
            if (execution.startedAt() != null && execution.endedAt() != null) {
                durationMs = Math.max(0L, Duration.between(execution.startedAt(), execution.endedAt()).toMillis());
            }
            entry.put("durationMs", durationMs);
            entry.put("traceId", execution.traceId());
            Object summary = execution.sanitizedOutput() != null ? execution.sanitizedOutput().get("summary") : null;
            entry.put("summary", summary != null ? String.valueOf(summary) : null);
            entry.put("sanitizedInput", execution.sanitizedInput() == null ? Map.of() : execution.sanitizedInput());
            entry.put("sanitizedOutput", execution.sanitizedOutput() == null ? Map.of() : execution.sanitizedOutput());
            result.add(entry);
        }
        return result;
    }

    private Map<String, Object> buildHealthComparisonSection(HealthComparisonResponse health) {
        Map<String, Object> section = new LinkedHashMap<>();
        HealthSnapshotResponse before = health != null ? health.before() : null;
        HealthSnapshotResponse after = health != null ? health.after() : null;
        HealthSnapshotResponse postRollback = health != null ? health.postRollback() : null;

        section.put("before", summarizeSnapshot(before));
        section.put("after", summarizeSnapshot(after));
        section.put("postRollback", summarizeSnapshot(postRollback));

        String beforeStatus = before != null ? String.valueOf(before.signals().getOrDefault("healthStatus", "UNKNOWN")) : "MISSING";
        String afterStatus = after != null ? String.valueOf(after.signals().getOrDefault("healthStatus", "UNKNOWN")) : "MISSING";
        String postRollbackStatus = postRollback != null ? String.valueOf(postRollback.signals().getOrDefault("healthStatus", "UNKNOWN")) : null;

        String summary = "BEFORE=" + beforeStatus + ", AFTER=" + afterStatus
                + (postRollbackStatus != null ? ", POST_ROLLBACK=" + postRollbackStatus : "");
        section.put("comparisonSummary", summary);
        return section;
    }

    private Map<String, Object> summarizeSnapshot(HealthSnapshotResponse snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> signals = snapshot.signals() == null ? Map.of() : snapshot.signals();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", snapshot.id().toString());
        summary.put("phase", snapshot.snapshotPhase().name());
        summary.put("capturedAt", snapshot.capturedAt() != null ? snapshot.capturedAt().toString() : null);
        summary.put("healthStatus", signals.get("healthStatus"));
        summary.put("telemetryStatus", signals.get("telemetryStatus"));
        summary.put("workloadStatus", signals.get("workloadStatus"));
        summary.put("activeFindings", signals.getOrDefault("activeFindings", List.of()));
        copyIfPresent(signals, summary, "restartCount");
        copyIfPresent(signals, summary, "errorRatePercent");
        copyIfPresent(signals, summary, "latencyMs");
        copyIfPresent(signals, summary, "cpuPercent");
        copyIfPresent(signals, summary, "memoryPercent");
        return summary;
    }

    private List<Map<String, Object>> buildOutcomesSection(List<RemediationOutcomeEntity> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (RemediationOutcomeEntity outcome : outcomes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", outcome.getId().toString());
            entry.put("actionId", outcome.getActionId() != null ? outcome.getActionId().toString() : null);
            entry.put("actionType", outcome.getActionType().name());
            entry.put("success", outcome.isSuccess());
            entry.put("durationMs", outcome.getDurationMs());
            entry.put("rollbackTriggered", outcome.isRollbackTriggered());
            entry.put("rollbackSuccess", outcome.getRollbackSuccess());
            entry.put("createdAt", outcome.getCreatedAt() != null ? outcome.getCreatedAt().toString() : null);
            result.add(entry);
        }
        return result;
    }

    private Map<String, Object> buildRunbookCandidate(
            IncidentResponse incident,
            List<DiagnosisResponse> diagnoses,
            List<ProposedActionResponse> actions,
            HealthComparisonResponse health,
            List<RemediationOutcomeEntity> outcomes
    ) {
        ProposedActionResponse primaryAction = (actions == null || actions.isEmpty()) ? null : actions.getFirst();
        boolean succeededOutcome = outcomes != null && outcomes.stream().anyMatch(RemediationOutcomeEntity::isSuccess);
        boolean eligible = incident.status() == IncidentStatus.CLOSED
                && primaryAction != null
                && primaryAction.actionType() != ActionType.NO_ACTION
                && succeededOutcome;

        Set<String> findingCodes = new LinkedHashSet<>();
        if (diagnoses != null) {
            for (DiagnosisResponse diagnosis : diagnoses) {
                if (diagnosis.hypotheses() != null) {
                    for (DiagnosisHypothesis hypothesis : diagnosis.hypotheses()) {
                        if (hypothesis.ruleCode() != null && !hypothesis.ruleCode().isBlank()) {
                            findingCodes.add(hypothesis.ruleCode());
                        }
                    }
                }
            }
        }
        if (health != null && health.before() != null && health.before().signals() != null) {
            Object rawFindings = health.before().signals().get("activeFindings");
            if (rawFindings instanceof List<?> list) {
                list.forEach(item -> {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        findingCodes.add(String.valueOf(item));
                    }
                });
            }
        }

        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("eligible", eligible);
        candidate.put("service", incident.service());
        candidate.put("environmentId", incident.environmentId());
        candidate.put("findingCodes", List.copyOf(findingCodes));
        if (primaryAction != null) {
            candidate.put("actionType", primaryAction.actionType().name());
            candidate.put("arguments", primaryAction.arguments() == null ? Map.of() : new TreeMap<>(primaryAction.arguments()));
            candidate.put("suggestedName", incident.service() + " - " + primaryAction.actionType().name());
        } else {
            candidate.put("actionType", ActionType.NO_ACTION.name());
            candidate.put("arguments", Map.of());
            candidate.put("suggestedName", null);
        }
        candidate.put("note", eligible
                ? "Eligible for runbook draft via POST /api/v1/runbooks/draft-from-incident/" + incident.id()
                + ". Publishing requires runbook:publish authority; runbooks are never auto-published or auto-executed."
                : "Not eligible for automatic runbook drafting (requires CLOSED status with a successful remediated action).");
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private String renderMarkdown(
            IncidentResponse incident,
            String finalOutcome,
            Map<String, Object> timelineSection,
            Map<String, Object> evidenceSection,
            List<Map<String, Object>> diagnoses,
            List<Map<String, Object>> actions,
            List<Map<String, Object>> approvals,
            List<Map<String, Object>> executions,
            Map<String, Object> healthSection,
            List<Map<String, Object>> outcomes,
            Map<String, Object> runbookCandidate
    ) {
        StringBuilder md = new StringBuilder();
        md.append("# Incident Report: ").append(incident.service()).append(" (").append(finalOutcome).append(")\n\n");

        md.append("## 1. Alert Metadata\n\n");
        md.append("- **Incident ID:** `").append(incident.id()).append("`\n");
        md.append("- **Environment:** `").append(incident.environmentId()).append("`\n");
        md.append("- **Service:** `").append(incident.service()).append("`\n");
        md.append("- **Severity:** `").append(incident.severity()).append("`\n");
        md.append("- **Source:** `").append(incident.source()).append("`\n");
        md.append("- **Fingerprint:** `").append(incident.fingerprint()).append("`\n");
        md.append("- **Trace ID:** `").append(incident.traceId() != null ? incident.traceId() : "n/a").append("`\n");
        md.append("- **Final Outcome:** `").append(finalOutcome).append("`\n\n");

        md.append("## 2. Timeline & Phase Durations\n\n");
        md.append("- **Total Duration (ms):** ").append(timelineSection.get("totalDurationMs")).append("\n");
        Map<String, Long> phaseDurations = (Map<String, Long>) timelineSection.get("phaseDurationsMs");
        if (phaseDurations != null && !phaseDurations.isEmpty()) {
            md.append("- **Phase Durations (ms):** ");
            List<String> parts = new ArrayList<>();
            phaseDurations.forEach((phase, ms) -> parts.add("`" + phase + "`=" + ms + "ms"));
            md.append(String.join(", ", parts)).append("\n");
        }
        md.append("\n| Seq | From | To | Event | Actor | Delta (ms) | Reason |\n");
        md.append("| --- | --- | --- | --- | --- | ---: | --- |\n");
        List<Map<String, Object>> events = (List<Map<String, Object>>) timelineSection.get("events");
        for (Map<String, Object> event : events) {
            md.append("| ").append(event.get("sequence"))
                    .append(" | ").append(nullSafe(event.get("fromStatus"), "-"))
                    .append(" | ").append(nullSafe(event.get("toStatus"), "-"))
                    .append(" | ").append(nullSafe(event.get("eventType"), "-"))
                    .append(" | ").append(escapePipes(nullSafe(event.get("actor"), "-")))
                    .append(" | ").append(event.get("durationFromPreviousMs"))
                    .append(" | ").append(escapePipes(nullSafe(event.get("reason"), "-")))
                    .append(" |\n");
        }
        md.append("\n");

        md.append("## 3. Evidence Inventory\n\n");
        md.append("- **Total Items:** ").append(evidenceSection.get("totalCount"))
                .append(" (Collection Errors: ").append(evidenceSection.get("collectionErrorCount")).append(")\n\n");
        List<Map<String, Object>> evidenceItems = (List<Map<String, Object>>) evidenceSection.get("items");
        if (evidenceItems.isEmpty()) {
            md.append("_No evidence items recorded._\n\n");
        } else {
            md.append("| Evidence ID | Type | Source | Redaction | Hash |\n");
            md.append("| --- | --- | --- | --- | --- |\n");
            for (Map<String, Object> item : evidenceItems) {
                md.append("| `").append(item.get("id")).append("` | ")
                        .append(item.get("type")).append(" | ")
                        .append(escapePipes(nullSafe(item.get("source"), "-"))).append(" | ")
                        .append(item.get("redactionStatus")).append(" | `")
                        .append(preview(nullSafe(item.get("contentHash"), "-"), 16)).append("` |\n");
            }
            md.append("\n");
        }

        md.append("## 4. Diagnosis & Cited Evidence\n\n");
        if (diagnoses.isEmpty()) {
            md.append("_No diagnosis recorded._\n\n");
        } else {
            for (Map<String, Object> d : diagnoses) {
                md.append("- **Summary:** ").append(d.get("summary")).append("\n");
                md.append("- **Confidence:** ").append(d.get("confidence"))
                        .append(" | **Model:** `").append(d.get("modelVersion"))
                        .append("` | **Rules:** `").append(d.get("ruleVersion")).append("`\n");
                md.append("- **Cited Evidence IDs:** ").append(d.get("citedEvidenceIds")).append("\n");
                List<Map<String, Object>> hypotheses = (List<Map<String, Object>>) d.get("hypotheses");
                for (Map<String, Object> h : hypotheses) {
                    md.append("  - [").append(nullSafe(h.get("ruleCode"), "HYPOTHESIS")).append("] ")
                            .append(h.get("title")).append(" (confidence=").append(h.get("confidence"))
                            .append(", evidence=").append(h.get("evidenceIds")).append(")\n");
                }
            }
            md.append("\n");
        }

        md.append("## 5. Proposed Actions & Approvals\n\n");
        if (actions.isEmpty()) {
            md.append("_No remediation actions proposed._\n\n");
        } else {
            for (Map<String, Object> a : actions) {
                md.append("- **Action `").append(a.get("id")).append("`:** `").append(a.get("actionType"))
                        .append("` (status=`").append(a.get("status"))
                        .append("`, risk=`").append(a.get("riskLevel"))
                        .append("`, rollback=`").append(a.get("rollbackStrategy")).append("`)\n");
                md.append("  - Preview: `").append(a.get("commandPreview")).append("`\n");
            }
            md.append("\n");
        }
        if (!approvals.isEmpty()) {
            md.append("### Approval Decisions\n\n");
            for (Map<String, Object> ap : approvals) {
                md.append("- **").append(ap.get("decision")).append("** by `").append(ap.get("actor"))
                        .append("` (delay=").append(nullSafe(ap.get("approvalDelayMs"), "n/a")).append("ms) — ")
                        .append(nullSafe(ap.get("reason"), "No reason supplied")).append("\n");
            }
            md.append("\n");
        }

        md.append("## 6. Tool Executions\n\n");
        if (executions.isEmpty()) {
            md.append("_No tool executions recorded._\n\n");
        } else {
            for (Map<String, Object> ex : executions) {
                md.append("- Execution `").append(ex.get("id")).append("` (attempt ").append(ex.get("attempt"))
                        .append(", key=`").append(ex.get("idempotencyKey")).append("`): **").append(ex.get("status"))
                        .append("** (duration=").append(nullSafe(ex.get("durationMs"), "n/a")).append("ms) — ")
                        .append(nullSafe(ex.get("summary"), "No summary")).append("\n");
            }
            md.append("\n");
        }

        md.append("## 7. Health Comparison (BEFORE / AFTER / POST_ROLLBACK)\n\n");
        md.append("- **Summary:** ").append(healthSection.get("comparisonSummary")).append("\n");
        appendSnapshotMarkdown(md, "BEFORE", (Map<String, Object>) healthSection.get("before"));
        appendSnapshotMarkdown(md, "AFTER", (Map<String, Object>) healthSection.get("after"));
        appendSnapshotMarkdown(md, "POST_ROLLBACK", (Map<String, Object>) healthSection.get("postRollback"));
        md.append("\n");

        md.append("## 8. Remediation Outcome\n\n");
        if (outcomes.isEmpty()) {
            md.append("_No remediation outcome recorded._\n\n");
        } else {
            for (Map<String, Object> o : outcomes) {
                md.append("- Action `").append(o.get("actionType")).append("`: success=`").append(o.get("success"))
                        .append("`, duration=").append(o.get("durationMs")).append("ms, rollbackTriggered=`")
                        .append(o.get("rollbackTriggered")).append("`, rollbackSuccess=`")
                        .append(nullSafe(o.get("rollbackSuccess"), "n/a")).append("`\n");
            }
            md.append("\n");
        }

        md.append("## 9. Runbook Candidate Summary\n\n");
        md.append("- **Eligible:** `").append(runbookCandidate.get("eligible")).append("`\n");
        md.append("- **Action Type:** `").append(runbookCandidate.get("actionType")).append("`\n");
        md.append("- **Finding Codes:** ").append(runbookCandidate.get("findingCodes")).append("\n");
        md.append("- **Note:** ").append(runbookCandidate.get("note")).append("\n");

        return md.toString();
    }

    private void appendSnapshotMarkdown(StringBuilder md, String label, Map<String, Object> snapshot) {
        if (snapshot == null) {
            md.append("- **").append(label).append(":** _Not captured_\n");
            return;
        }
        md.append("- **").append(label).append(":** health=`").append(snapshot.get("healthStatus"))
                .append("`, telemetry=`").append(snapshot.get("telemetryStatus"))
                .append("`, workload=`").append(nullSafe(snapshot.get("workloadStatus"), "UNKNOWN"))
                .append("`, findings=").append(snapshot.get("activeFindings")).append("\n");
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private String preview(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= maxLength ? singleLine : singleLine.substring(0, maxLength) + "...";
    }

    private String nullSafe(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String escapePipes(String value) {
        return value.replace("|", "\\|");
    }

    public record IncidentReportInput(
            IncidentResponse incident,
            List<IncidentEventResponse> timeline,
            List<EvidenceItemResponse> evidence,
            List<DiagnosisResponse> diagnoses,
            List<ProposedActionResponse> proposedActions,
            List<ApprovalDecisionEntity> approvals,
            List<ToolExecutionResponse> executions,
            HealthComparisonResponse healthComparison,
            List<RemediationOutcomeEntity> outcomes
    ) {
    }

    public record RenderedIncidentReport(
            String finalOutcome,
            Map<String, Object> structuredReport,
            String renderedMarkdown
    ) {
    }
}
