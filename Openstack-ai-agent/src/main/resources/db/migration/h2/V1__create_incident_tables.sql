create table incidents (
    id uuid primary key,
    source varchar(100) not null,
    external_alert_id varchar(200),
    fingerprint varchar(512) not null,
    environment_id varchar(100) not null,
    severity varchar(16) not null check (severity in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    service varchar(200) not null,
    status varchar(32) not null check (status in ('ALERTED', 'GATHERING_EVIDENCE', 'CORRELATING_SIGNALS', 'DIAGNOSING', 'AWAITING_APPROVAL', 'EXECUTING', 'VERIFYING', 'CLOSED', 'ROLLING_BACK', 'ROLLED_BACK', 'FAILED', 'CANCELLED')),
    started_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    created_by varchar(100) not null,
    version bigint not null default 0,
    labels json not null,
    annotations json not null,
    trace_id varchar(128),
    constraint uk_incidents_deduplication unique (source, environment_id, fingerprint)
);

create index idx_incidents_environment_status_created_at
    on incidents (environment_id, status, created_at desc);

create table incident_events (
    id uuid primary key,
    incident_id uuid not null references incidents(id),
    event_sequence bigint not null,
    from_status varchar(32),
    to_status varchar(32) not null,
    event_type varchar(64) not null,
    actor varchar(100) not null,
    reason varchar(500),
    trace_id varchar(128),
    occurred_at timestamp with time zone not null,
    metadata json not null,
    constraint uk_incident_events_sequence unique (incident_id, event_sequence)
);

create index idx_incident_events_incident_sequence
    on incident_events (incident_id, event_sequence);
