CREATE TABLE source_health (
    source           VARCHAR(128) PRIMARY KEY,
    status           VARCHAR(16)     NOT NULL,
    last_seen_at     TIMESTAMPTZ     NOT NULL,
    events_last_5m   BIGINT          NOT NULL DEFAULT 0,
    events_last_1h   BIGINT          NOT NULL DEFAULT 0,
    duplicate_rate   DOUBLE PRECISION NOT NULL DEFAULT 0,
    late_event_rate  DOUBLE PRECISION NOT NULL DEFAULT 0,
    null_rate        DOUBLE PRECISION NOT NULL DEFAULT 0,
    health_score     DOUBLE PRECISION NOT NULL DEFAULT 100,
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_source_health_status ON source_health (status, health_score);
CREATE INDEX idx_source_health_updated_at ON source_health (updated_at DESC);
