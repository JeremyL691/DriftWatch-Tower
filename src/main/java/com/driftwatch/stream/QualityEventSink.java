package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.dashboard.DashboardWebSocketHandler;
import com.driftwatch.dlt.DltMessage;
import com.driftwatch.dlt.DltPublisher;
import com.driftwatch.dlt.DltStage;
import com.driftwatch.persistence.QualityAlertEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Terminal consumer for {@code quality-events-v1} (execution guide, sections 4.3 and 7.1).
 *
 * <p>Each record is persisted through {@link SinkPersistenceService} with a bounded retry
 * (immediately, then 2s, 10s, 30s — four attempts in total). A record that still fails is
 * published to {@code dead-letter-events-v1} keeping its ingestion identity; the listener only
 * returns once the broker acknowledged that dead letter, so a failed DLT publish leaves the
 * original offset uncommitted and the record is not lost. Success counters and WebSocket
 * broadcasts happen after the transaction committed, never before.
 */
@Component
@ConditionalOnProperty(name = "driftwatch.streams.enabled", havingValue = "true")
public class QualityEventSink {

    private static final Logger log = LoggerFactory.getLogger(QualityEventSink.class);

    /** Attempt 1 is immediate; retries follow at 2s, 10s and 30s (four attempts in total). */
    static final long[] RETRY_DELAYS_MS = {0L, 2_000L, 10_000L, 30_000L};

    private final long[] retryDelaysMs;
    private final SinkPersistenceService persistence;
    private final DashboardWebSocketHandler ws;
    private final DltPublisher dltPublisher;
    private final ObjectMapper objectMapper;
    private final Counter eventCounter;
    private final Counter alertCounter;
    private final Counter deadLetterCounter;

    @Autowired
    public QualityEventSink(SinkPersistenceService persistence,
                            DashboardWebSocketHandler ws,
                            DltPublisher dltPublisher,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry) {
        this(persistence, ws, dltPublisher, objectMapper, meterRegistry, RETRY_DELAYS_MS);
    }

    /** Test-friendly constructor: the retry schedule is the only thing that changes. */
    QualityEventSink(SinkPersistenceService persistence,
                     DashboardWebSocketHandler ws,
                     DltPublisher dltPublisher,
                     ObjectMapper objectMapper,
                     MeterRegistry meterRegistry,
                     long[] retryDelaysMs) {
        this.retryDelaysMs = retryDelaysMs;
        this.persistence = persistence;
        this.ws = ws;
        this.dltPublisher = dltPublisher;
        this.objectMapper = objectMapper;
        this.eventCounter = Counter.builder("driftwatch.events.ingested")
                .description("Total events ingested")
                .register(meterRegistry);
        this.alertCounter = Counter.builder("driftwatch.alerts.fired")
                .description("Total alerts fired")
                .register(meterRegistry);
        this.deadLetterCounter = Counter.builder("driftwatch_sink_dead_letters_total")
                .description("Records that failed persistence and were written to the dead-letter topic")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = KafkaTopics.QUALITY_EVENTS_V1,
            groupId = "driftwatch-sink",
            properties = {
                    "spring.json.value.default.type=com.driftwatch.stream.ProcessedEvent",
                    "spring.json.trusted.packages=com.driftwatch.event,com.driftwatch.quality,com.driftwatch.stream"
            })
    public void onProcessed(ProcessedEvent p) {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < retryDelaysMs.length; attempt++) {
            if (attempt > 0) {
                sleepQuietly(retryDelaysMs[attempt]);
            }
            try {
                SinkPersistenceService.PersistResult result = persistence.persist(p);
                if (result.duplicate()) {
                    return;
                }
                eventCounter.increment();
                alertCounter.increment(result.alerts().size());
                ws.broadcastEvent(result.raw());
                result.alerts().forEach(ws::broadcastAlert);
                return;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("sink attempt {}/{} failed for ingestion {}: {}",
                        attempt + 1, retryDelaysMs.length, SinkPersistenceService.ingestionIdOf(p),
                        e.getClass().getSimpleName());
            }
        }
        handlePersistentFailure(p, lastFailure);
    }

    private void handlePersistentFailure(ProcessedEvent p, Exception failure) {
        DltMessage deadLetter = new DltMessage(
                DltMessage.diagnosticIdFor(SinkPersistenceService.ingestionIdOf(p),
                        KafkaTopics.QUALITY_EVENTS_V1, null, null),
                DltStage.SINK,
                SinkPersistenceService.ingestionIdOf(p),
                p.event() == null ? null : p.event().source(),
                p.event() == null ? null : p.event().eventType(),
                DltMessage.truncate(failure == null ? "unknown failure"
                        : failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                        DltMessage.MAX_REASON_LENGTH),
                retryDelaysMs.length,
                KafkaTopics.QUALITY_EVENTS_V1,
                null, null,
                DltMessage.truncate(serialize(p), DltMessage.MAX_PAYLOAD_LENGTH),
                Instant.now());
        if (!dltPublisher.publish(deadLetter)) {
            // The dead letter is not durable either: keep the offset uncommitted and let the
            // consumer retry later rather than dropping the record.
            throw new IllegalStateException("sink failed and the dead-letter publish was not acknowledged;"
                    + " the record must be retried", failure);
        }
        deadLetterCounter.increment();
        log.error("ingestion {} moved to the dead-letter topic after {} attempts: {}",
                deadLetter.ingestionId(), retryDelaysMs.length, deadLetter.reason());
    }

    private String serialize(ProcessedEvent p) {
        try {
            return objectMapper.writeValueAsString(p);
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying the sink", e);
        }
    }
}
