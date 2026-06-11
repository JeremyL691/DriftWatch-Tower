ALTER TABLE quality_alerts ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'OPEN';
ALTER TABLE quality_alerts ADD COLUMN acknowledged_by VARCHAR(100);
ALTER TABLE quality_alerts ADD COLUMN acknowledged_at TIMESTAMP;
ALTER TABLE quality_alerts ADD COLUMN root_cause TEXT;

CREATE INDEX idx_quality_alerts_status ON quality_alerts(status);
