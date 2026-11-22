package com.example.Openstack_ai_agent.diagnosis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "health_snapshots")
public class HealthSnapshotEntity {
    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_phase", nullable = false, length = 32)
    private HealthSnapshotPhase snapshotPhase;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> signals;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private OffsetDateTime capturedAt;

    protected HealthSnapshotEntity() {
    }

    public static HealthSnapshotEntity of(
            UUID incidentId,
            HealthSnapshotPhase snapshotPhase,
            Map<String, Object> signals
    ) {
        HealthSnapshotEntity entity = new HealthSnapshotEntity();
        entity.id = UUID.randomUUID();
        entity.incidentId = incidentId;
        entity.snapshotPhase = snapshotPhase;
        entity.signals = signals == null ? Map.of() : signals;
        entity.capturedAt = OffsetDateTime.now();
        return entity;
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public HealthSnapshotPhase getSnapshotPhase() { return snapshotPhase; }
    public Map<String, Object> getSignals() { return signals; }
    public OffsetDateTime getCapturedAt() { return capturedAt; }
}
