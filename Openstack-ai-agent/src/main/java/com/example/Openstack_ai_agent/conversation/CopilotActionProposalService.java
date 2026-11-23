package com.example.Openstack_ai_agent.conversation;

import com.example.Openstack_ai_agent.diagnosis.DiagnosisEntity;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisHypothesis;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotEntity;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotPhase;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceCandidate;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.evidence.RedactionStatus;
import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentEventEntity;
import com.example.Openstack_ai_agent.incident.IncidentEventRepository;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentWorkflowEvent;
import com.example.Openstack_ai_agent.incident.TransitionIncidentRequest;
import com.example.Openstack_ai_agent.observability.DashboardEventPublisher;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidationException;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.remediation.ProposedActionEntity;
import com.example.Openstack_ai_agent.remediation.ProposedActionRepository;
import com.example.Openstack_ai_agent.remediation.ValidatedActionProposal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Queues one or more allowlisted remediation action proposals requested by the operator
 * through HealOps Copilot (/chat) directly into the approval queue (/approvals).
 * Every proposed command is validated via {@link ActionCatalogValidator} and remains in
 * {@code PROPOSED} / {@code AWAITING_APPROVAL} state until the operator explicitly approves it.
 */
@Service
public class CopilotActionProposalService {
    private static final Logger log = LoggerFactory.getLogger(CopilotActionProposalService.class);
    public static final String OPEN_TAG = "<PROPOSED_ACTIONS_JSON>";
    public static final String CLOSE_TAG = "</PROPOSED_ACTIONS_JSON>";

    private static final Pattern PROPOSAL_BLOCK_PATTERN = Pattern.compile(
            "<PROPOSED_ACTIONS_JSON>\\s*(\\[.*?\\])\\s*</PROPOSED_ACTIONS_JSON>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SCALE_PATTERN = Pattern.compile(
            "(?i)\\bscale\\s+(?:deployment[/\\s]+)?([a-z0-9][-a-z0-9]*)(?:\\s+deployment)?(?:\\s+in\\s+namespace\\s+([a-z0-9][-a-z0-9]*))?.*?\\bto\\s+(\\d+)\\s*replica"
    );
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile(
            "(?i)\\brollback\\s+(?:deployment[/\\s]+)?([a-z0-9][-a-z0-9]*)(?:\\s+deployment)?(?:\\s+in\\s+namespace\\s+([a-z0-9][-a-z0-9]*))?(?:.*?\\brevision\\s+#?(\\d+))?"
    );
    private static final Pattern RESTART_K8S_PATTERN = Pattern.compile(
            "(?i)\\brestart\\s+(?:k8s\\s+|kubernetes\\s+|deployment[/\\s]+|workload[/\\s]+)?([a-z0-9][-a-z0-9]*)(?:\\s+in\\s+namespace\\s+([a-z0-9][-a-z0-9]*))?"
    );

    private final IncidentRepository incidentRepository;
    private final IncidentEventRepository incidentEventRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final HealthSnapshotRepository healthSnapshotRepository;
    private final ProposedActionRepository proposedActionRepository;
    private final ActionCatalogValidator actionCatalogValidator;
    private final DashboardEventPublisher dashboardEventPublisher;
    private final ConversationSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTx;

