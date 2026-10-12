package com.driftwatch.dlt;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.persistence.DeadLetterRecordEntity;
import com.driftwatch.persistence.DeadLetterRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects Kafka dead letters into {@code dead_letter_records} (guide 7.1).
 *
 * <p>Kafka remains the durable recovery source, so this consumer can lag or restart without
 * losing a failure. Writes are idempotent by diagnostic id: a redelivered dead letter is skipped
 * instead of duplicating the row.
 */
@Component
public class DeadLetterProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterProjectionConsumer.class);

    private final DeadLetterRecordRepository repository;
    private final ObjectMapper objectMapper;

    public DeadLetterProjectionConsumer(DeadLetterRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = KafkaTopics.DEAD_LETTER_EVENTS,
            groupId = "driftwatch-dlt-projection",
            properties = {
                    "spring.json.value.default.type=com.driftwatch.dlt.DltMessage",
                    "spring.json.trusted.packages=com.driftwatch.dlt"
            })
    @Transactional
    public void onDeadLetter(DltMessage message) {
        if (repository.findByDiagnosticId(clip(message.diagnosticId(), 128)).isPresent()) {
            return;
        }
        DeadLetterRecordEntity row = new DeadLetterRecordEntity();
        // Column widths bound every identifying field: a dead letter exists precisely because its
        // record may be malformed (an over-long source is a sink failure), so the projection must
        // never fail on it - an endless retry here would block every later dead letter.
        row.setDiagnosticId(clip(message.diagnosticId(), 128));
        row.setStage(message.stage() == null ? DltStage.STREAM.name() : message.stage().name());
        row.setIngestionId(clip(message.ingestionId(), 64));
        row.setSource(clip(message.source(), 128));
        row.setEventType(clip(message.eventType(), 128));
        row.setReason(DltMessage.truncate(message.reason(), DltMessage.MAX_REASON_LENGTH));
        row.setPayload(payloadNode(message.payload()));
        row.setAttempts(message.attempts());
        row.setRecoveryState(DeadLetterRecordEntity.STATE_OPEN);
        row.setKafkaTopic(clip(message.kafkaTopic(), 128));
        row.setKafkaPartition(message.kafkaPartition());
        row.setKafkaOffset(message.kafkaOffset());
        row.setCreatedAt(message.occurredAt() == null ? java.time.Instant.now() : message.occurredAt());
        repository.save(row);
        log.warn("dead letter projected: diagnostic={} stage={} reason={}",
                row.getDiagnosticId(), row.getStage(), row.getReason());
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** The payload is stored as text inside JSON so a truncated record is still queryable. */
    private ObjectNode payloadNode(String payload) {
        ObjectNode node = objectMapper.createObjectNode();
        if (payload != null) {
            node.put("raw", DltMessage.truncatePayload(payload));
        }
        return node;
    }
}
