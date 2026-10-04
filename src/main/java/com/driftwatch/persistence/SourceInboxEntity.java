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

/** One upstream event as first seen; the unique (source, github_event_id) pair is the dedupe identity. */
@Entity
@Table(name = "source_inbox")
public class SourceInboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 128)
    private String source;

    @Column(name = "github_event_id", nullable = false, length = 64)
    private String githubEventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "ingestion_id", nullable = false, length = 64)
    private String ingestionId;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "origin_reference")
    private String originReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "poll_run_id")
    private Long pollRunId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "pruned_at")
    private Instant prunedAt;

    public Long getId() { return id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getGithubEventId() { return githubEventId; }
    public void setGithubEventId(String githubEventId) { this.githubEventId = githubEventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getIngestionId() { return ingestionId; }
    public void setIngestionId(String ingestionId) { this.ingestionId = ingestionId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getOriginReference() { return originReference; }
    public void setOriginReference(String originReference) { this.originReference = originReference; }
    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Long getPollRunId() { return pollRunId; }
    public void setPollRunId(Long pollRunId) { this.pollRunId = pollRunId; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getPrunedAt() { return prunedAt; }
    public void setPrunedAt(Instant prunedAt) { this.prunedAt = prunedAt; }
}