    public CopilotActionProposalService(
            IncidentRepository incidentRepository,
            IncidentEventRepository incidentEventRepository,
            EvidenceItemRepository evidenceItemRepository,
            DiagnosisRepository diagnosisRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            ProposedActionRepository proposedActionRepository,
            ActionCatalogValidator actionCatalogValidator,
            DashboardEventPublisher dashboardEventPublisher,
            ConversationSanitizer sanitizer,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.incidentRepository = incidentRepository;
        this.incidentEventRepository = incidentEventRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.healthSnapshotRepository = healthSnapshotRepository;
        this.proposedActionRepository = proposedActionRepository;
        this.actionCatalogValidator = actionCatalogValidator;
        this.dashboardEventPublisher = dashboardEventPublisher;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ParsedCopilotReply extractAndQueueProposals(
            UUID sessionId,
            String environmentId,
            String userText,
            String rawAssistantReply
    ) {
        String replyText = rawAssistantReply == null ? "" : rawAssistantReply;
        List<RawActionDraft> drafts = new ArrayList<>();

        Matcher matcher = PROPOSAL_BLOCK_PATTERN.matcher(replyText);
        while (matcher.find()) {
            String jsonArray = matcher.group(1);
            drafts.addAll(parseJsonDrafts(jsonArray));
        }

        String cleanedReply = PROPOSAL_BLOCK_PATTERN.matcher(replyText).replaceAll("").trim();

        if (drafts.isEmpty() && userText != null && !userText.isBlank()) {
            drafts.addAll(parseDeterministicUserIntent(userText));
        }

        if (drafts.isEmpty()) {
            return new ParsedCopilotReply(cleanedReply, List.of());
        }

        List<QueuedActionRecord> queued = new ArrayList<>();
        for (RawActionDraft draft : drafts) {
            if (queued.size() >= 5) {
                break;
            }
            try {
                ValidatedActionProposal validated = actionCatalogValidator.validateAndNormalize(
                        draft.actionType(),
                        draft.arguments()
                );
                if (validated.actionType() == ActionType.NO_ACTION) {
                    continue;
                }
                QueuedActionRecord record = requiresNewTx.execute(
                        status -> persistProposalTransactionally(sessionId, environmentId, userText, draft, validated)
                );
                if (record != null) {
                    queued.add(record);
                }
            } catch (RuntimeException ex) {
                log.warn("Skipped invalid Copilot action proposal {} ({}): {}",
                        draft.actionType(), draft.arguments(), ex.getMessage());
            }
        }

        if (queued.isEmpty()) {
            return new ParsedCopilotReply(cleanedReply, List.of());
        }

        StringBuilder summaryBlock = new StringBuilder(cleanedReply);
        summaryBlock.append("\n\n---\n### Queued for Operator Approval (`/approvals`)\n");
        summaryBlock.append("The following **").append(queued.size())
                .append("** allowlisted command(s) have been created and queued in **[/approvals](/approvals)**:\n\n");
        summaryBlock.append("| # | Action Type | Target Service | Command to Execute | Risk | Incident |\n");
        summaryBlock.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n");
        for (int i = 0; i < queued.size(); i++) {
            QueuedActionRecord q = queued.get(i);
            summaryBlock.append("| ").append(i + 1)
                    .append(" | `").append(q.actionType().name()).append("`")
                    .append(" | **").append(q.service()).append("**")
                    .append(" | `").append(q.commandPreview()).append("`")
                    .append(" | ").append(q.riskLevel())
                    .append(" | `#").append(q.incidentId().toString().substring(0, 8)).append("` |\n");
        }
        summaryBlock.append("\nOpen **[/approvals](/approvals)** (or click the linked Incident below) to review and click **Approve** to execute.");

        return new ParsedCopilotReply(summaryBlock.toString(), List.copyOf(queued));
    }

    private QueuedActionRecord persistProposalTransactionally(
            UUID sessionId,
            String environmentId,
            String userText,
            RawActionDraft draft,
            ValidatedActionProposal validated
    ) {
        String service = resolveService(draft, validated);
        Map<String, String> labels = buildTargetLabels(service, validated);
        String reason = draft.reason() != null && !draft.reason().isBlank()
                ? sanitizer.sanitizeContent(draft.reason())
                : "Copilot operator request: " + validated.commandPreview();

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("summary", reason);
        annotations.put("requestedBy", "copilot-chat");
        annotations.put("commandPreview", validated.commandPreview());
        if (userText != null && !userText.isBlank()) {
            annotations.put("operatorPrompt", truncate(sanitizer.sanitizeContent(userText), 240));
        }

        String uid = UUID.randomUUID().toString();
        CreateIncidentRequest createReq = new CreateIncidentRequest(
                "copilot-chat",
                "copilot-ext-" + uid,
                "copilot-fp-" + sessionId + "-" + uid,
                environmentId,
                IncidentSeverity.MEDIUM,
                service,
                OffsetDateTime.now(),
                labels,
                annotations,
                null
        );

        IncidentEntity incident = incidentRepository.saveAndFlush(IncidentEntity.alertReceived(createReq));
        incidentEventRepository.save(IncidentEventEntity.alertReceived(incident));

        // Transition through GATHERING_EVIDENCE -> CORRELATING_SIGNALS -> DIAGNOSING -> AWAITING_APPROVAL
        recordStepTransition(incident, IncidentStatus.ALERTED, IncidentStatus.GATHERING_EVIDENCE,
                IncidentWorkflowEvent.COLLECTION_STARTED, "Copilot captured request context");
        recordStepTransition(incident, IncidentStatus.GATHERING_EVIDENCE, IncidentStatus.CORRELATING_SIGNALS,
                IncidentWorkflowEvent.EVIDENCE_GATHERED, "Copilot validated target workload parameters");
        recordStepTransition(incident, IncidentStatus.CORRELATING_SIGNALS, IncidentStatus.DIAGNOSING,
                IncidentWorkflowEvent.SIGNALS_CORRELATED, "Synthesizing allowlisted command proposal");
        recordStepTransition(incident, IncidentStatus.DIAGNOSING, IncidentStatus.AWAITING_APPROVAL,
                IncidentWorkflowEvent.ACTION_PROPOSED, "Command queued for operator approval: " + validated.commandPreview());

        OffsetDateTime now = OffsetDateTime.now();
        String excerpt = "{\"summary\":\"" + reason.replace("\"", "'") + "\",\"commandPreview\":\""
                + validated.commandPreview().replace("\"", "'") + "\"}";
        Map<String, String> evMeta = Map.of("sessionId", sessionId.toString(), "actionType", validated.actionType().name());
        EvidenceCandidate candidate = new EvidenceCandidate(
                EvidenceType.ALERT_CONTEXT,
                "copilot-chat",
                service,
                now,
                now.minusMinutes(1),
                now,
                null,
                excerpt,
                evMeta
        );
        EvidenceItemEntity evidence = evidenceItemRepository.saveAndFlush(EvidenceItemEntity.from(
                incident.getId(),
                candidate,
                excerpt,
                RedactionStatus.NOT_REQUIRED,
                sha256Hex(excerpt),
                evMeta
        ));

        healthSnapshotRepository.saveAndFlush(HealthSnapshotEntity.of(
                incident.getId(),
                HealthSnapshotPhase.BEFORE,
                Map.of(
                        "healthStatus", "HEALTHY",
                        "telemetryStatus", "COMPLETE",
                        "workloadStatus", "Running",
                        "evidenceCount", 1,
                        "collectionErrorCount", 0
                )
        ));

        DiagnosisEntity diagnosis = diagnosisRepository.saveAndFlush(DiagnosisEntity.create(
                incident.getId(),
                reason,
                List.of(new DiagnosisHypothesis(
                        "Operator requested " + validated.actionType().name() + " on " + service,
                        "Queued via HealOps Copilot: " + validated.commandPreview(),
                        new BigDecimal("0.95"),
                        List.of(evidence.getId()),
                        "COPILOT_OPERATOR_REQUEST"
                )),
                new BigDecimal("0.95"),
                List.of(evidence.getId()),
                "copilot-proposal-v1",
                "copilot-operator-intent"
        ));

        ProposedActionEntity savedAction = proposedActionRepository.saveAndFlush(
                ProposedActionEntity.proposed(incident.getId(), diagnosis.getId(), validated)
        );

        Map<String, Object> approvalPayload = new LinkedHashMap<>();
        approvalPayload.put("actionId", savedAction.getId().toString());
        approvalPayload.put("incidentId", incident.getId().toString());
        approvalPayload.put("diagnosisId", diagnosis.getId().toString());
        approvalPayload.put("actionType", savedAction.getActionType().name());
        approvalPayload.put("riskLevel", savedAction.getRiskLevel().name());
        approvalPayload.put("rollbackStrategy", savedAction.getRollbackStrategy());
        approvalPayload.put("commandPreview", validated.commandPreview());
        approvalPayload.put("status", savedAction.getStatus().name());
        approvalPayload.put("version", savedAction.getVersion());

        dashboardEventPublisher.publish(
                environmentId,
                "approval.created",
                incident.getId(),
                null,
                approvalPayload
        );
        dashboardEventPublisher.publish(
                environmentId,
                "incident.updated",
                incident.getId(),
                null,
                Map.of(
                        "incidentId", incident.getId().toString(),
                        "service", service,
                        "status", IncidentStatus.AWAITING_APPROVAL.name(),
                        "severity", incident.getSeverity().name()
                )
        );

        return new QueuedActionRecord(
                savedAction.getId(),
                incident.getId(),
                evidence.getId(),
                validated.actionType(),
                service,
                validated.arguments(),
                validated.commandPreview(),
                validated.riskLevel().name(),
                validated.rollbackStrategy()
        );
    }

    private void recordStepTransition(
            IncidentEntity incident,
            IncidentStatus fromStatus,
            IncidentStatus toStatus,
            IncidentWorkflowEvent event,
            String reason
    ) {
        incident.getAnnotations(); // keep initialized
        long nextSeq = incidentEventRepository.countByIncidentId(incident.getId()) + 1;
        TransitionIncidentRequest req = new TransitionIncidentRequest(
                event,
                null,
                "copilot-assistant",
                reason,
                null,
                Map.of("source", "copilot-chat"),
                incident.getVersion()
        );
        incident.transitionTo(toStatus);
        incidentRepository.saveAndFlush(incident);
        incidentEventRepository.save(IncidentEventEntity.transition(incident, nextSeq, fromStatus, toStatus, req));
    }

    private String resolveService(RawActionDraft draft, ValidatedActionProposal validated) {
        if (draft.service() != null && !draft.service().isBlank()) {
            return draft.service().trim();
        }
        Map<String, Object> args = validated.arguments();
        if (args.get("deployment") != null) {
            return String.valueOf(args.get("deployment"));
        }
        if (args.get("workload") != null) {
            return String.valueOf(args.get("workload"));
        }
        if (args.get("container") != null) {
            return String.valueOf(args.get("container"));
        }
        if (args.get("service") != null) {
            return String.valueOf(args.get("service"));
        }
        return "infrastructure-workload";
    }

    private Map<String, String> buildTargetLabels(String service, ValidatedActionProposal validated) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("service", service);
        Map<String, Object> args = validated.arguments();
        switch (validated.actionType()) {
            case SCALE_K8S_DEPLOYMENT, ROLLBACK_K8S_DEPLOYMENT -> {
                labels.put("namespace", String.valueOf(args.get("namespace")));
                labels.put("deployment", String.valueOf(args.get("deployment")));
            }
            case RESTART_K8S_WORKLOAD -> {
                labels.put("namespace", String.valueOf(args.get("namespace")));
                labels.put("workload", String.valueOf(args.get("workload")));
                labels.put("deployment", String.valueOf(args.get("workload")));
                if (args.get("kind") != null) {
                    labels.put("kind", String.valueOf(args.get("kind")));
                }
            }
            case RESTART_CONTAINER -> {
                labels.put("runtime", "docker");
                labels.put("container", String.valueOf(args.get("container")));
            }
            case RESTART_OPENSTACK_SERVICE -> {
                labels.put("platform", "openstack");
                labels.put("node", String.valueOf(args.get("node")));
                labels.put("openstack_service", String.valueOf(args.get("service")));
            }
            case NO_ACTION -> {
            }
        }
        return labels;
    }

