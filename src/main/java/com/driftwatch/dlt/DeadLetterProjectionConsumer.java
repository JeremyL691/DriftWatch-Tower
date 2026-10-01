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
        if (repository.findByDiagnosticId(message.diagnosticId()).isPresent()) {
            return;
        }
        DeadLetterRecordEntity row = new DeadLetterRecordEntity();
        row.setDiagnosticId(message.diagnosticId());
        row.setStage(message.stage() == null ? DltStage.STREAM.name() : message.stage().name());
        row.setIngestionId(message.ingestionId());
        row.setSource(message.source());
        row.setEventType(message.eventType());
        row.setReason(DltMessage.truncate(message.reason(), DltMessage.MAX_REASON_LENGTH));
        row.setPayload(payloadNode(message.payload()));
        row.setAttempts(message.attempts());
        row.setRecoveryState(DeadLetterRecordEntity.STATE_OPEN);
        row.setKafkaTopic(message.kafkaTopic());
        row.setKafkaPartition(message.kafkaPartition());
        row.setKafkaOffset(message.kafkaOffset());
        row.setCreatedAt(message.occurredAt() == null ? java.time.Instant.now() : message.occurredAt());
        repository.save(row);
        log.warn("dead letter projected: diagnostic={} stage={} reason={}",
                row.getDiagnosticId(), row.getStage(), row.getReason());
    }

    /** The payload is stored as text inside JSON so a truncated record is still queryable. */
    private ObjectNode payloadNode(String payload) {
        ObjectNode node = objectMapper.createObjectNode();
        if (payload != null) {
            node.put("raw", DltMessage.truncate(payload, DltMessage.MAX_PAYLOAD_LENGTH));
        }
        return node;
    }
}
