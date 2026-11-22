# PostgreSQL persistence

## Runtime configuration

The Spring control plane requires these environment variables in non-test environments:

- `POSTGRES_URL`, for example `jdbc:postgresql://postgres:5432/healops`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`

`docker-compose.yml` runs a pinned `postgres:17.4-alpine` container named `healops-postgres`, waits for its health check before starting the agent, and stores database files in the named `postgres_data` volume. Copy `.env.example` to `.env` and replace the password placeholder; `.env` remains ignored by Git.

## Schema ownership

Flyway is the sole schema owner. The application uses `spring.jpa.hibernate.ddl-auto=validate`, which verifies mappings but never creates or mutates tables. Do not add `schema.sql`, `data.sql`, or `ddl-auto=update`.

Migrations are vendor-specific:

- `Openstack-ai-agent/src/main/resources/db/migration/postgresql/` for the runtime database.
- `Openstack-ai-agent/src/main/resources/db/migration/h2/` for the isolated automated-test database.

The initial migration creates:

- `incidents`, constrained by a unique `(source, environment_id, fingerprint)` deduplication key and protected by JPA optimistic versioning.
- `incident_events`, an append-only, ordered timeline with a foreign key to its incident and a unique incident/sequence pair.

Labels, annotations, and event metadata are structured JSON columns. They are deliberately bounded API fields, not a place to store raw logs or traces.

## Current audit behavior

Spring Data auditing writes creation and update timestamps and a temporary `system` actor. The approval/security phase will replace that fallback with the authenticated operator identity. Each new incident receives an immutable `ALERT_RECEIVED` event in the same transaction as its incident row.
