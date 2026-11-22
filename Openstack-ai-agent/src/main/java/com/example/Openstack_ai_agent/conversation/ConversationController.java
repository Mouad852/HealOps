package com.example.Openstack_ai_agent.conversation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('incident:read')")
    public ResponseEntity<ConversationSessionResponse> createSession(
            @Valid @RequestBody CreateConversationRequest request,
            Authentication authentication
    ) {
        ConversationSessionResponse created = conversationService.createSession(request, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('incident:read')")
    public List<ConversationSessionResponse> listSessions(
            @RequestParam(required = false) String environmentId,
            Authentication authentication
    ) {
        return conversationService.listSessions(environmentId, authentication);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('incident:read')")
    public ConversationDetailResponse getSession(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return conversationService.getSession(id, authentication);
    }

    @PostMapping("/{id}/messages")
    @PreAuthorize("hasAuthority('incident:read')")
    public ResponseEntity<PostConversationMessageResponse> postMessage(
            @PathVariable UUID id,
            @Valid @RequestBody PostConversationMessageRequest request,
            Authentication authentication
    ) {
        PostConversationMessageResponse response = conversationService.postMessage(id, request, authentication);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
