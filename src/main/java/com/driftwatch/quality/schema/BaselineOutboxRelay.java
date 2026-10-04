package com.driftwatch.quality.schema;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.persistence.BaselineOutboxEntity;
import com.driftwatch.persistence.BaselineOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Relay for the baseline outbox (execution guide, section 4.4).
 *
 * <p>Baseline changes are committed with the schema change and published afterwards. A row is
 * only marked SENT after the broker acknowledged the record, so a crash between the commit and
 * the publish is retried by the next pass with the same payload; the relay never claims the
 * Streams side applied a baseline that was not delivered.
 */
@Component
public class BaselineOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(BaselineOutboxRelay.class);
    private static final int MAX_ATTEMPTS = 10;

    private final BaselineOutboxRepository outboxRepository;
    private final KafkaTemplate<String, BaselineMessage> kafkaTemplate;

    public BaselineOutboxRelay(BaselineOutboxRepository outboxRepository,
                               KafkaTemplate<String, BaselineMessage> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 5_000L, initialDelay = 5_000L)
    public void publishPending() {
        for (BaselineOutboxEntity row : outboxRepository.findTop50ByStatusOrderByIdAsc(
                BaselineOutboxEntity.STATUS_PENDING)) {
            publish(row);
        }
    }

    /** Publishes one row and persists the resulting status, returning true when it was sent. */
    public boolean publish(BaselineOutboxEntity row) {
        try {
            BaselineMessage message = toMessage(row);
            kafkaTemplate.send(KafkaTopics.SCHEMA_BASELINES, row.getEventType(), message)
                    .get(10, TimeUnit.SECONDS);
            row.setStatus(BaselineOutboxEntity.STATUS_SENT);
            row.setSentAt(Instant.now());
            row.setLastError(null);
            outboxRepository.save(row);
            return true;
        } catch (Exception e) {
            int attempts = row.getAttempts() + 1;
            row.setAttempts(attempts);
            row.setLastError(truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
            if (attempts >= MAX_ATTEMPTS) {
                row.setStatus(BaselineOutboxEntity.STATUS_FAILED);
                log.error("baseline outbox row {} failed permanently after {} attempts",
                        row.getId(), attempts);
            } else {
                log.warn("baseline outbox row {} publish attempt {} failed: {}",
                        row.getId(), attempts, row.getLastError());
            }
            outboxRepository.save(row);
            return false;
        }
    }

    /** Best-effort immediate publish used by the activation API to report a truthful sync state. */
    public boolean publishNow(Long outboxId) {
        return outboxRepository.findById(outboxId).map(this::publish).orElse(false);
    }

    private BaselineMessage toMessage(BaselineOutboxEntity row) {
        var payload = row.getPayloadJson();
        var leafTypes = new java.util.TreeMap<String, String>();
        if (payload != null && payload.has("leaf_types")) {
            payload.get("leaf_types").fields().forEachRemaining(entry ->
                    leafTypes.put(entry.getKey(), entry.getValue().asText()));
        }
        return new BaselineMessage(row.getEventType(), row.getVersionId(), leafTypes);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /** Backoff hint for operators reading logs. */
    public static Duration retryDelay() {
        return Duration.ofSeconds(5);
    }
}