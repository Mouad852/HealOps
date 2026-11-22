package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Generates a structured diagnosis draft via Spring AI 1.0.3 using only redacted, bounded
 * evidence summaries and persisted evidence UUIDs.
 * Never runs inside a database transaction.
 */
@Service
public class LlmDiagnosisService {
    public static final String PROMPT_VERSION = "diagnosis-prompt-v1";
    private static final int MAX_EVIDENCE_ITEMS_IN_PROMPT = 20;
    private static final int MAX_EXCERPT_CHARS_IN_PROMPT = 400;
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|authorization|bearer|password|secret|token)(\\s*[:=]\\s*|\\s+)([^\\s,;]+)"
    );

    private static final String SYSTEM_PROMPT = """
            You are HealOps Incident Autopilot's diagnosis assistant.
            Analyze the bounded, redacted incident evidence and deterministic findings below.
            Strict rules:
            1. Every hypothesis MUST cite one or more exact evidence UUIDs from the provided evidence list. Never invent UUIDs.
            2. Never treat missing telemetry or COLLECTION_ERROR evidence as healthy.
            3. Recommend ONLY one allowlisted actionType from:
               NO_ACTION, RESTART_CONTAINER, RESTART_K8S_WORKLOAD, SCALE_K8S_DEPLOYMENT, ROLLBACK_K8S_DEPLOYMENT, RESTART_OPENSTACK_SERVICE.
            4. Supply ONLY typed arguments matching the chosen actionType:
               - NO_ACTION: {reason}
               - RESTART_CONTAINER: {container}
               - RESTART_K8S_WORKLOAD: {namespace, workload, kind}
               - SCALE_K8S_DEPLOYMENT: {namespace, deployment, replicas (1..10)}
               - ROLLBACK_K8S_DEPLOYMENT: {namespace, deployment, revision (optional int)}
               - RESTART_OPENSTACK_SERVICE: {node, service}
            5. NEVER emit shell commands, scripts, YAML manifests, or workflow state transitions.
            6. Confidence values must be decimal numbers between 0.00 and 1.00.
            """;

    private final ChatClient chatClient;
    private final boolean llmEnabled;
    private final String modelName;
    private final String apiKey;

    public LlmDiagnosisService(
            ChatClient chatClient,
            boolean llmEnabled,
            String modelName
    ) {
        this(chatClient, llmEnabled, modelName, "unit-test-key");
    }

    @Autowired
    public LlmDiagnosisService(
            ChatClient chatClient,
            @Value("${healops.diagnosis.llm-enabled:false}") boolean llmEnabled,
            @Value("${spring.ai.openai.chat.options.model:gemini-2.5-flash-lite}") String modelName,
            @Value("${spring.ai.openai.api-key:}") String apiKey
    ) {
        this.chatClient = chatClient;
        this.llmEnabled = llmEnabled;
        this.modelName = modelName;
        this.apiKey = apiKey;
    }

    public boolean isEnabled() {
        return llmEnabled && isUsableApiKey(apiKey);
    }

    public static boolean isUsableApiKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return false;
        }
        String normalized = rawKey.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("test-key")
                && !normalized.equals("not-configured")
                && !normalized.startsWith("replace_")
                && !normalized.contains("replace_with");
    }

    public String modelVersion() {
        return modelName + "@" + PROMPT_VERSION;
    }

    public LlmDiagnosisDraft generateDraft(
            IncidentResponse incident,
            List<EvidenceItemResponse> evidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult deterministic
    ) {
        String userPrompt = buildBoundedUserPrompt(incident, evidence, correlation, deterministic);
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .entity(LlmDiagnosisDraft.class);
    }

    String buildBoundedUserPrompt(
            IncidentResponse incident,
            List<EvidenceItemResponse> evidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult deterministic
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Incident:\n");
        sb.append("- service: ").append(sanitize(incident.service(), 200)).append('\n');
        sb.append("- environmentId: ").append(sanitize(incident.environmentId(), 100)).append('\n');
        sb.append("- severity: ").append(incident.severity()).append('\n');
        sb.append("- correlationKeys: ").append(sanitize(String.valueOf(correlation.correlationKeys()), 500)).append('\n');
        sb.append("- telemetryStatus: ").append(deterministic.healthSignals().telemetryStatus()).append('\n');
        sb.append("- healthStatus: ").append(deterministic.healthSignals().healthStatus()).append('\n');

        sb.append("\nDeterministic Findings (").append(deterministic.ruleVersion()).append("):\n");
        for (DeterministicDiagnosticsEngine.DeterministicFinding finding : deterministic.findings()) {
            sb.append("- [").append(finding.code()).append("] ")
                    .append(sanitize(finding.title(), 200))
                    .append(" (evidenceIds=").append(finding.evidenceIds()).append(")\n");
        }
        if (!deterministic.missingEvidence().isEmpty()) {
            sb.append("- Missing evidence: ").append(sanitize(String.join("; ", deterministic.missingEvidence()), 500)).append('\n');
        }
        sb.append("- Deterministic baseline action: ")
                .append(deterministic.recommendedAction().actionType())
                .append(" args=").append(deterministic.recommendedAction().arguments()).append('\n');

        sb.append("\nBounded Redacted Evidence (cite ONLY these exact UUIDs):\n");
        evidence.stream().limit(MAX_EVIDENCE_ITEMS_IN_PROMPT).forEach(item -> sb
                .append("- id=").append(item.id())
                .append(" type=").append(item.type())
                .append(" source=").append(sanitize(item.source(), 100))
                .append(" target=").append(sanitize(item.target(), 150))
                .append(" observedAt=").append(item.observedAt())
                .append(" excerpt=\"").append(sanitize(item.excerpt(), MAX_EXCERPT_CHARS_IN_PROMPT)).append("\"\n"));

        return sb.toString();
    }

    private String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String singleLine = raw.replace('\r', ' ').replace('\n', ' ').trim();
        String redacted = SENSITIVE_VALUE.matcher(singleLine).replaceAll("$1$2[REDACTED]");
        return redacted.length() <= maxLength ? redacted : redacted.substring(0, maxLength);
    }
}
