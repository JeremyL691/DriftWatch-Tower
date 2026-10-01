package com.driftwatch.event;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.quality.ScopeKey;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a {@link RawEnvelope} to {@code raw-events-v1}. The ingest API waits for the broker
 * acknowledgement (guide 3.1/4.2): the caller only sees 202 after the record was accepted by
 * the broker, and a timeout is reported as 503 with the retry hint instead of a false success.
 */
@Component
public class RawEventProducer {

    public static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, RawEnvelope> kafkaTemplate;

    public RawEventProducer(KafkaTemplate<String, RawEnvelope> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, RawEnvelope>> publish(RawEnvelope envelope) {
        return kafkaTemplate.send(KafkaTopics.RAW_EVENTS_V1, partitionKey(envelope.event()), envelope);
    }

    /** Blocks until the broker acknowledged the record; false on timeout or failure. */
    public boolean publishAndAwait(RawEnvelope envelope) {
        try {
            publish(envelope).get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Canonical scope key: source/event_type encoded as a JSON array, never a bare separator. */
    public static String partitionKey(DataEvent event) {
        return ScopeKey.of(event.source(), event.eventType());
    }
}
