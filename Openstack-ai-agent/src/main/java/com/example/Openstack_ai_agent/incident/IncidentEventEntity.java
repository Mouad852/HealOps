package com.example.Openstack_ai_agent.incident;

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
@Table(name = "incident_events")
public class IncidentEventEntity {
    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "event_sequence", nullable = false)
    private long eventSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private IncidentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private IncidentStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private IncidentWorkflowEvent eventType;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(length = 500)
    private String reason;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> metadata;

    protected IncidentEventEntity() {
    }

    public static IncidentEventEntity alertReceived(IncidentEntity incident) {
        IncidentEventEntity event = new IncidentEventEntity();
        event.id = UUID.randomUUID();
        event.incidentId = incident.getId();
        event.eventSequence = 1;
        event.toStatus = IncidentStatus.ALERTED;
        event.eventType = IncidentWorkflowEvent.ALERT_RECEIVED;
        event.actor = incident.getSource();
        event.reason = "Alert received";
        event.traceId = incident.getTraceId();
        event.occurredAt = OffsetDateTime.now();
        event.metadata = Map.of("fingerprint", incident.getFingerprint());
        return event;
    }

    public static IncidentEventEntity transition(
            IncidentEntity incident,
            long sequence,
            IncidentStatus fromStatus,
            IncidentStatus toStatus,
            TransitionIncidentRequest request
    ) {
        IncidentEventEntity event = new IncidentEventEntity();
        event.id = UUID.randomUUID();
        event.incidentId = incident.getId();
        event.eventSequence = sequence;
        event.fromStatus = fromStatus;
        event.toStatus = toStatus;
        event.eventType = request.event();
        event.actor = request.actor();
        event.reason = request.reason();
        event.traceId = request.traceId();
        event.occurredAt = OffsetDateTime.now();
        event.metadata = request.metadata();
        return event;
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public long getEventSequence() { return eventSequence; }
    public IncidentStatus getFromStatus() { return fromStatus; }
    public IncidentStatus getToStatus() { return toStatus; }
    public IncidentWorkflowEvent getEventType() { return eventType; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public String getTraceId() { return traceId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public Map<String, String> getMetadata() { return metadata; }
}
