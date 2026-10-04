-- Ingestion identity and idempotent delivery (execution guide, sections 4.1-4.3).
--
-- raw_events gains the internal identity; legacy rows receive a stable legacy-db:<pk> identity
-- so the migration is re-runnable and old primary keys, alerts and payloads are untouched.
-- The business event_id keeps a plain index (never unique): two inputs may share it.

ALTER TABLE raw_events ADD COLUMN IF NOT EXISTS ingestion_id VARCHAR(64);
ALTER TABLE raw_events ADD COLUMN IF NOT EXISTS origin        VARCHAR(16);
ALTER TABLE raw_events ADD COLUMN IF NOT EXISTS mode          VARCHAR(16);
ALTER TABLE raw_events ADD COLUMN IF NOT EXISTS window_evaluation JSONB;
ALTER TABLE raw_events ADD COLUMN IF NOT EXISTS baseline_status VARCHAR(16);

UPDATE raw_events
SET ingestion_id = 'legacy-db:' || id
WHERE ingestion_id IS NULL;

ALTER TABLE raw_events ALTER COLUMN ingestion_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_raw_events_ingestion_id ON raw_events (ingestion_id);
CREATE INDEX IF NOT EXISTS idx_raw_events_window_outcome
    ON raw_events ((window_evaluation ->> 'outcome'));

-- One row per processed ingestion: the sink's idempotency key.
CREATE TABLE IF NOT EXISTS processed_receipts (
    ingestion_id  VARCHAR(64) PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL,
    rule_version  VARCHAR(64) NOT NULL,
    content_digest VARCHAR(64) NOT NULL
);

-- Idempotency-Key receipts for the ingest API. The receipt is written before the publish so a
-- restart cannot hand out a second identity for the same key.
CREATE TABLE IF NOT EXISTS ingestion_receipts (
    source            VARCHAR(128) NOT NULL,
    event_type        VARCHAR(128) NOT NULL,
    idempotency_key   VARCHAR(128) NOT NULL,
    request_digest    VARCHAR(64)  NOT NULL,
    ingestion_id      VARCHAR(64)  NOT NULL,
    event_id          VARCHAR(128),
    publish_state     VARCHAR(16)  NOT NULL,
    batch             BOOLEAN      NOT NULL DEFAULT false,
    batch_items       JSONB,
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (source, event_type, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_ingestion_receipts_expiry ON ingestion_receipts (expires_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_receipts_ingestion ON ingestion_receipts (ingestion_id);

-- Alerts carry the ingestion identity and the detector/window key; the unique constraint keeps
-- a replayed ingestion from duplicating the same window alert. Legacy rows keep NULL identity
-- and are exempt from the constraint.
ALTER TABLE quality_alerts ADD COLUMN IF NOT EXISTS ingestion_id VARCHAR(64);
ALTER TABLE quality_alerts ADD COLUMN IF NOT EXISTS detector_key VARCHAR(128);
ALTER TABLE quality_alerts ADD COLUMN IF NOT EXISTS window_key   VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quality_alerts_ingestion_detector_window
    ON quality_alerts (ingestion_id, detector_key, window_key)
    WHERE ingestion_id IS NOT NULL AND detector_key IS NOT NULL AND window_key IS NOT NULL;

-- Dead-letter records for source/stream/sink failures (filled in P3.2).
CREATE TABLE IF NOT EXISTS dead_letter_records (
    id             BIGSERIAL PRIMARY KEY,
    diagnostic_id  VARCHAR(128) NOT NULL,
    stage          VARCHAR(16)  NOT NULL,
    ingestion_id   VARCHAR(64),
    source         VARCHAR(128),
    event_type     VARCHAR(128),
    reason         TEXT,
    payload        JSONB,
    attempts       INT          NOT NULL DEFAULT 0,
    recovery_state VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    kafka_topic    VARCHAR(128),
    kafka_partition INT,
    kafka_offset   BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    recovered_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_dead_letter_diagnostic ON dead_letter_records (diagnostic_id);
CREATE INDEX IF NOT EXISTS idx_dead_letter_state ON dead_letter_records (recovery_state, id);