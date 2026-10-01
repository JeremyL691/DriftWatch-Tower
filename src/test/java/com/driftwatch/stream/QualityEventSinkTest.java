package com.driftwatch.stream;

import com.driftwatch.dashboard.DashboardWebSocketHandler;
import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.ProcessedReceiptRepository;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.quality.schema.SchemaInferrer;
import com.driftwatch.quality.schema.SchemaObservationService;
import com.driftwatch.source.AlertIncidentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sink persists what the detection pipeline produced and nothing else. Source health is an
 * aggregate over rolling windows and belongs to the scheduled refresh: recomputing it per event
 * made persistence O(rows) per event and was the measured throughput ceiling under load.
 */
class QualityEventSinkTest {

    @Test
    void persistsOnlyDetectionAlertsAndLeavesSourceHealthToTheScheduler() {
        RawEventRepository rawEventRepository = mock(RawEventRepository.class);
        QualityAlertRepository alertRepository = mock(QualityAlertRepository.class);
        SchemaObservationService schemaObservationService = mock(SchemaObservationService.class);
        MetricWindowProjector metricWindowProjector = mock(MetricWindowProjector.class);
        DashboardWebSocketHandler webSocket = mock(DashboardWebSocketHandler.class);
        ProcessedReceiptRepository processedReceiptRepository = mock(ProcessedReceiptRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        Instant receivedAt = Instant.parse("2026-06-01T12:00:00Z");
        DataEvent event = new DataEvent(
                "evt-001",
                "orders-api",
                "order_created",
                receivedAt.minusSeconds(30),
                Map.of("order_id", "order-001")
        );
        ProcessedEvent processed = new ProcessedEvent(
                event,
                UUID.randomUUID(),
                receivedAt,
                "REST",
                "LIVE",
                "hash-001",
                "OK",
                "RULES",
                "APPLIED",
                new WindowEvaluation("[]", receivedAt, receivedAt.plusSeconds(60),
                        WindowEvaluation.Outcome.INCLUDED, receivedAt, null),
                List.of()
        );

        when(processedReceiptRepository.findById(anyString())).thenReturn(Optional.empty());
        when(schemaObservationService.observe(anyString(), any(), any()))
                .thenReturn(new SchemaObservationService.Observation(
                        null, null, Map.of(), "hash", false,
                        new SchemaInferrer.SchemaDiff(Set.of(), Set.of(), Map.of()),
                        false));

        SinkPersistenceService persistence = new SinkPersistenceService(
                rawEventRepository,
                processedReceiptRepository,
                alertRepository,
                schemaObservationService,
                mock(AlertIncidentService.class),
                metricWindowProjector,
                new ObjectMapper().findAndRegisterModules());

        QualityEventSink sink = new QualityEventSink(
                persistence,
                webSocket,
                mock(com.driftwatch.dlt.DltPublisher.class),
                new ObjectMapper().findAndRegisterModules(),
                meterRegistry,
                mock(com.driftwatch.operations.DriftwatchMetrics.class)
        );

        sink.onProcessed(processed);

        // This event carries no detection alert, so nothing is written and nothing is broadcast:
        // a stale-source notice can only come from the scheduler, never from this path.
        verify(alertRepository, never()).saveAll(anyList());
        verify(webSocket, never()).broadcastAlert(any());
        verify(metricWindowProjector).project(processed);
    }
}
