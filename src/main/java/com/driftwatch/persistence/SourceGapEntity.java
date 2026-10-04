package com.driftwatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A recorded visibility gap; an unmeasurable missing count is stored as {@code unknown}. */
@Entity
@Table(name = "source_gaps")
public class SourceGapEntity {

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_RECOVERED = "RECOVERED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 128)
    private String source;

    @Column(name = "poll_run_id")
    private Long pollRunId;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "visible_from")
    private Instant visibleFrom;

    @Column(name = "visible_to")
    private Instant visibleTo;

    @Column(name = "confirmed_from")
    private Instant confirmedFrom;

    @Column(name = "missing_count", nullable = false, length = 32)
    private String missingCount = "unknown";

    @Column(name = "recovery_state", nullable = false, length = 16)
    private String recoveryState = STATE_OPEN;

    @Column(name = "detail")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "recovered_at")
    private Instant recoveredAt;

    public Long getId() { return id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getPollRunId() { return pollRunId; }
    public void setPollRunId(Long pollRunId) { this.pollRunId = pollRunId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getVisibleFrom() { return visibleFrom; }
    public void setVisibleFrom(Instant visibleFrom) { this.visibleFrom = visibleFrom; }
    public Instant getVisibleTo() { return visibleTo; }
    public void setVisibleTo(Instant visibleTo) { this.visibleTo = visibleTo; }
    public Instant getConfirmedFrom() { return confirmedFrom; }
    public void setConfirmedFrom(Instant confirmedFrom) { this.confirmedFrom = confirmedFrom; }
    public String getMissingCount() { return missingCount; }
    public void setMissingCount(String missingCount) { this.missingCount = missingCount; }
    public String getRecoveryState() { return recoveryState; }
    public void setRecoveryState(String recoveryState) { this.recoveryState = recoveryState; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getRecoveredAt() { return recoveredAt; }
    public void setRecoveredAt(Instant recoveredAt) { this.recoveredAt = recoveredAt; }
}
