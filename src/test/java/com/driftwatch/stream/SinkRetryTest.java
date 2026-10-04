package com.driftwatch.stream;

import com.driftwatch.dashboard.DashboardWebSocketHandler;
import com.driftwatch.dlt.DltMessage;
import com.driftwatch.dlt.DltPublisher;
import com.driftwatch.dlt.DltStage;
import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.RawEventEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gate G06: the sink's bounded retry and dead-letter behaviour (guide 7.1).
 *
 * <p>The retry schedule is injected so the test runs instantly; production uses
 * immediate/2s/10s/30s. A record that still fails is dead-lettered, and if the dead letter itself
 * cannot be published the listener throws so the original offset is not committed.
 */
class SinkRetryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private ProcessedEvent event() {
        DataEvent event = new DataEvent("retry-1", "retry-source", "retry_event",
                Instant.parse("2026-10-01T00:00:00Z"), Map.of("bid", 1.0));
        return new ProcessedEvent(event, UUID.randomUUID(), Instant.now(), "REST", "LIVE", "hash",
                "OK", "rules-test", "APPLIED",
                new WindowEvaluation("[]", null, null, WindowEvaluation.Outcome.INCLUDED, null, null),
                List.of());
    }

    private QualityEventSink sink(SinkPersistenceService persistence, DltPublisher publisher) {
        return new QualityEventSink(persistence, mock(DashboardWebSocketHandler.class), publisher,
                objectMapper, new SimpleMeterRegistry(), new long[]{0L, 0L, 0L, 0L},
                mock(com.driftwatch.operations.DriftwatchMetrics.class));
    }

    @Test
    void transientFailureIsRetriedWithoutDeadLettering() {
        SinkPersistenceService persistence = mock(SinkPersistenceService.class);
        DltPublisher publisher = mock(DltPublisher.class);
        ProcessedEvent processed = event();
        when(persistence.persist(processed))
                .thenThrow(new org.springframework.dao.TransientDataAccessResourceException("db down"))
                .thenThrow(new org.springframework.dao.TransientDataAccessResourceException("db down"))
                .thenReturn(new SinkPersistenceService.PersistResult(false, new RawEventEntity(), List.of()));

        sink(persistence, publisher).onProcessed(processed);

        verify(persistence, times(3)).persist(processed);
        verify(publisher, never()).publish(any());
    }

    @Test
    void persistentFailurePublishesASinkDeadLetterWithTheOriginalIdentity() {
        SinkPersistenceService persistence = mock(SinkPersistenceService.class);
        DltPublisher publisher = mock(DltPublisher.class);
        ProcessedEvent processed = event();
        when(persistence.persist(processed))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("still failing"));
        when(publisher.publish(any())).thenReturn(true);

        sink(persistence, publisher).onProcessed(processed);

        verify(persistence, times(4)).persist(processed);
        ArgumentCaptor<DltMessage> captor = ArgumentCaptor.forClass(DltMessage.class);
        verify(publisher).publish(captor.capture());
        DltMessage deadLetter = captor.getValue();
        assertThat(deadLetter.stage()).isEqualTo(DltStage.SINK);
        assertThat(deadLetter.ingestionId()).isEqualTo(processed.ingestionId().toString());
        assertThat(deadLetter.diagnosticId()).isEqualTo("ingestion:" + processed.ingestionId());
        assertThat(deadLetter.attempts()).isEqualTo(4);
        assertThat(deadLetter.reason()).contains("DataIntegrityViolationException");
        assertThat(deadLetter.payload()).contains("retry-1");
    }

    @Test
    void failedDeadLetterPublishKeepsTheOffsetUncommitted() {
        SinkPersistenceService persistence = mock(SinkPersistenceService.class);
        DltPublisher publisher = mock(DltPublisher.class);
        ProcessedEvent processed = event();
        when(persistence.persist(processed)).thenThrow(new RuntimeException("db down"));
        when(publisher.publish(any())).thenReturn(false);

        assertThatThrownBy(() -> sink(persistence, publisher).onProcessed(processed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dead-letter publish was not acknowledged");
    }

    @Test
    void duplicateReceiptIsNotCountedAsANewIngestion() {
        SinkPersistenceService persistence = mock(SinkPersistenceService.class);
        DltPublisher publisher = mock(DltPublisher.class);
        ProcessedEvent processed = event();
        when(persistence.persist(processed))
                .thenReturn(new SinkPersistenceService.PersistResult(true, null, List.of()));

        sink(persistence, publisher).onProcessed(processed);

        verify(persistence, times(1)).persist(processed);
        verify(publisher, never()).publish(any());
    }
}
