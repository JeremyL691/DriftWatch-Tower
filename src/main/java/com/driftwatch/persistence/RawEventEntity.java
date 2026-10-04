package com.driftwatch.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "raw_events")
public class RawEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "ingestion_id", nullable = false, length = 64)
    private String ingestionId;

    @Column(name = "origin", length = 16)
    private String origin;

    @Column(name = "mode", length = 16)
    private String mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "window_evaluation", columnDefinition = "jsonb")
    private JsonNode windowEvaluation;

    @Column(name = "baseline_status", length = 16)
    private String baselineStatus;

    @Column(nullable = false)
    private String source;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode payloadJson;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "quality_status", nullable = false)
    private String qualityStatus;

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getIngestionId() { return ingestionId; }
    public void setIngestionId(String ingestionId) { this.ingestionId = ingestionId; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public JsonNode getWindowEvaluation() { return windowEvaluation; }
    public void setWindowEvaluation(JsonNode windowEvaluation) { this.windowEvaluation = windowEvaluation; }
    public String getBaselineStatus() { return baselineStatus; }
    public void setBaselineStatus(String baselineStatus) { this.baselineStatus = baselineStatus; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(Instant eventTimestamp) { this.eventTimestamp = eventTimestamp; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public JsonNode getPayloadJson() { return payloadJson; }
    public void setPayloadJson(JsonNode payloadJson) { this.payloadJson = payloadJson; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getQualityStatus() { return qualityStatus; }
    public void setQualityStatus(String qualityStatus) { this.qualityStatus = qualityStatus; }
}
