package com.driftwatch.dlt;

import com.driftwatch.config.KafkaTopics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes dead letters and waits for the broker acknowledgement. The sink only gives up on a
 * record after this returns successfully (guide 7.1): a failed DLT publish must leave the
 * original offset uncommitted so the record is not lost.
 */
@Component
public class DltPublisher {

    public static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, DltMessage> kafkaTemplate;

    public DltPublisher(KafkaTemplate<String, DltMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** @return true when the broker acknowledged the dead letter */
    public boolean publish(DltMessage message) {
        try {
            kafkaTemplate.send(KafkaTopics.DEAD_LETTER_EVENTS, message.diagnosticId(), message)
                    .get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
