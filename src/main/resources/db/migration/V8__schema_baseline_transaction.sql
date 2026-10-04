-- Schema transaction guarantees and the baseline outbox (execution guide, section 4.4).
--
-- At most one ACTIVE baseline per event type is enforced by a partial unique index. When an
-- upgraded database already contains several ACTIVE rows, the earliest row per event type is
-- kept and the others are demoted to DRIFTING with a migration record, so no version is lost.

CREATE TABLE IF NOT EXISTS schema_baseline_migrations (
    id                    BIGSERIAL PRIMARY KEY,
    event_type            VARCHAR(64) NOT NULL,
    kept_version_id       BIGINT      NOT NULL REFERENCES schema_versions(id),
    superseded_version_id BIGINT      NOT NULL REFERENCES schema_versions(id),
    note                  TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reconcile pre-existing duplicate ACTIVE rows before the unique index is created.
INSERT INTO schema_baseline_migrations (event_type, kept_version_id, superseded_version_id, note)
SELECT s.event_type, keep.kept_id, s.id, 'V8 reconciliation: duplicate ACTIVE rows'
FROM schema_versions s
JOIN (
    SELECT event_type, MIN(id) AS kept_id
    FROM schema_versions
    WHERE status = 'ACTIVE'
    GROUP BY event_type
) keep ON keep.event_type = s.event_type
WHERE s.status = 'ACTIVE' AND s.id <> keep.kept_id;

UPDATE schema_versions s
SET status = 'DRIFTING'
FROM (
    SELECT event_type, MIN(id) AS kept_id
    FROM schema_versions
    WHERE status = 'ACTIVE'
    GROUP BY event_type
) keep
WHERE s.event_type = keep.event_type
  AND s.status = 'ACTIVE'
  AND s.id <> keep.kept_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_schema_versions_active_per_event_type
    ON schema_versions (event_type)
    WHERE status = 'ACTIVE';

-- Outbox for baseline changes; the relay publishes them to the compacted schema-baselines-v1
-- topic. A row stays PENDING until the broker acknowledged the record, so a crash between the
-- commit and the publish is recovered by the next relay pass.
CREATE TABLE IF NOT EXISTS baseline_outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    version_id   BIGINT      NOT NULL REFERENCES schema_versions(id),
    payload_json JSONB       NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts     INT         NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_baseline_outbox_status ON baseline_outbox (status, id);