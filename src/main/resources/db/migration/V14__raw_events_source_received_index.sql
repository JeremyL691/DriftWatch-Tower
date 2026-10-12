-- Source freshness and health counts are computed from when this application received an event
-- (guide 7.2: ordinary sources use last_received_at). The newest-row lookup and the rolling
-- counts per source filter and sort on received_at, which the event_timestamp index from V12
-- cannot serve.
CREATE INDEX IF NOT EXISTS idx_raw_events_source_received
    ON raw_events (source, received_at DESC, id DESC);
