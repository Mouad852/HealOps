package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HealthSnapshotService {
    private final HealthSnapshotRepository healthSnapshotRepository;
    private final IncidentService incidentService;

    public HealthSnapshotService(
            HealthSnapshotRepository healthSnapshotRepository,
            IncidentService incidentService
    ) {
        this.healthSnapshotRepository = healthSnapshotRepository;
        this.incidentService = incidentService;
    }

    @Transactional
    public HealthSnapshotResponse captureBeforeSnapshot(
            IncidentResponse incident,
            List<EvidenceItemResponse> evidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics
    ) {
        return captureSnapshot(HealthSnapshotPhase.BEFORE, incident, evidence, correlation, diagnostics);
    }

    @Transactional
    public HealthSnapshotResponse captureAfterSnapshot(
            IncidentResponse incident,
            List<EvidenceItemResponse> evidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics
    ) {
        return captureSnapshot(HealthSnapshotPhase.AFTER, incident, evidence, correlation, diagnostics);
    }

    @Transactional
    public HealthSnapshotResponse capturePostRollbackSnapshot(
            IncidentResponse incident,
            List<EvidenceItemResponse> evidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics
    ) {
        return captureSnapshot(HealthSnapshotPhase.POST_ROLLBACK, incident, evidence, correlation, diagnostics);
    }

    @Transactional
    public HealthSnapshotResponse captureSnapshot(
            HealthSnapshotPhase phase,
            IncidentResponse incident,
            List<EvidenceItemResponse> evidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics
    ) {
        Map<String, Object> signals = buildNormalizedSignals(incident, evidence, correlation, diagnostics);
        HealthSnapshotEntity entity = HealthSnapshotEntity.of(
                incident.id(),
                phase,
                signals
        );
        return HealthSnapshotResponse.from(healthSnapshotRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public HealthComparisonResponse getHealthComparisons(UUID incidentId) {
        incidentService.findById(incidentId);
        List<HealthSnapshotResponse> snapshots = healthSnapshotRepository
                .findByIncidentIdOrderByCapturedAtAsc(incidentId)
                .stream()
                .map(HealthSnapshotResponse::from)
                .toList();

        HealthSnapshotResponse before = latestForPhase(snapshots, HealthSnapshotPhase.BEFORE);
        HealthSnapshotResponse after = latestForPhase(snapshots, HealthSnapshotPhase.AFTER);
        HealthSnapshotResponse postRollback = latestForPhase(snapshots, HealthSnapshotPhase.POST_ROLLBACK);

        return new HealthComparisonResponse(incidentId, before, after, postRollback, snapshots);
    }

    private Map<String, Object> buildNormalizedSignals(
            IncidentResponse incident,
            List<EvidenceItemResponse> evidence,
            CorrelationService.CorrelationSummary correlation,
            DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult diagnostics
    ) {
        DeterministicDiagnosticsEngine.ExtractedHealthSignals extracted = diagnostics.healthSignals();
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("service", incident.service());
        signals.put("environmentId", incident.environmentId());
        signals.put("severity", incident.severity().name());
        signals.put("telemetryStatus", extracted.telemetryStatus());
        signals.put("healthStatus", extracted.healthStatus());
        signals.put("workloadStatus", extracted.workloadStatus());
        signals.put("evidenceCount", evidence.size());
        signals.put("collectionErrorCount", correlation.collectionErrorCount());
        signals.put("matchedTraceCount", correlation.matchedTraceCount());
        signals.put("sources", correlation.sources());
        signals.put("failedSources", extracted.failedSources());
        signals.put("activeFindings", extracted.activeFindings());
        signals.put("probeFailures", extracted.probeFailures());
        signals.put("oomDetected", extracted.oomDetected());
        signals.put("correlationKeys", correlation.correlationKeys());
        if (correlation.windowStart() != null) {
            signals.put("windowStart", correlation.windowStart().toString());
        }
        if (correlation.windowEnd() != null) {
            signals.put("windowEnd", correlation.windowEnd().toString());
        }
        if (extracted.restartCount() != null) {
            signals.put("restartCount", extracted.restartCount());
        }
        if (extracted.errorRatePercent() != null) {
            signals.put("errorRatePercent", extracted.errorRatePercent());
        }
        if (extracted.latencyMs() != null) {
            signals.put("latencyMs", extracted.latencyMs());
        }
        if (extracted.cpuPercent() != null) {
            signals.put("cpuPercent", extracted.cpuPercent());
        }
        if (extracted.memoryPercent() != null) {
            signals.put("memoryPercent", extracted.memoryPercent());
        }
        return signals;
    }

    private HealthSnapshotResponse latestForPhase(
            List<HealthSnapshotResponse> snapshots,
            HealthSnapshotPhase phase
    ) {
        HealthSnapshotResponse match = null;
        for (HealthSnapshotResponse snapshot : snapshots) {
            if (snapshot.snapshotPhase() == phase) {
                match = snapshot;
            }
        }
        return match;
    }
}
