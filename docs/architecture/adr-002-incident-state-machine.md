# ADR-002: Guarded incident state machine

## Decision

Incident lifecycle changes are controlled by `IncidentWorkflowEvent` and an explicit compile-time transition map. The `IncidentWorkflowService` is the only code path that mutates an incident status. Each accepted transition uses optimistic versioning and appends one immutable `incident_events` record in the same transaction.

The LLM, browser, and infrastructure adapters may supply bounded evidence or requests, but they never choose or directly set an incident state.

## Allowed transitions

| Current status | Event | Next status |
| --- | --- | --- |
| — | `ALERT_RECEIVED` | `ALERTED` |
| `ALERTED` | `COLLECTION_STARTED` | `GATHERING_EVIDENCE` |
| `GATHERING_EVIDENCE` | `EVIDENCE_GATHERED` | `CORRELATING_SIGNALS` |
| `CORRELATING_SIGNALS` | `SIGNALS_CORRELATED` | `DIAGNOSING` |
| `DIAGNOSING` | `ACTION_PROPOSED` | `AWAITING_APPROVAL` |
| `DIAGNOSING` | `NO_ACTION_REQUIRED` | `VERIFYING` |
| `AWAITING_APPROVAL` | `ACTION_APPROVED` | `EXECUTING` |
| `AWAITING_APPROVAL` | `ACTION_REJECTED` | `CANCELLED` |
| `EXECUTING` | `EXECUTION_SUCCEEDED` | `VERIFYING` |
| `EXECUTING` | `EXECUTION_FAILED` + `FAILED` disposition | `FAILED` |
| `EXECUTING` | `EXECUTION_FAILED` + `ROLLING_BACK` disposition | `ROLLING_BACK` |
| `VERIFYING` | `VERIFICATION_PASSED` | `CLOSED` |
| `VERIFYING` | `VERIFICATION_FAILED` + `FAILED` disposition | `FAILED` |
| `VERIFYING` | `VERIFICATION_FAILED` + `ROLLING_BACK` disposition | `ROLLING_BACK` |
| `ROLLING_BACK` | `ROLLBACK_SUCCEEDED` | `ROLLED_BACK` |
| `ROLLING_BACK` | `ROLLBACK_FAILED` | `FAILED` |
| `FAILED` | `PHASE_RETRIED` | `GATHERING_EVIDENCE` |

`CLOSED`, `ROLLED_BACK`, and `CANCELLED` are terminal states and reject further transitions.

## Concurrency and audit behavior

The transition request contains the incident's `expectedVersion`. A mismatch returns `409 Conflict`, preventing an outdated caller from overwriting a newer outcome. Hibernate's `@Version` adds database-level optimistic-lock protection for competing updates.

Accepted transitions persist the event type, prior and next statuses, actor, optional reason, trace ID, timestamp, metadata, and the next event sequence. Rejected requests do not append timeline events.

## Current API boundary

`POST /api/v1/incidents/{incidentId}/transitions` is a temporary controlled entry point for development and workflow wiring. It requires a legal `event`, `actor`, and `expectedVersion`; failure events also require a `failureDisposition`. The security/approval phase will derive the actor from authentication and limit which principals can submit each event.
