package com.driftwatch.dlt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Dead-letter record carried on {@code dead-letter-events-v1} (execution guide, section 7.1).
 *
 * <p>The identity is the original {@code ingestionId} when it could be parsed; otherwise a stable
 * diagnostic id derived from topic/partition/offset. {@code reason} and {@code payload} are
 * truncated and never contain credentials or configuration dumps.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DltMessage(
        @JsonProperty("diagnostic_id") String diagnosticId,
        @JsonProperty("stage") DltStage stage,
        @JsonProperty("ingestion_id") String ingestionId,
        @JsonProperty("source") String source,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("reason") String reason,
        @JsonProperty("attempts") int attempts,
        @JsonProperty("kafka_topic") String kafkaTopic,
        @JsonProperty("kafka_partition") Integer kafkaPartition,
        @JsonProperty("kafka_offset") Long kafkaOffset,
        /** Original record, truncated: ProcessedEvent JSON for SINK, envelope JSON for STREAM. */
        @JsonProperty("payload") String payload,
        @JsonProperty("occurred_at") Instant occurredAt
) {

    public static final int MAX_REASON_LENGTH = 500;
    public static final int MAX_PAYLOAD_LENGTH = 8 * 1024;

    /** Stable diagnostic identity when the ingestion id is unknown. */
    public static String diagnosticIdFor(String ingestionId, String topic, Integer partition, Long offset) {
        if (ingestionId != null && !ingestionId.isBlank()) {
            return "ingestion:" + ingestionId;
        }
        return "kafka:" + topic + ":" + partition + ":" + offset;
    }

    public static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "...[truncated]";
    }
}
