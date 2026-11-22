# Incident API — initial contract

Base path: `/api/v1/incidents`

This is the first public contract for Incident Autopilot. Incidents and their initial timeline event are persisted in PostgreSQL through Flyway-managed migrations, so they survive Spring service restarts. Workflow transitions, authentication, and pagination are later milestones; consumers should use the documented fields rather than rely on storage internals.

## Create or deduplicate an incident

`POST /api/v1/incidents`

Required JSON fields:

- `source` — alert origin, for example `manual-demo` or `alertmanager`.
- `fingerprint` — stable alert signature.
- `environmentId` — selected environment identifier.
- `severity` — one of `CRITICAL`, `HIGH`, `MEDIUM`, or `LOW`.
- `service` — affected service identifier.
- `startedAt` — ISO-8601 timestamp that is not in the future.

Optional fields: `externalAlertId`, `labels`, `annotations`, and `traceId`.

The deduplication key is `source + environmentId + fingerprint`. A new key returns `201 Created`; a repeated key returns `200 OK` with the original incident. New incidents always begin in `ALERTED` status.

Example request:

```json
{
  "source": "manual-demo",
  "fingerprint": "checkout-api-high-error-rate",
  "environmentId": "local",
  "severity": "HIGH",
  "service": "checkout-api",
  "startedAt": "2025-01-01T00:00:00Z",
  "labels": { "team": "payments" },
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

## Query incidents

- `GET /api/v1/incidents` lists incidents, newest first.
- `GET /api/v1/incidents?environmentId=local&status=ALERTED` filters by environment and/or status.
- `GET /api/v1/incidents/{incidentId}` returns one incident.
- `GET /api/v1/incidents/{incidentId}/timeline` returns the immutable state-event timeline in sequence order.

Unknown incident IDs return `404` as RFC 9457 Problem Details. Invalid create payloads return `400` Problem Details with an `errors` object keyed by field name.

Every response includes `version`, the optimistic-lock version that later state-changing APIs will require. Creating an incident appends an `ALERT_RECEIVED` timeline event with sequence `1` in the same database transaction.

## Controlled lifecycle transition

`POST /api/v1/incidents/{incidentId}/transitions` advances an incident only through an allowed workflow event. Its required fields are `event`, `actor`, and `expectedVersion`; `metadata`, `reason`, and `traceId` are optional. `EXECUTION_FAILED` and `VERIFICATION_FAILED` additionally require `failureDisposition` of `FAILED` or `ROLLING_BACK`.

An accepted transition returns the updated incident and appends one timeline event. An illegal event or stale `expectedVersion` returns `409 Conflict` with Problem Details. See [ADR-002](../architecture/adr-002-incident-state-machine.md) for the complete transition table.

## Evidence collection and retrieval

- `POST /api/v1/incidents/{incidentId}/evidence/collect` collects evidence synchronously while the incident is `GATHERING_EVIDENCE`.
- `GET /api/v1/incidents/{incidentId}/evidence` returns persisted evidence, newest observation first.

Evidence items contain a type, source, target, time window, trace ID, bounded excerpt, content hash, redaction status, and metadata. Calling collection in another state returns `409 Conflict`. Adapter-backed Docker, Prometheus, Kubernetes, OpenStack, and trace collection is opt-in through explicit alert labels; a failed source is returned and retained as `COLLECTION_ERROR` evidence rather than discarding successful results. See the [evidence model](../architecture/evidence-model.md) for routing labels, bounds, and redaction behavior.

## Current lifecycle vocabulary

The API exposes the complete planned state vocabulary: `ALERTED`, `GATHERING_EVIDENCE`, `CORRELATING_SIGNALS`, `DIAGNOSING`, `AWAITING_APPROVAL`, `EXECUTING`, `VERIFYING`, `CLOSED`, `ROLLING_BACK`, `ROLLED_BACK`, `FAILED`, and `CANCELLED`.

The transition engine supports every listed state. Evidence collection, diagnosis, approval policy, execution, and verification handlers will emit their corresponding events in later milestones.
