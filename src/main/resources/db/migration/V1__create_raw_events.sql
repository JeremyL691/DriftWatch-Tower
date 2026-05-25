CREATE TABLE raw_events (
    id               BIGSERIAL PRIMARY KEY,
    event_id         VARCHAR(128) NOT NULL,
    source           VARCHAR(64)  NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    event_timestamp  TIMESTAMPTZ  NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    payload_json     JSONB        NOT NULL,
    payload_hash     VARCHAR(64)  NOT NULL,
    quality_status   VARCHAR(32)  NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_raw_events_event_id    ON raw_events (event_id);
CREATE INDEX idx_raw_events_received_at ON raw_events (received_at DESC);
CREATE INDEX idx_raw_events_source_type ON raw_events (source, event_type);
CREATE INDEX idx_raw_events_payload_hash ON raw_events (payload_hash);
