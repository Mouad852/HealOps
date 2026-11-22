package com.example.Openstack_ai_agent.conversation;

import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.remediation.ToolExecutionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ConversationService {
    private static final String ENV_AUTHORITY_PREFIX = "env:";

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final IncidentRepository incidentRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final ConversationSanitizer sanitizer;
    private final ApplicationEventPublisher eventPublisher;

    public ConversationService(
            ConversationSessionRepository sessionRepository,
            ConversationMessageRepository messageRepository,
            IncidentRepository incidentRepository,
            EvidenceItemRepository evidenceItemRepository,
            ToolExecutionRepository toolExecutionRepository,
            ConversationSanitizer sanitizer,
            ApplicationEventPublisher eventPublisher
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.incidentRepository = incidentRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.sanitizer = sanitizer;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ConversationSessionResponse createSession(
            CreateConversationRequest request,
            Authentication authentication
    ) {
        String environmentId = request.environmentId().trim();
        requireEnvironmentAuthority(authentication, environmentId);

        String title = sanitizer.sanitizeTitle(request.title());
        ConversationSessionEntity saved = sessionRepository.saveAndFlush(
                ConversationSessionEntity.create(environmentId, title)
        );
        return ConversationSessionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ConversationSessionResponse> listSessions(
            String environmentId,
            Authentication authentication
    ) {
        Set<String> authorizedEnvironments = extractAuthorizedEnvironments(authentication);
        if (authorizedEnvironments.isEmpty()) {
            return List.of();
        }
        if (environmentId != null && !environmentId.isBlank()) {
            String trimmed = environmentId.trim();
            if (!authorizedEnvironments.contains(trimmed)) {
                return List.of();
            }
            return sessionRepository.findByEnvironmentIdOrderByUpdatedAtDesc(trimmed).stream()
                    .map(ConversationSessionResponse::from)
                    .toList();
        }
        return sessionRepository.findByEnvironmentIdInOrderByUpdatedAtDesc(authorizedEnvironments).stream()
                .map(ConversationSessionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailResponse getSession(UUID sessionId, Authentication authentication) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ConversationNotFoundException(sessionId));
        requireEnvironmentAuthority(authentication, session.getEnvironmentId());
        List<ConversationMessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return ConversationDetailResponse.from(session, messages);
    }

    @Transactional
    public PostConversationMessageResponse postMessage(
            UUID sessionId,
            PostConversationMessageRequest request,
            Authentication authentication
    ) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ConversationNotFoundException(sessionId));
        requireEnvironmentAuthority(authentication, session.getEnvironmentId());

        validateOptionalReferences(session.getEnvironmentId(), request);

        String sanitizedContent = sanitizer.sanitizeContent(request.content());
        if (sanitizedContent.isBlank()) {
            throw new IllegalArgumentException("Message content must not be blank");
        }

        ConversationMessageEntity userMessage = messageRepository.saveAndFlush(
                ConversationMessageEntity.create(
                        sessionId,
                        ConversationMessageRole.USER,
                        sanitizedContent,
                        request.evidenceId(),
                        request.executionId(),
                        request.incidentId()
                )
        );
        session.touch();
        sessionRepository.saveAndFlush(session);

        String actor = authentication != null && authentication.getName() != null
                ? authentication.getName()
                : "operator";

        eventPublisher.publishEvent(new ConversationMessageSubmittedEvent(
                sessionId,
                userMessage.getId(),
                session.getEnvironmentId(),
                actor,
                request.incidentId(),
                request.evidenceId(),
                request.executionId()
        ));

        return PostConversationMessageResponse.accepted(userMessage);
    }

    private void validateOptionalReferences(String sessionEnvironmentId, PostConversationMessageRequest request) {
        if (request.incidentId() != null) {
            IncidentEntity incident = incidentRepository.findById(request.incidentId())
                    .orElseThrow(() -> new IllegalArgumentException("Referenced incident not found: " + request.incidentId()));
            if (!sessionEnvironmentId.equals(incident.getEnvironmentId())) {
                throw new AccessDeniedException("Referenced incident belongs to a different environment");
            }
        }
        if (request.evidenceId() != null && !evidenceItemRepository.existsById(request.evidenceId())) {
            throw new IllegalArgumentException("Referenced evidence item not found: " + request.evidenceId());
        }
        if (request.executionId() != null && !toolExecutionRepository.existsById(request.executionId())) {
            throw new IllegalArgumentException("Referenced tool execution not found: " + request.executionId());
        }
    }

    private void requireEnvironmentAuthority(Authentication authentication, String environmentId) {
        Set<String> authorizedEnvironments = extractAuthorizedEnvironments(authentication);
        if (!authorizedEnvironments.contains(environmentId)) {
            throw new AccessDeniedException("Caller is not authorized for environment '" + environmentId + "'");
        }
    }

    private Set<String> extractAuthorizedEnvironments(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(auth -> auth != null && auth.startsWith(ENV_AUTHORITY_PREFIX) && auth.length() > ENV_AUTHORITY_PREFIX.length())
                .map(auth -> auth.substring(ENV_AUTHORITY_PREFIX.length()))
                .collect(Collectors.toSet());
    }
}
