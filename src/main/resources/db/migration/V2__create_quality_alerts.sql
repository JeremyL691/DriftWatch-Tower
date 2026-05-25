CREATE TABLE quality_alerts (
    id              BIGSERIAL PRIMARY KEY,
    alert_type      VARCHAR(32)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL,
    source          VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    field_path      VARCHAR(256),
    message         TEXT         NOT NULL,
    evidence_json   JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX idx_quality_alerts_created_at ON quality_alerts (created_at DESC);
CREATE INDEX idx_quality_alerts_alert_type ON quality_alerts (alert_type);
CREATE INDEX idx_quality_alerts_source     ON quality_alerts (source);
CREATE INDEX idx_quality_alerts_unresolved ON quality_alerts (resolved_at) WHERE resolved_at IS NULL;
