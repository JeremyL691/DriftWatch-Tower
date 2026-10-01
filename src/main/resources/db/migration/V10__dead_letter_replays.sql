-- Recovery history for dead-letter replay operations (execution guide, sections 4.5 and 7.1).
-- Each replay keeps the original ingestion identity and returns a new replay_attempt_id, so a
-- repeated replay can be recognised instead of counting the same recovery twice.

CREATE TABLE IF NOT EXISTS dead_letter_replays (
    id                BIGSERIAL PRIMARY KEY,
    dead_letter_id    BIGINT      NOT NULL REFERENCES dead_letter_records(id),
    replay_attempt_id VARCHAR(64) NOT NULL,
    stage             VARCHAR(16) NOT NULL,
    outcome           VARCHAR(16) NOT NULL,
    detail            TEXT,
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_dead_letter_replays_attempt
    ON dead_letter_replays (replay_attempt_id);
CREATE INDEX IF NOT EXISTS idx_dead_letter_replays_dlt ON dead_letter_replays (dead_letter_id, id);
