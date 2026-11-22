package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentDiagnosisController {
    private final DiagnosisPersistenceService diagnosisPersistenceService;
    private final HealthSnapshotService healthSnapshotService;

    public IncidentDiagnosisController(
            DiagnosisPersistenceService diagnosisPersistenceService,
            HealthSnapshotService healthSnapshotService
    ) {
        this.diagnosisPersistenceService = diagnosisPersistenceService;
        this.healthSnapshotService = healthSnapshotService;
    }

    @GetMapping("/{incidentId}/diagnoses")
    @PreAuthorize("hasAuthority('incident:read')")
    public List<DiagnosisResponse> findDiagnoses(@PathVariable UUID incidentId) {
        return diagnosisPersistenceService.findDiagnoses(incidentId);
    }

    @GetMapping("/{incidentId}/proposed-actions")
    @PreAuthorize("hasAuthority('incident:read')")
    public List<ProposedActionResponse> findProposedActions(@PathVariable UUID incidentId) {
        return diagnosisPersistenceService.findProposedActions(incidentId);
    }

    @GetMapping("/{incidentId}/health-comparisons")
    @PreAuthorize("hasAuthority('incident:read')")
    public HealthComparisonResponse findHealthComparisons(@PathVariable UUID incidentId) {
        return healthSnapshotService.getHealthComparisons(incidentId);
    }
}