    private List<RawActionDraft> parseJsonDrafts(String jsonArray) {
        List<RawActionDraft> list = new ArrayList<>();
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(
                    jsonArray,
                    new TypeReference<List<Map<String, Object>>>() {}
            );
            for (Map<String, Object> item : parsed) {
                Object typeObj = item.get("actionType");
                if (typeObj == null) {
                    continue;
                }
                String actionType = String.valueOf(typeObj).trim();
                String service = item.get("service") != null ? String.valueOf(item.get("service")).trim() : null;
                String reason = item.get("reason") != null ? String.valueOf(item.get("reason")).trim() : null;
                Map<String, Object> args = new LinkedHashMap<>();
                Object rawArgs = item.get("arguments");
                if (rawArgs instanceof Map<?, ?> mapArgs) {
                    mapArgs.forEach((k, v) -> {
                        if (k != null && v != null) {
                            args.put(String.valueOf(k), v);
                        }
                    });
                }
                list.add(new RawActionDraft(actionType, service, reason, args));
            }
        } catch (Exception ex) {
            log.debug("Failed to parse PROPOSED_ACTIONS_JSON block: {}", ex.getMessage());
        }
        return list;
    }

    List<RawActionDraft> parseDeterministicUserIntent(String userText) {
        List<RawActionDraft> drafts = new ArrayList<>();
        String lower = userText.trim().toLowerCase(Locale.ROOT);
        // Only trigger on explicit imperative action requests, never questions or informational prompts
        boolean isQuestion = lower.startsWith("why ") || lower.startsWith("what ") || lower.startsWith("how ")
                || lower.startsWith("show ") || lower.startsWith("list ") || lower.startsWith("explain ")
                || lower.startsWith("describe ") || lower.startsWith("check ") || lower.startsWith("tell ");
        if (isQuestion) {
            return List.of();
        }

        Set<String> stopWords = Set.of(
                "the", "a", "an", "my", "this", "that", "these", "those", "them", "it",
                "all", "everything", "anything", "something", "one", "some",
                "pod", "pods", "container", "containers", "service", "services",
                "deployment", "deployments", "workload", "workloads", "cluster", "system"
        );

        Matcher scaleMatcher = SCALE_PATTERN.matcher(userText);
        while (scaleMatcher.find()) {
            String deployment = scaleMatcher.group(1).toLowerCase(Locale.ROOT);
            if (stopWords.contains(deployment)) {
                continue;
            }
            String namespace = scaleMatcher.group(2) != null ? scaleMatcher.group(2).toLowerCase(Locale.ROOT) : "pitstop";
            int replicas = Integer.parseInt(scaleMatcher.group(3));
            drafts.add(new RawActionDraft(
                    ActionType.SCALE_K8S_DEPLOYMENT.name(),
                    deployment,
                    "Operator requested scaling " + deployment + " to " + replicas + " replicas in namespace " + namespace,
                    Map.of("namespace", namespace, "deployment", deployment, "replicas", replicas)
            ));
        }

        Matcher rollbackMatcher = ROLLBACK_PATTERN.matcher(userText);
        while (rollbackMatcher.find()) {
            String deployment = rollbackMatcher.group(1).toLowerCase(Locale.ROOT);
            if (stopWords.contains(deployment)) {
                continue;
            }
            String namespace = rollbackMatcher.group(2) != null ? rollbackMatcher.group(2).toLowerCase(Locale.ROOT) : "pitstop";
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("namespace", namespace);
            args.put("deployment", deployment);
            if (rollbackMatcher.group(3) != null) {
                args.put("revision", Integer.parseInt(rollbackMatcher.group(3)));
            }
            drafts.add(new RawActionDraft(
                    ActionType.ROLLBACK_K8S_DEPLOYMENT.name(),
                    deployment,
                    "Operator requested rolling back deployment " + deployment + " in namespace " + namespace,
                    args
            ));
        }

        Matcher restartMatcher = RESTART_K8S_PATTERN.matcher(userText);
        while (restartMatcher.find()) {
            String target = restartMatcher.group(1).toLowerCase(Locale.ROOT);
            if (stopWords.contains(target)) {
                continue;
            }
            String namespace = restartMatcher.group(2) != null ? restartMatcher.group(2).toLowerCase(Locale.ROOT) : "pitstop";
            if (target.contains("demo-workload") || lower.contains("container " + target)) {
                String containerName = target.equals("demo-workload") ? "healops-demo-workload" : target;
                drafts.add(new RawActionDraft(
                        ActionType.RESTART_CONTAINER.name(),
                        target,
                        "Operator requested restarting Docker container " + containerName,
                        Map.of("container", containerName)
                ));
            } else {
                String kind = (target.startsWith("mysql") || target.startsWith("rabbitmq")) ? "StatefulSet" : "Deployment";
                drafts.add(new RawActionDraft(
                        ActionType.RESTART_K8S_WORKLOAD.name(),
                        target,
                        "Operator requested restarting Kubernetes " + kind + " " + target + " in namespace " + namespace,
                        Map.of("namespace", namespace, "workload", target, "kind", kind)
                ));
            }
        }

        return drafts;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    public record RawActionDraft(
            String actionType,
            String service,
            String reason,
            Map<String, Object> arguments
    ) {
    }

    public record QueuedActionRecord(
            UUID actionId,
            UUID incidentId,
            UUID evidenceId,
            ActionType actionType,
            String service,
            Map<String, Object> arguments,
            String commandPreview,
            String riskLevel,
            String rollbackStrategy
    ) {
    }

    public record ParsedCopilotReply(
            String formattedReply,
            List<QueuedActionRecord> queuedActions
    ) {
    }
}
