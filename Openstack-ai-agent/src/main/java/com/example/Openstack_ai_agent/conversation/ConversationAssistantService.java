package com.example.Openstack_ai_agent.conversation;

import com.example.Openstack_ai_agent.diagnosis.LlmDiagnosisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generates assistant replies for persisted conversation sessions without holding
 * any database transaction. Always passes the session UUID explicitly as Spring AI's
 * {@link ChatMemory#CONVERSATION_ID} so distinct sessions never share chat memory.
 */
@Service
public class ConversationAssistantService {
    private static final Logger log = LoggerFactory.getLogger(ConversationAssistantService.class);
    private static final String SYSTEM_PROMPT = """
            You are HealOps Incident Autopilot's operations assistant.
            Respond concisely using only the provided bounded, redacted conversation context,
            read-only operational tool results, and incident/evidence/execution references.
            Strict rules:
            1. Never repeat secrets, credentials, or raw unbounded logs.
            2. Never emit executable shell commands or claim to bypass operator approval/policy.
            3. Reference evidence items and tool executions by their UUIDs.
            """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ConversationSanitizer sanitizer;
    private final CopilotToolQueryService copilotToolQueryService;
    private final boolean llmEnabled;
    private final String apiKey;

    @Autowired
    public ConversationAssistantService(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            ConversationSanitizer sanitizer,
            CopilotToolQueryService copilotToolQueryService,
            @Value("${healops.conversation.llm-enabled:false}") boolean llmEnabled,
            @Value("${spring.ai.openai.api-key:}") String apiKey
    ) {
        this.chatMemory = chatMemory;
        this.sanitizer = sanitizer;
        this.copilotToolQueryService = copilotToolQueryService;
        this.llmEnabled = llmEnabled;
        this.apiKey = apiKey;
        this.chatClient = chatClientBuilder
                .clone()
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public List<ConversationPersistenceService.GeneratedMessage> processTurn(
            ConversationPersistenceService.ConversationTurnContext context
    ) {
        UUID sessionId = context.session().id();
        String conversationId = sessionId.toString();
        String userText = sanitizer.sanitizeContent(context.userMessage().content());

        hydrateMemoryIfEmpty(conversationId, context);

        List<ConversationPersistenceService.GeneratedMessage> messages = new ArrayList<>();
        ConversationPersistenceService.IncidentReferenceSummary ref = context.incidentRef();
        UUID linkedIncidentId = context.userMessage().incidentId();
        UUID linkedEvidenceId = context.userMessage().evidenceId() != null
                ? context.userMessage().evidenceId()
                : (ref != null ? ref.latestEvidenceId() : null);
        UUID linkedExecutionId = context.userMessage().executionId() != null
                ? context.userMessage().executionId()
                : (ref != null ? ref.latestExecutionId() : null);

        if (ref != null) {
            messages.add(new ConversationPersistenceService.GeneratedMessage(
                    ConversationMessageRole.TOOL_REQUEST,
                    "lookup_incident_context(incidentId=" + ref.incidentId() + ")",
                    linkedEvidenceId,
                    linkedExecutionId,
                    ref.incidentId()
            ));
            StringBuilder toolSummary = new StringBuilder();
            toolSummary.append("Incident ").append(ref.incidentId())
                    .append(" [service=").append(ref.service())
                    .append(", status=").append(ref.status())
                    .append(", severity=").append(ref.severity())
                    .append(", evidenceCount=").append(ref.evidenceCount())
                    .append(", executionCount=").append(ref.executionCount()).append("]");
            if (ref.latestDiagnosisSummary() != null && !ref.latestDiagnosisSummary().isBlank()) {
                toolSummary.append(" Latest diagnosis: ").append(ref.latestDiagnosisSummary());
            }
            messages.add(new ConversationPersistenceService.GeneratedMessage(
                    ConversationMessageRole.TOOL_RESPONSE,
                    sanitizer.sanitizeContent(toolSummary.toString()),
                    linkedEvidenceId,
                    linkedExecutionId,
                    ref.incidentId()
            ));
        }

        String assistantReply;
        if (llmEnabled && LlmDiagnosisService.isUsableApiKey(apiKey)) {
            try {
                String boundedPrompt = buildBoundedPrompt(context, userText);
                String rawReply = chatClient.prompt()
                        .user(boundedPrompt)
                        .tools(copilotToolQueryService.scopedTools(context.session().environmentId()))
                        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .content();
                assistantReply = sanitizer.sanitizeContent(
                        rawReply == null || rawReply.isBlank()
                                ? "Acknowledged request in session " + conversationId
                                : rawReply
                );
            } catch (RuntimeException exception) {
                log.warn("LLM conversation reply unavailable for session {}: {}. Falling back to deterministic assistant.",
                        conversationId, exception.getMessage());
                assistantReply = generateDeterministicReply(conversationId, context, userText);
            }
        } else {
            assistantReply = generateDeterministicReply(conversationId, context, userText);
        }

        messages.add(new ConversationPersistenceService.GeneratedMessage(
                ConversationMessageRole.ASSISTANT,
                assistantReply,
                linkedEvidenceId,
                linkedExecutionId,
                linkedIncidentId
        ));

        return List.copyOf(messages);
    }

    /**
     * Hydrates Spring AI {@link ChatMemory} from PostgreSQL-persisted {@code recentHistory}
     * when in-memory chat history is empty (e.g. after control-plane restart), excluding the
     * current triggering user message so it is never duplicated.
     */
    void hydrateMemoryIfEmpty(
            String conversationId,
            ConversationPersistenceService.ConversationTurnContext context
    ) {
        if (!chatMemory.get(conversationId).isEmpty() || context.recentHistory() == null) {
            return;
        }
        UUID currentUserMsgId = context.userMessage().id();
        for (ConversationMessageResponse historical : context.recentHistory()) {
            if (historical.id().equals(currentUserMsgId)) {
                continue;
            }
            String safeContent = sanitizer.sanitizeContent(historical.content());
            if (safeContent.isBlank()) {
                continue;
            }
            if (historical.role() == ConversationMessageRole.USER) {
                chatMemory.add(conversationId, new UserMessage(safeContent));
            } else if (historical.role() == ConversationMessageRole.ASSISTANT) {
                chatMemory.add(conversationId, new AssistantMessage(safeContent));
            }
        }
    }

    private String generateDeterministicReply(
            String conversationId,
            ConversationPersistenceService.ConversationTurnContext context,
            String userText
    ) {
        // Store and read Spring AI ChatMemory strictly under this session's UUID
        chatMemory.add(conversationId, new UserMessage(userText));
        List<Message> sessionMemory = chatMemory.get(conversationId);

        long userTurnsInMemory = sessionMemory.stream()
                .filter(message -> message instanceof UserMessage)
                .count();

        StringBuilder reply = new StringBuilder();
        reply.append("[Session ").append(conversationId)
                .append(" | env=").append(context.session().environmentId())
                .append(" | turns=").append(userTurnsInMemory).append("] ");
        reply.append("Received: \"").append(truncate(userText, 240)).append("\".");

        if (context.incidentRef() != null) {
            ConversationPersistenceService.IncidentReferenceSummary ref = context.incidentRef();
            reply.append(" Referenced incident ").append(ref.incidentId())
                    .append(" (service=").append(ref.service())
                    .append(", status=").append(ref.status())
                    .append(", evidence=").append(ref.evidenceCount())
                    .append(", executions=").append(ref.executionCount()).append(").");
        }

        String safeReply = sanitizer.sanitizeContent(reply.toString());
        chatMemory.add(conversationId, new AssistantMessage(safeReply));
        return safeReply;
    }

    private String buildBoundedPrompt(
            ConversationPersistenceService.ConversationTurnContext context,
            String userText
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Environment: ").append(context.session().environmentId()).append('\n');
        if (context.incidentRef() != null) {
            ConversationPersistenceService.IncidentReferenceSummary ref = context.incidentRef();
            sb.append("Linked Incident Reference: id=").append(ref.incidentId())
                    .append(" service=").append(ref.service())
                    .append(" status=").append(ref.status())
                    .append(" severity=").append(ref.severity())
                    .append(" evidenceCount=").append(ref.evidenceCount())
                    .append(" executionCount=").append(ref.executionCount()).append('\n');
            if (ref.latestDiagnosisSummary() != null) {
                sb.append("Latest Diagnosis Summary: ").append(ref.latestDiagnosisSummary()).append('\n');
            }
        }
        sb.append("User Message: ").append(userText);
        return sb.toString();
    }

    private String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
