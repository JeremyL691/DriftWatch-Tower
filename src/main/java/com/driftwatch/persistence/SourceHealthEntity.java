package com.driftwatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "source_health")
public class SourceHealthEntity {

    public static final String STATUS_HEALTHY = "HEALTHY";
    public static final String STATUS_STALE = "STALE";
    public static final String STATUS_UNHEALTHY = "UNHEALTHY";

    @Id
    @Column(nullable = false, length = 128)
    private String source;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "events_last_5m", nullable = false)
    private long eventsLast5m;

    @Column(name = "events_last_1h", nullable = false)
    private long eventsLast1h;

    @Column(name = "duplicate_rate", nullable = false)
    private double duplicateRate;

    @Column(name = "late_event_rate", nullable = false)
    private double lateEventRate;

    @Column(name = "null_rate", nullable = false)
    private double nullRate;

    @Column(name = "health_score", nullable = false)
    private double healthScore;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public long getEventsLast5m() { return eventsLast5m; }
    public void setEventsLast5m(long eventsLast5m) { this.eventsLast5m = eventsLast5m; }
    public long getEventsLast1h() { return eventsLast1h; }
    public void setEventsLast1h(long eventsLast1h) { this.eventsLast1h = eventsLast1h; }
    public double getDuplicateRate() { return duplicateRate; }
    public void setDuplicateRate(double duplicateRate) { this.duplicateRate = duplicateRate; }
    public double getLateEventRate() { return lateEventRate; }
    public void setLateEventRate(double lateEventRate) { this.lateEventRate = lateEventRate; }
    public double getNullRate() { return nullRate; }
    public void setNullRate(double nullRate) { this.nullRate = nullRate; }
    public double getHealthScore() { return healthScore; }
    public void setHealthScore(double healthScore) { this.healthScore = healthScore; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
