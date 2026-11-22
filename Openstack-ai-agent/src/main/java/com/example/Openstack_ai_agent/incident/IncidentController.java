package com.example.Openstack_ai_agent.incident;

import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {
    private final IncidentService incidentService;
    private final EvidenceCollectionService evidenceCollectionService;

    public IncidentController(IncidentService incidentService, EvidenceCollectionService evidenceCollectionService) {
        this.incidentService = incidentService;
        this.evidenceCollectionService = evidenceCollectionService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('incident:write')")
    public ResponseEntity<IncidentResponse> create(@Valid @RequestBody CreateIncidentRequest request) {
        IncidentService.IncidentCreationResult result = incidentService.create(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.incident());
    }

    @PostMapping("/webhooks/alertmanager")
    @PreAuthorize("hasAuthority('incident:write')")
    public ResponseEntity<List<IncidentResponse>> ingestAlertmanagerWebhook(
            @Valid @RequestBody AlertmanagerWebhookRequest webhook,
            @RequestParam(defaultValue = "local") String defaultEnvironmentId
    ) {
        List<IncidentResponse> results = new ArrayList<>();
        for (AlertmanagerWebhookRequest.AlertItem alert : webhook.alerts()) {
            if (alert.status() != null && !alert.status().equalsIgnoreCase("firing")) {
                continue;
            }
            results.add(incidentService.create(alert.toCreateIncidentRequest(defaultEnvironmentId)).incident());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(List.copyOf(results));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('incident:read')")
    public List<IncidentResponse> findAll(
            @RequestParam(required = false) String environmentId,
            @RequestParam(required = false) IncidentStatus status
    ) {
        return incidentService.findAll(environmentId, status);
    }

    @GetMapping("/{incidentId}")
    @PreAuthorize("hasAuthority('incident:read')")
    public IncidentResponse findById(@PathVariable UUID incidentId) {
        return incidentService.findById(incidentId);
    }

    @PostMapping("/{incidentId}/transitions")
    @PreAuthorize("hasAuthority('incident:write')")
    public IncidentResponse transition(
            @PathVariable UUID incidentId,
            @Valid @RequestBody TransitionIncidentRequest request,
            Authentication authentication
    ) {
        String authenticatedActor = authentication != null && authentication.getName() != null
                ? authentication.getName()
                : request.actor();
        TransitionIncidentRequest governed = new TransitionIncidentRequest(
                request.event(),
                request.failureDisposition(),
                authenticatedActor,
                request.reason(),
                request.traceId(),
                request.metadata(),
                request.expectedVersion()
        );
        return incidentService.transition(incidentId, governed);
    }

    @PostMapping("/{incidentId}/retry")
    @PreAuthorize("hasAuthority('incident:write')")
    public IncidentResponse retry(
            @PathVariable UUID incidentId,
            @Valid @RequestBody RetryIncidentRequest request,
            Authentication authentication
    ) {
        String actor = authentication != null && authentication.getName() != null
                ? authentication.getName()
                : "operator";
        return incidentService.retryFailedPhase(incidentId, actor, request.reason(), request.expectedVersion());
    }

    @GetMapping("/{incidentId}/timeline")
    @PreAuthorize("hasAuthority('incident:read')")
    public List<IncidentEventResponse> findTimeline(@PathVariable UUID incidentId) {
        return incidentService.findTimeline(incidentId);
    }

    @PostMapping("/{incidentId}/evidence/collect")
    @PreAuthorize("hasAuthority('incident:write')")
    public List<EvidenceItemResponse> collectEvidence(@PathVariable UUID incidentId) {
        return evidenceCollectionService.collect(incidentId);
    }

    @GetMapping("/{incidentId}/evidence")
    @PreAuthorize("hasAuthority('incident:read')")
    public List<EvidenceItemResponse> findEvidence(@PathVariable UUID incidentId) {
        return evidenceCollectionService.findAll(incidentId);
    }

    public record RetryIncidentRequest(
            @Size(max = 500) String reason,
            @PositiveOrZero long expectedVersion
    ) {
    }
}
