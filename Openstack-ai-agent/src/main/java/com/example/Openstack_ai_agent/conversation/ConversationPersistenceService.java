package com.example.Openstack_ai_agent.conversation;

import com.example.Openstack_ai_agent.diagnosis.DiagnosisEntity;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.observability.DashboardEventPublisher;
import com.example.Openstack_ai_agent.remediation.ToolExecutionEntity;
import com.example.Openstack_ai_agent.remediation.ToolExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Short-transaction persistence boundary used by {@link ConversationAssistantListener}
 * so LLM calls never hold an open database transaction.
 */
@Service
public class ConversationPersistenceService {
    private static final int TOKEN_CHUNK_SIZE = 160;
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final IncidentRepository incidentRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final DashboardEventPublisher dashboardEventPublisher;
    private final ConversationSanitizer sanitizer;

    public ConversationPersistenceService(
            ConversationSessionRepository sessionRepository,
            ConversationMessageRepository messageRepository,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            DiagnosisRepository diagnosisRepository,
            ToolExecutionRepository toolExecutionRepository,
            DashboardEventPublisher dashboardEventPublisher,
            ConversationSanitizer sanitizer
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.incidentRepository = incidentRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.dashboardEventPublisher = dashboardEventPublisher;
        this.sanitizer = sanitizer;
    }

    @Transactional(readOnly = true)
    public Optional<ConversationTurnContext> loadTurnContext(ConversationMessageSubmittedEvent event) {
        Optional<ConversationSessionEntity> sessionOpt = sessionRepository.findById(event.sessionId());
        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }
        ConversationSessionEntity session = sessionOpt.get();
        List<ConversationMessageEntity> allMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(event.sessionId());
        ConversationMessageEntity triggerMessage = allMessages.stream()
                .filter(message -> message.getId().equals(event.messageId()))
                .findFirst()
                .orElse(null);
        if (triggerMessage == null) {
            return Optional.empty();
        }

        int startIdx = Math.max(0, allMessages.size() - MAX_HISTORY_MESSAGES);
        List<ConversationMessageResponse> recentHistory = allMessages.subList(startIdx, allMessages.size())
                .stream()
                .map(ConversationMessageResponse::from)
                .toList();

        IncidentReferenceSummary incidentRef = null;
        if (event.incidentId() != null) {
            incidentRef = buildIncidentReferenceSummary(event.incidentId(), session.getEnvironmentId());
        }

