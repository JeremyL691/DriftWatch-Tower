package com.driftwatch.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Idempotency-Key receipt for the ingest API. Written before the publish so a restart cannot
 * allocate a second ingestion identity for the same key, and updated with the publish outcome
 * afterwards. No credential material is stored.
 */
@Entity
@Table(name = "ingestion_receipts")
@IdClass(IngestionReceiptEntity.Key.class)
public class IngestionReceiptEntity {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_CONFIRMED = "CONFIRMED";
    public static final String STATE_FAILED = "FAILED";

    @Id
    @Column(name = "source", length = 128)
    private String source;

    @Id
    @Column(name = "event_type", length = 128)
    private String eventType;

    @Id
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;

    @Column(name = "ingestion_id", nullable = false, length = 64)
    private String ingestionId;

    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "publish_state", nullable = false, length = 16)
    private String publishState;

    @Column(name = "batch", nullable = false)
    private boolean batch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "batch_items", columnDefinition = "jsonb")
    private JsonNode batchItems;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestDigest() { return requestDigest; }
    public void setRequestDigest(String requestDigest) { this.requestDigest = requestDigest; }
    public String getIngestionId() { return ingestionId; }
    public void setIngestionId(String ingestionId) { this.ingestionId = ingestionId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getPublishState() { return publishState; }
    public void setPublishState(String publishState) { this.publishState = publishState; }
    public boolean isBatch() { return batch; }
    public void setBatch(boolean batch) { this.batch = batch; }
    public JsonNode getBatchItems() { return batchItems; }
    public void setBatchItems(JsonNode batchItems) { this.batchItems = batchItems; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /** Composite key: one receipt per source/event_type/idempotency key. */
    public static class Key implements Serializable {
        private String source;
        private String eventType;
        private String idempotencyKey;

        public Key() {}

        public Key(String source, String eventType, String idempotencyKey) {
            this.source = source;
            this.eventType = eventType;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(source, key.source)
                    && Objects.equals(eventType, key.eventType)
                    && Objects.equals(idempotencyKey, key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, eventType, idempotencyKey);
        }
    }
}
