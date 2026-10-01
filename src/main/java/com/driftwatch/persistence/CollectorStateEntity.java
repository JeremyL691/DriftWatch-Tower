package com.driftwatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Collector state machine, checkpoints and lease for one source (guide 6.3). */
@Entity
@Table(name = "collector_state")
public class CollectorStateEntity {

    public static final String STATUS_READY = "READY";
    public static final String STATUS_FETCHING = "FETCHING";
    public static final String STATUS_STAGED = "STAGED";
    public static final String STATUS_PUBLISHING = "PUBLISHING";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_BACKOFF = "BACKOFF";
    public static final String STATUS_ERROR = "ERROR";

    @Id
    @Column(name = "source", length = 128)
    private String source;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_READY;

    @Column(name = "etag_applied", length = 256)
    private String etagApplied;

    @Column(name = "etag_candidate", length = 256)
    private String etagCandidate;

    @Column(name = "last_poll_at")
    private Instant lastPollAt;

    @Column(name = "last_poll_success")
    private Instant lastPollSuccess;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "next_poll_at")
    private Instant nextPollAt;

    @Column(name = "backoff_until")
    private Instant backoffUntil;

    @Column(name = "backoff_seconds")
    private Integer backoffSeconds;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "pending_poll_run_id")
    private Long pendingPollRunId;

    @Column(name = "observed_from")
    private Instant observedFrom;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEtagApplied() { return etagApplied; }
    public void setEtagApplied(String etagApplied) { this.etagApplied = etagApplied; }
    public String getEtagCandidate() { return etagCandidate; }
    public void setEtagCandidate(String etagCandidate) { this.etagCandidate = etagCandidate; }
    public Instant getLastPollAt() { return lastPollAt; }
    public void setLastPollAt(Instant lastPollAt) { this.lastPollAt = lastPollAt; }
    public Instant getLastPollSuccess() { return lastPollSuccess; }
    public void setLastPollSuccess(Instant lastPollSuccess) { this.lastPollSuccess = lastPollSuccess; }
    public Instant getLastEventAt() { return lastEventAt; }
    public void setLastEventAt(Instant lastEventAt) { this.lastEventAt = lastEventAt; }
    public Instant getNextPollAt() { return nextPollAt; }
    public void setNextPollAt(Instant nextPollAt) { this.nextPollAt = nextPollAt; }
    public Instant getBackoffUntil() { return backoffUntil; }
    public void setBackoffUntil(Instant backoffUntil) { this.backoffUntil = backoffUntil; }
    public Integer getBackoffSeconds() { return backoffSeconds; }
    public void setBackoffSeconds(Integer backoffSeconds) { this.backoffSeconds = backoffSeconds; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Long getPendingPollRunId() { return pendingPollRunId; }
    public void setPendingPollRunId(Long pendingPollRunId) { this.pendingPollRunId = pendingPollRunId; }
    public Instant getObservedFrom() { return observedFrom; }
    public void setObservedFrom(Instant observedFrom) { this.observedFrom = observedFrom; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
