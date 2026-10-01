package com.driftwatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One poll attempt with its traversed range, candidate ETag and counts. */
@Entity
@Table(name = "source_poll_runs")
public class SourcePollRunEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_STAGED = "STAGED";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_QUIET = "QUIET";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 128)
    private String source;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "etag_candidate", length = 256)
    private String etagCandidate;

    @Column(name = "pages_read", nullable = false)
    private int pagesRead;

    @Column(name = "records_seen", nullable = false)
    private int recordsSeen;

    @Column(name = "new_records", nullable = false)
    private int newRecords;

    @Column(name = "oldest_created_at")
    private Instant oldestCreatedAt;

    @Column(name = "newest_created_at")
    private Instant newestCreatedAt;

    @Column(name = "x_poll_interval")
    private Integer xPollInterval;

    @Column(name = "failure_reason")
    private String failureReason;

    public Long getId() { return id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getEtagCandidate() { return etagCandidate; }
    public void setEtagCandidate(String etagCandidate) { this.etagCandidate = etagCandidate; }
    public int getPagesRead() { return pagesRead; }
    public void setPagesRead(int pagesRead) { this.pagesRead = pagesRead; }
    public int getRecordsSeen() { return recordsSeen; }
    public void setRecordsSeen(int recordsSeen) { this.recordsSeen = recordsSeen; }
    public int getNewRecords() { return newRecords; }
    public void setNewRecords(int newRecords) { this.newRecords = newRecords; }
    public Instant getOldestCreatedAt() { return oldestCreatedAt; }
    public void setOldestCreatedAt(Instant oldestCreatedAt) { this.oldestCreatedAt = oldestCreatedAt; }
    public Instant getNewestCreatedAt() { return newestCreatedAt; }
    public void setNewestCreatedAt(Instant newestCreatedAt) { this.newestCreatedAt = newestCreatedAt; }
    public Integer getXPollInterval() { return xPollInterval; }
    public void setXPollInterval(Integer xPollInterval) { this.xPollInterval = xPollInterval; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
