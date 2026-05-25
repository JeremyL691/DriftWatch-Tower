package com.driftwatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "metric_windows",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_metric_windows_scope",
                columnNames = {"source", "event_type", "window_start", "window_end", "metric_name"}))
public class MetricWindowEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String source;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "metric_value", nullable = false)
    private double metricValue;

    public Long getId() { return id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public double getMetricValue() { return metricValue; }
    public void setMetricValue(double metricValue) { this.metricValue = metricValue; }
}
