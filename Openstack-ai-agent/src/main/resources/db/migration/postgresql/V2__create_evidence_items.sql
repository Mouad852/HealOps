create table evidence_items (
    id uuid primary key,
    incident_id uuid not null references incidents(id),
    evidence_type varchar(32) not null check (evidence_type in ('ALERT_CONTEXT', 'METRIC', 'LOG', 'KUBERNETES_EVENT', 'OPENSTACK_EVENT', 'TRACE', 'COLLECTION_ERROR')),
    source varchar(100) not null,
    target varchar(200),
    observed_at timestamp with time zone not null,
    window_started_at timestamp with time zone,
    window_ended_at timestamp with time zone,
    trace_id varchar(128),
    excerpt varchar(8192) not null,
    content_hash varchar(64) not null,
    redaction_status varchar(32) not null check (redaction_status in ('NOT_REQUIRED', 'REDACTED')),
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamp with time zone not null
);

create index idx_evidence_items_incident_observed_at
    on evidence_items (incident_id, observed_at desc);
