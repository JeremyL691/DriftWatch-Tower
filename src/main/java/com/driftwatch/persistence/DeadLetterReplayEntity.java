package com.driftwatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One operator replay attempt; the attempt id is returned to the caller. */
@Entity
@Table(name = "dead_letter_replays")
public class DeadLetterReplayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dead_letter_id", nullable = false)
    private Long deadLetterId;

    @Column(name = "replay_attempt_id", nullable = false, length = 64)
    private String replayAttemptId;

    @Column(name = "stage", nullable = false, length = 16)
    private String stage;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "detail")
    private String detail;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    public Long getId() { return id; }
    public Long getDeadLetterId() { return deadLetterId; }
    public void setDeadLetterId(Long deadLetterId) { this.deadLetterId = deadLetterId; }
    public String getReplayAttemptId() { return replayAttemptId; }
    public void setReplayAttemptId(String replayAttemptId) { this.replayAttemptId = replayAttemptId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
}
