-- Persistent state for the GitHub public-event collector (execution guide, section 6.3).
-- Everything needed to resume a poll after a restart lives here: the lease, the checkpoints,
-- the inbox/outbox pair and the recorded gaps.

-- One row per upstream event id; the inbox is the dedupe identity and is kept for 35 days
-- even when payloads are pruned.
CREATE TABLE IF NOT EXISTS source_inbox (
    id                BIGSERIAL PRIMARY KEY,
    source            VARCHAR(128) NOT NULL,
    github_event_id   VARCHAR(64)  NOT NULL,
    event_type        VARCHAR(128) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    ingestion_id      VARCHAR(64)  NOT NULL,
    mode              VARCHAR(16)  NOT NULL,
    origin_reference  TEXT,
    payload           JSONB        NOT NULL,
    content_hash      VARCHAR(64)  NOT NULL,
    poll_run_id       BIGINT,
    received_at       TIMESTAMPTZ  NOT NULL,
    pruned_at         TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_source_inbox_source_event
    ON source_inbox (source, github_event_id);
CREATE INDEX IF NOT EXISTS idx_source_inbox_received ON source_inbox (received_at);

-- Outbox rows are the durable publish intent: written with the inbox row, marked SENT only
-- after the broker acknowledged the record.
CREATE TABLE IF NOT EXISTS source_outbox (
    id             BIGSERIAL PRIMARY KEY,
    source         VARCHAR(128) NOT NULL,
    ingestion_id   VARCHAR(64)  NOT NULL,
    inbox_id       BIGINT       NOT NULL REFERENCES source_inbox(id),
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_source_outbox_ingestion ON source_outbox (ingestion_id);
CREATE INDEX IF NOT EXISTS idx_source_outbox_status ON source_outbox (status, id);

-- Collector state machine per source: READY/FETCHING/STAGED/PUBLISHING/APPLIED plus
-- BACKOFF/ERROR, the ETag checkpoints and the lease.
CREATE TABLE IF NOT EXISTS collector_state (
    source             VARCHAR(128) PRIMARY KEY,
    status             VARCHAR(16)  NOT NULL,
    etag_applied       VARCHAR(256),
    etag_candidate     VARCHAR(256),
    last_poll_at       TIMESTAMPTZ,
    last_poll_success  TIMESTAMPTZ,
    last_event_at      TIMESTAMPTZ,
    next_poll_at       TIMESTAMPTZ,
    backoff_until      TIMESTAMPTZ,
    backoff_seconds    INT,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_error         TEXT,
    lease_owner        VARCHAR(128),
    lease_expires_at   TIMESTAMPTZ,
    pending_poll_run_id BIGINT,
    observed_from      TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per poll attempt: the traversed range, the candidate ETag, counts and outcome.
CREATE TABLE IF NOT EXISTS source_poll_runs (
    id                BIGSERIAL PRIMARY KEY,
    source            VARCHAR(128) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    mode              VARCHAR(16)  NOT NULL,
    started_at        TIMESTAMPTZ  NOT NULL,
    finished_at       TIMESTAMPTZ,
    etag_candidate    VARCHAR(256),
    pages_read        INT          NOT NULL DEFAULT 0,
    records_seen      INT          NOT NULL DEFAULT 0,
    new_records       INT          NOT NULL DEFAULT 0,
    oldest_created_at TIMESTAMPTZ,
    newest_created_at TIMESTAMPTZ,
    x_poll_interval   INT,
    failure_reason    TEXT
);

CREATE INDEX IF NOT EXISTS idx_source_poll_runs_source ON source_poll_runs (source, id);

-- Recorded gaps: no overlap window, downtime beyond the visible range, pagination truncation
-- or budget exhaustion. A missing count that cannot be established is stored as 'unknown'.
CREATE TABLE IF NOT EXISTS source_gaps (
    id             BIGSERIAL PRIMARY KEY,
    source         VARCHAR(128) NOT NULL,
    poll_run_id    BIGINT,
    reason         VARCHAR(64)  NOT NULL,
    visible_from   TIMESTAMPTZ,
    visible_to     TIMESTAMPTZ,
    confirmed_from TIMESTAMPTZ,
    missing_count  VARCHAR(32)  NOT NULL DEFAULT 'unknown',
    recovery_state VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    detail         TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    recovered_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_source_gaps_state ON source_gaps (recovery_state, id);
