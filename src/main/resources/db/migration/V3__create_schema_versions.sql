CREATE TABLE schema_versions (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(64)  NOT NULL,
    schema_hash     VARCHAR(64)  NOT NULL,
    schema_json     JSONB        NOT NULL,
    first_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status          VARCHAR(16)  NOT NULL,
    CONSTRAINT uq_schema_versions_event_type_hash UNIQUE (event_type, schema_hash)
);

CREATE INDEX idx_schema_versions_event_type     ON schema_versions (event_type);
CREATE INDEX idx_schema_versions_event_type_status ON schema_versions (event_type, status);
