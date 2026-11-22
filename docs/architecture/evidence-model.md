# Evidence model and collection boundary

## What an evidence item contains

`evidence_items` stores one bounded observation associated with an incident:

- Evidence type and source.
- Service or infrastructure target.
- Observation and collection-window timestamps.
- Trace ID when known.
- A maximum 8 KiB excerpt and SHA-256 content hash.
- Redaction status and small structured metadata.

Evidence is separate from the incident row so many observations can be retained without turning an incident into an unbounded log archive. Raw logs and traces remain in their source backends; the item stores only a bounded, sanitized excerpt or reference.

## Collection contract

`EvidenceCollector` is the typed adapter SPI. A collector declares whether it supports an incident and returns zero or more `EvidenceCandidate` values. `EvidenceCollectionService` validates, bounds, redacts, hashes, and persists each candidate. If one collector throws an error, the service records a `COLLECTION_ERROR` item and preserves evidence produced by other collectors.

Evidence collection is permitted only while an incident is `GATHERING_EVIDENCE`. This protects the workflow from accidental collection after a terminal outcome.

The first built-in collector is `AlertContextEvidenceCollector`, which persists a safe summary of the received alert. Five adapter-backed collectors now use the same SPI:

| Alert label | Collector | Source endpoint | Evidence type |
| --- | --- | --- | --- |
| `runtime=docker` | Docker | `GET /docker/list?all=true` | `METRIC` |
| `collect-prometheus=true` | Prometheus | `GET /metrics/container/{service}` | `METRIC` |
| `namespace=<name>` | Kubernetes | `GET /k8s/pods?namespace=<name>` | `KUBERNETES_EVENT` |
| `platform=openstack` | OpenStack | `GET /api/v1/openstack/events` | `OPENSTACK_EVENT` |
| `collect-trace=true` plus trace ID | Tracing | `GET /apm/trace/{traceId}` | `TRACE` |

Explicit labels prevent a generic alert from scanning every adapter. Each adapter uses Spring's managed `WebClient.Builder`, has a three-second timeout, and limits a response to 32 KiB before the persistence layer applies its 8 KiB evidence-excerpt limit. A failed adapter produces `COLLECTION_ERROR` evidence while successful collectors continue.

External adapter calls run outside database transactions. Only the final sanitized evidence write uses a short transaction.

## Redaction and limits

The persistence service redacts common `api_key`, `authorization`, `password`, `secret`, and `token` key/value patterns before storage. Excerpts are limited to 8 KiB; metadata is limited to 50 entries, with 100-character keys and 512-character values. These are defence-in-depth limits, not permission to include credentials in telemetry.
