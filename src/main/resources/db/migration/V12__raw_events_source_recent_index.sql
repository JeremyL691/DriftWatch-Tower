-- Source-health refresh runs once per persisted event and asks for the newest raw row of a
-- source. Without a matching index that is a sequential scan plus a sort of every row the source
-- ever produced, which grows with the table and shows up as the pipeline's throughput ceiling
-- under load. The index makes it a single index scan.
CREATE INDEX IF NOT EXISTS idx_raw_events_source_recent
    ON raw_events (source, event_timestamp DESC, id DESC);
