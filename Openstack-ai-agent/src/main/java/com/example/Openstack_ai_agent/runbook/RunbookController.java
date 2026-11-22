package com.example.Openstack_ai_agent.runbook;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runbooks")
public class RunbookController {
    private final RunbookService runbookService;

    public RunbookController(RunbookService runbookService) {
        this.runbookService = runbookService;
    }

    @PostMapping("/draft-from-incident/{incidentId}")
    @PreAuthorize("hasAuthority('incident:write')")
    public ResponseEntity<RunbookDetailResponse> draftFromIncident(
            @PathVariable UUID incidentId,
            @Valid @RequestBody(required = false) DraftRunbookFromIncidentRequest request,
            Authentication authentication
    ) {
        RunbookDetailResponse drafted = runbookService.draftFromIncident(incidentId, request, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(drafted);
    }

    @PutMapping("/{runbookId}")
    @PreAuthorize("hasAuthority('incident:write')")
    public RunbookDetailResponse updateRunbook(
            @PathVariable UUID runbookId,
            @Valid @RequestBody UpdateRunbookRequest request,
            Authentication authentication
    ) {
        return runbookService.updateRunbook(runbookId, request, authentication);
    }

    @PostMapping("/{runbookId}/publish")
    @PreAuthorize("hasAuthority('runbook:publish')")
    public RunbookDetailResponse publishRunbook(
            @PathVariable UUID runbookId,
            Authentication authentication
    ) {
        return runbookService.publishRunbook(runbookId, authentication);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('incident:read')")
    public List<RunbookDetailResponse> listRunbooks(
            @RequestParam(required = false) RunbookStatus status
    ) {
        return runbookService.listRunbooks(status);
    }

    @GetMapping("/{runbookId}")
    @PreAuthorize("hasAuthority('incident:read')")
    public RunbookDetailResponse getRunbook(@PathVariable UUID runbookId) {
        return runbookService.getRunbook(runbookId);
    }

    @GetMapping("/suggestions")
    @PreAuthorize("hasAuthority('incident:read')")
    public List<RunbookSuggestionResponse> suggestForIncident(@RequestParam UUID incidentId) {
        return runbookService.suggestForIncident(incidentId);
    }
}
