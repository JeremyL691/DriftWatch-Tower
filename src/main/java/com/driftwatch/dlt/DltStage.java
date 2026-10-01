package com.driftwatch.dlt;

/** Where a record failed; the stage decides which stage a replay re-enters. */
public enum DltStage {
    /** Could not be ingested from the source (GitHub poller / REST validation). */
    SOURCE,
    /** Could not be parsed or processed by the streams pipeline. */
    STREAM,
    /** Parsed fine but the persistence sink failed after its bounded retries. */
    SINK
}
