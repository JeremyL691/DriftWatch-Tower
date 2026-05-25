CREATE TABLE metric_windows (
    id            BIGSERIAL PRIMARY KEY,
    source        VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    window_start  TIMESTAMPTZ  NOT NULL,
    window_end    TIMESTAMPTZ  NOT NULL,
    metric_name   VARCHAR(256) NOT NULL,
    metric_value  DOUBLE PRECISION NOT NULL DEFAULT 0,
    CONSTRAINT uq_metric_windows_scope
        UNIQUE (source, event_type, window_start, window_end, metric_name)
);

CREATE INDEX idx_metric_windows_scope
    ON metric_windows (source, event_type, window_start DESC);

CREATE INDEX idx_metric_windows_metric
    ON metric_windows (metric_name, window_start DESC);
