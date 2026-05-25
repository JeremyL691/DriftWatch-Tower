package com.driftwatch.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "schema_versions",
        uniqueConstraints = @UniqueConstraint(name = "uq_schema_versions_event_type_hash",
                columnNames = {"event_type", "schema_hash"}))
public class SchemaVersionEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DRIFTING = "DRIFTING";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "schema_hash", nullable = false, length = 64)
    private String schemaHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode schemaJson;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false, length = 16)
    private String status;

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getSchemaHash() { return schemaHash; }
    public void setSchemaHash(String schemaHash) { this.schemaHash = schemaHash; }
    public JsonNode getSchemaJson() { return schemaJson; }
    public void setSchemaJson(JsonNode schemaJson) { this.schemaJson = schemaJson; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
