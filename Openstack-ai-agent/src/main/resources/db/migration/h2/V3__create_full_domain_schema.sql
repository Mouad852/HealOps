-- V3__create_full_domain_schema.sql (H2)

create table environments (
    id varchar(100) primary key,
    name varchar(200) not null,
    kind varchar(50) not null,
    display_label varchar(200) not null,
    enabled boolean not null default true,
    capabilities json not null,
    created_at timestamp with time zone not null
);

create table diagnoses (
    id uuid primary key,
    incident_id uuid not null references incidents(id),
    summary varchar(4000) not null,
    hypotheses json not null,
    confidence numeric(5,2) not null,
    cited_evidence_ids json not null,
    model_version varchar(100) not null,
    rule_version varchar(100) not null,
    created_at timestamp with time zone not null
);

create index idx_diagnoses_incident_id on diagnoses(incident_id);

create table proposed_actions (
    id uuid primary key,
    incident_id uuid not null references incidents(id),
    diagnosis_id uuid references diagnoses(id),
    action_type varchar(64) not null,
    arguments json not null,
    risk_level varchar(32) not null check (risk_level in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    rollback_strategy varchar(100),
    status varchar(32) not null check (status in ('PROPOSED', 'APPROVED', 'REJECTED', 'EXECUTED', 'FAILED')),
    version bigint not null default 0,
    created_at timestamp with time zone not null
);

create index idx_proposed_actions_incident on proposed_actions(incident_id);

create table approval_decisions (
    id uuid primary key,
    action_id uuid not null references proposed_actions(id),
    decision varchar(32) not null check (decision in ('APPROVED', 'REJECTED')),
    actor varchar(100) not null,
    reason varchar(1000),
    decided_at timestamp with time zone not null,
    expected_version bigint not null,
    idempotency_key varchar(200) not null unique
);

create table tool_executions (
    id uuid primary key,
    action_id uuid references proposed_actions(id),
    incident_id uuid references incidents(id),
    idempotency_key varchar(200) not null unique,
    attempt int not null default 1,
    started_at timestamp with time zone not null,
    ended_at timestamp with time zone,
    status varchar(32) not null check (status in ('RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT')),
    sanitized_input json not null,
    sanitized_output json not null,
    trace_id varchar(128)
);

create index idx_tool_executions_incident on tool_executions(incident_id);

create table health_snapshots (
    id uuid primary key,
    incident_id uuid not null references incidents(id),
    snapshot_phase varchar(32) not null check (snapshot_phase in ('BEFORE', 'AFTER', 'POST_ROLLBACK')),
    signals json not null,
    captured_at timestamp with time zone not null
);

create table incident_reports (
    id uuid primary key,
    incident_id uuid not null unique references incidents(id),
    structured_report json not null,
    rendered_markdown clob not null,
    final_outcome varchar(64) not null,
    generated_at timestamp with time zone not null
);

create table conversation_sessions (
    id uuid primary key,
    environment_id varchar(100) not null,
    title varchar(200) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table conversation_messages (
    id uuid primary key,
    session_id uuid not null references conversation_sessions(id),
    message_role varchar(32) not null check (message_role in ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL_REQUEST', 'TOOL_RESPONSE', 'ERROR')),
    content clob not null,
    evidence_id uuid references evidence_items(id),
    execution_id uuid references tool_executions(id),
    incident_id uuid references incidents(id),
    created_at timestamp with time zone not null
);

create index idx_conversation_messages_session on conversation_messages(session_id, created_at asc);

create table audit_records (
    id uuid primary key,
    actor varchar(100) not null,
    operation varchar(100) not null,
    target_resource varchar(200) not null,
    outcome varchar(32) not null check (outcome in ('SUCCESS', 'DENIED', 'FAILED')),
    details json not null,
    trace_id varchar(128),
    occurred_at timestamp with time zone not null
);

create index idx_audit_records_occurred_at on audit_records(occurred_at desc);

create table runbooks (
    id uuid primary key,
    name varchar(200) not null,
    status varchar(32) not null check (status in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    current_version int not null default 1,
    match_criteria json not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table runbook_versions (
    id uuid primary key,
    runbook_id uuid not null references runbooks(id),
    version_number int not null,
    typed_steps json not null,
    policy_requirements json not null,
    published_by varchar(100) not null,
    created_at timestamp with time zone not null,
    constraint uk_runbook_versions unique (runbook_id, version_number)
);

create table remediation_outcomes (
    id uuid primary key,
    action_id uuid references proposed_actions(id),
    incident_id uuid not null references incidents(id),
    action_type varchar(64) not null,
    success boolean not null,
    duration_ms bigint not null,
    rollback_triggered boolean not null default false,
    rollback_success boolean,
    created_at timestamp with time zone not null
);

create table dashboard_events (
    id uuid primary key,
    sequence_number bigserial not null,
    environment_id varchar(100) not null,
    event_type varchar(64) not null,
    incident_id uuid,
    trace_id varchar(128),
    payload json not null,
    occurred_at timestamp with time zone not null
);

create index idx_dashboard_events_env_seq on dashboard_events(environment_id, sequence_number asc);
