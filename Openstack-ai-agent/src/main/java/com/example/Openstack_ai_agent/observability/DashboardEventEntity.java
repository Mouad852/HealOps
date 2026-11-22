package com.example.Openstack_ai_agent.observability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "dashboard_events")
public class DashboardEventEntity {
    @Id
    private UUID id;

    @Generated(event = EventType.INSERT)
    @Column(name = "sequence_number", insertable = false, updatable = false)
    private Long sequenceNumber;

    @Column(name = "environment_id", nullable = false, length = 100)
    private String environmentId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected DashboardEventEntity() {
    }

    public static DashboardEventEntity of(
            String environmentId,
            String eventType,
            UUID incidentId,
            String traceId,
            Map<String, Object> payload
    ) {
        DashboardEventEntity event = new DashboardEventEntity();
        event.id = UUID.randomUUID();
        event.environmentId = environmentId;
        event.eventType = eventType;
        event.incidentId = incidentId;
        event.traceId = traceId;
        event.payload = payload == null ? Map.of() : Map.copyOf(payload);
        event.occurredAt = OffsetDateTime.now();
        return event;
    }

    public UUID getId() { return id; }
    public Long getSequenceNumber() { return sequenceNumber; }
    public String getEnvironmentId() { return environmentId; }
    public String getEventType() { return eventType; }
    public UUID getIncidentId() { return incidentId; }
    public String getTraceId() { return traceId; }
    public Map<String, Object> getPayload() { return payload; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