        return Optional.of(new ConversationTurnContext(
                ConversationSessionResponse.from(session),
                ConversationMessageResponse.from(triggerMessage),
                recentHistory,
                incidentRef
        ));
    }

    @Transactional
    public void persistAssistantOutcome(
            UUID sessionId,
            UUID triggerMessageId,
            String environmentId,
            UUID incidentId,
            String traceId,
            List<GeneratedMessage> generatedMessages
    ) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ConversationNotFoundException(sessionId));

        ConversationMessageEntity finalMessage = null;
        for (GeneratedMessage item : generatedMessages) {
            String safeContent = sanitizer.sanitizeContent(item.content());
            ConversationMessageEntity saved = messageRepository.saveAndFlush(ConversationMessageEntity.create(
                    sessionId,
                    item.role(),
                    safeContent,
                    item.evidenceId(),
                    item.executionId(),
                    item.incidentId() != null ? item.incidentId() : incidentId
            ));
            if (item.role() == ConversationMessageRole.ASSISTANT || item.role() == ConversationMessageRole.ERROR) {
                finalMessage = saved;
            }
        }

        session.touch();
        sessionRepository.saveAndFlush(session);

        if (finalMessage != null) {
            List<String> chunks = chunkContent(finalMessage.getContent());
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> tokenPayload = new LinkedHashMap<>();
                tokenPayload.put("sessionId", sessionId.toString());
                tokenPayload.put("triggerMessageId", triggerMessageId.toString());
                tokenPayload.put("messageId", finalMessage.getId().toString());
                tokenPayload.put("role", finalMessage.getMessageRole().name());
                tokenPayload.put("chunkIndex", i);
                tokenPayload.put("token", chunks.get(i));
                dashboardEventPublisher.publish(
                        environmentId,
                        "conversation.token",
                        incidentId,
                        traceId,
                        tokenPayload
                );
            }

            Map<String, Object> completedPayload = new LinkedHashMap<>();
            completedPayload.put("sessionId", sessionId.toString());
            completedPayload.put("triggerMessageId", triggerMessageId.toString());
            completedPayload.put("messageId", finalMessage.getId().toString());
            completedPayload.put("role", finalMessage.getMessageRole().name());
            completedPayload.put("status", finalMessage.getMessageRole() == ConversationMessageRole.ERROR ? "ERROR" : "COMPLETED");
            completedPayload.put("messageCount", generatedMessages.size());
            if (finalMessage.getEvidenceId() != null) {
                completedPayload.put("evidenceId", finalMessage.getEvidenceId().toString());
            }
            if (finalMessage.getExecutionId() != null) {
                completedPayload.put("executionId", finalMessage.getExecutionId().toString());
            }
            dashboardEventPublisher.publish(
                    environmentId,
                    "conversation.completed",
                    incidentId,
                    traceId,
                    completedPayload
            );
        }
    }

    private IncidentReferenceSummary buildIncidentReferenceSummary(UUID incidentId, String expectedEnvironmentId) {
        Optional<IncidentEntity> incidentOpt = incidentRepository.findById(incidentId);
        if (incidentOpt.isEmpty()) {
            return null;
        }
        IncidentEntity incident = incidentOpt.get();
        if (!expectedEnvironmentId.equals(incident.getEnvironmentId())) {
            return null;
        }

        List<EvidenceItemEntity> evidence = evidenceItemRepository.findByIncidentIdOrderByObservedAtDesc(incidentId);
        UUID latestEvidenceId = evidence.isEmpty() ? null : evidence.getFirst().getId();

        List<ToolExecutionEntity> executions = toolExecutionRepository.findByIncidentIdOrderByStartedAtAsc(incidentId);
        UUID latestExecutionId = executions.isEmpty() ? null : executions.getLast().getId();

        String latestDiagnosisSummary = diagnosisRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .findFirst()
                .map(DiagnosisEntity::getSummary)
                .map(s -> sanitizer.sanitizeContent(s.length() <= 300 ? s : s.substring(0, 300)))
                .orElse(null);

        return new IncidentReferenceSummary(
                incident.getId(),
                incident.getService(),
                incident.getStatus().name(),
                incident.getSeverity().name(),
                incident.getTraceId(),
                evidence.size(),
                latestEvidenceId,
                executions.size(),
                latestExecutionId,
                latestDiagnosisSummary
        );
    }

    private List<String> chunkContent(String content) {
        if (content == null || content.isEmpty()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < content.length(); start += TOKEN_CHUNK_SIZE) {
            chunks.add(content.substring(start, Math.min(content.length(), start + TOKEN_CHUNK_SIZE)));
        }
        return chunks;
    }

    public record ConversationTurnContext(
            ConversationSessionResponse session,
            ConversationMessageResponse userMessage,
            List<ConversationMessageResponse> recentHistory,
            IncidentReferenceSummary incidentRef
    ) {
    }

    public record IncidentReferenceSummary(
            UUID incidentId,
            String service,
            String status,
            String severity,
            String traceId,
            int evidenceCount,
            UUID latestEvidenceId,
            int executionCount,
            UUID latestExecutionId,
            String latestDiagnosisSummary
    ) {
    }

    public record GeneratedMessage(
            ConversationMessageRole role,
            String content,
            UUID evidenceId,
            UUID executionId,
            UUID incidentId
    ) {
    }
}
