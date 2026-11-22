package com.example.Openstack_ai_agent.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.Optional;

/**
 * Processes submitted conversation messages asynchronously outside any database transaction.
 */
@Component
public class ConversationAssistantListener {
    private static final Logger log = LoggerFactory.getLogger(ConversationAssistantListener.class);

    private final ConversationPersistenceService persistenceService;
    private final ConversationAssistantService assistantService;

    public ConversationAssistantListener(
            ConversationPersistenceService persistenceService,
            ConversationAssistantService assistantService
    ) {
        this.persistenceService = persistenceService;
        this.assistantService = assistantService;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onMessageSubmitted(ConversationMessageSubmittedEvent event) {
        Optional<ConversationPersistenceService.ConversationTurnContext> contextOpt =
                persistenceService.loadTurnContext(event);
        if (contextOpt.isEmpty()) {
            log.debug("Skipping assistant turn for missing session {} / message {}", event.sessionId(), event.messageId());
            return;
        }

        ConversationPersistenceService.ConversationTurnContext context = contextOpt.get();
        String traceId = context.incidentRef() != null ? context.incidentRef().traceId() : null;

        List<ConversationPersistenceService.GeneratedMessage> generated;
        try {
            generated = assistantService.processTurn(context);
        } catch (RuntimeException exception) {
            log.warn("Assistant processing failed for session {}: {}", event.sessionId(), exception.getMessage());
            generated = List.of(new ConversationPersistenceService.GeneratedMessage(
                    ConversationMessageRole.ERROR,
                    "Assistant response failed: " + exception.getClass().getSimpleName(),
                    event.evidenceId(),
                    event.executionId(),
                    event.incidentId()
            ));
        }

        persistenceService.persistAssistantOutcome(
                event.sessionId(),
                event.messageId(),
                event.environmentId(),
                event.incidentId(),
                traceId,
                generated
        );
    }
}
