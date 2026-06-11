CREATE TABLE alert_incidents (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    source VARCHAR(100),
    event_type VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP
);

ALTER TABLE quality_alerts ADD COLUMN incident_id BIGINT REFERENCES alert_incidents(id);
CREATE INDEX idx_quality_alerts_incident ON quality_alerts(incident_id);
CREATE INDEX idx_alert_incidents_status ON alert_incidents(status);
