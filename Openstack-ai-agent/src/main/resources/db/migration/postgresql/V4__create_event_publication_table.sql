-- V4__create_event_publication_table.sql (PostgreSQL)
-- Supports Spring Modulith 1.4 JPA Event Publication Registry for durable workflow orchestration.

create table event_publication (
    id uuid not null primary key,
    listener_id varchar(512) not null,
    event_type varchar(512) not null,
    serialized_event text not null,
    publication_date timestamp with time zone not null,
    completion_date timestamp with time zone
);

create index idx_event_publication_serialized_listener
    on event_publication (serialized_event, listener_id);

create index idx_event_publication_completion_date
    on event_publication (completion_date);
