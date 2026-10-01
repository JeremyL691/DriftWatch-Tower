package com.driftwatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per processed ingestion — the sink's idempotency record. The primary key is the
 * ingestion id, so a Kafka redelivery can be recognised and skipped without adding a second
 * set of side effects.
 */
@Entity
@Table(name = "processed_receipts")
public class ProcessedReceiptEntity {

    @Id
    @Column(name = "ingestion_id", length = 64)
    private String ingestionId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(name = "rule_version", nullable = false, length = 64)
    private String ruleVersion;

    @Column(name = "content_digest", nullable = false, length = 64)
    private String contentDigest;

    public String getIngestionId() { return ingestionId; }
    public void setIngestionId(String ingestionId) { this.ingestionId = ingestionId; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getContentDigest() { return contentDigest; }
    public void setContentDigest(String contentDigest) { this.contentDigest = contentDigest; }
}
