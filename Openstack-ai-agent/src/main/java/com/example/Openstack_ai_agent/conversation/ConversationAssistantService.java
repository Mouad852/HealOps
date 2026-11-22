package com.example.Openstack_ai_agent.conversation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
    private static final String SYSTEM_PROMPT = """
            You are HealOps Incident Autopilot's operations assistant.
            Respond concisely using only the provided bounded, redacted conversation context and
            incident/evidence/execution references.
            Strict rules:
            1. Never repeat secrets, credentials, or raw unbounded logs.
            2. Never emit executable shell commands or claim to bypass operator approval/policy.
            3. Reference evidence items and tool executions by their UUIDs.
            """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ConversationSanitizer sanitizer;
    private final boolean llmEnabled;

    public ConversationAssistantService(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            ConversationSanitizer sanitizer,
            @Value("${healops.conversation.llm-enabled:false}") boolean llmEnabled
    ) {
        this.chatMemory = chatMemory;
        this.sanitizer = sanitizer;
        this.llmEnabled = llmEnabled;
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
        if (llmEnabled) {
            String boundedPrompt = buildBoundedPrompt(context, userText);
            String rawReply = chatClient.prompt()
                    .user(boundedPrompt)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
            assistantReply = sanitizer.sanitizeContent(
                    rawReply == null || rawReply.isBlank()
                            ? "Acknowledged request in session " + conversationId
                            : rawReply
            );
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
