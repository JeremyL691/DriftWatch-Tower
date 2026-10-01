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

/**
 * Projection of a Kafka dead letter (execution guide, sections 4.3 and 7.1). Kafka stays the
 * durable recovery source; this row is the queryable projection and is written idempotently by
 * diagnostic id.
 */
@Entity
@Table(name = "dead_letter_records")
public class DeadLetterRecordEntity {

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_REPLAYED = "REPLAYED";
    public static final String STATE_DISCARDED = "DISCARDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "diagnostic_id", nullable = false, length = 128)
    private String diagnosticId;

    @Column(name = "stage", nullable = false, length = 16)
    private String stage;

    @Column(name = "ingestion_id", length = 64)
    private String ingestionId;

    @Column(name = "source", length = 128)
    private String source;

    @Column(name = "event_type", length = 128)
    private String eventType;

    @Column(name = "reason")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "recovery_state", nullable = false, length = 16)
    private String recoveryState = STATE_OPEN;

    @Column(name = "kafka_topic", length = 128)
    private String kafkaTopic;

    @Column(name = "kafka_partition")
    private Integer kafkaPartition;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "recovered_at")
    private Instant recoveredAt;

    public Long getId() { return id; }
    public String getDiagnosticId() { return diagnosticId; }
    public void setDiagnosticId(String diagnosticId) { this.diagnosticId = diagnosticId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getIngestionId() { return ingestionId; }
    public void setIngestionId(String ingestionId) { this.ingestionId = ingestionId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getRecoveryState() { return recoveryState; }
    public void setRecoveryState(String recoveryState) { this.recoveryState = recoveryState; }
    public String getKafkaTopic() { return kafkaTopic; }
    public void setKafkaTopic(String kafkaTopic) { this.kafkaTopic = kafkaTopic; }
    public Integer getKafkaPartition() { return kafkaPartition; }
    public void setKafkaPartition(Integer kafkaPartition) { this.kafkaPartition = kafkaPartition; }
    public Long getKafkaOffset() { return kafkaOffset; }
    public void setKafkaOffset(Long kafkaOffset) { this.kafkaOffset = kafkaOffset; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getRecoveredAt() { return recoveredAt; }
    public void setRecoveredAt(Instant recoveredAt) { this.recoveredAt = recoveredAt; }
}
