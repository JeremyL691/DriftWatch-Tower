package com.driftwatch.stream;

import com.driftwatch.dashboard.DashboardWebSocketHandler;
import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.ProcessedReceiptRepository;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.driftwatch.operations.DriftwatchMetrics;
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

    @Test
    void forwardsPersistedAlertToMetricsWithDetectorAndSeverityLabels() {
        var persistence = mock(SinkPersistenceService.class);
        var metrics = mock(DriftwatchMetrics.class);
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        DataEvent event = new DataEvent("metric-probe", "probe-source", "probe-event",
                now, Map.of("value", 1));
        ProcessedEvent processed = new ProcessedEvent(event, UUID.randomUUID(), now,
                "REST", "LIVE", "hash-metric", "OK", "RULES", "APPLIED",
                WindowEvaluation.notApplicable("[]", WindowEvaluation.Outcome.SKIPPED_MODE, null),
                List.of());
        QualityAlertEntity alert = new QualityAlertEntity();
        alert.setAlertType(AlertType.SCHEMA_DRIFT);
        alert.setSeverity(Severity.WARN);
        when(persistence.persist(processed)).thenReturn(
                new SinkPersistenceService.PersistResult(false, null, List.of(alert)));
        QualityEventSink sink = new QualityEventSink(persistence,
                mock(DashboardWebSocketHandler.class),
                mock(com.driftwatch.dlt.DltPublisher.class),
                new ObjectMapper().findAndRegisterModules(),
                new SimpleMeterRegistry(), metrics);

        sink.onProcessed(processed);

        verify(metrics).recordAlert(AlertType.SCHEMA_DRIFT, "WARN");
    }

    @Test
    void schemaDriftChangesOkStatusToFlaggedOnRawEvent() {
        RawEventRepository rawEventRepository = mock(RawEventRepository.class);
        ProcessedReceiptRepository processedReceiptRepository = mock(ProcessedReceiptRepository.class);
        QualityAlertRepository alertRepository = mock(QualityAlertRepository.class);
        SchemaObservationService schemaObservationService = mock(SchemaObservationService.class);
        MetricWindowProjector metricWindowProjector = mock(MetricWindowProjector.class);

        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        DataEvent event = new DataEvent("evt-drift", "source-1", "order_created", now, Map.of("new_field", "val"));
        ProcessedEvent processed = new ProcessedEvent(event, UUID.randomUUID(), now, "REST", "LIVE",
                "hash-drift", "OK", "RULES", "APPLIED", null, List.of());

        when(processedReceiptRepository.findById(anyString())).thenReturn(Optional.empty());
        var observedRow = mock(com.driftwatch.persistence.SchemaVersionEntity.class);
        when(observedRow.getId()).thenReturn(2L);
        var diff = new SchemaInferrer.SchemaDiff(Set.of(), Set.of("new_field"), Map.of());
        when(schemaObservationService.observe(anyString(), any(), any()))
                .thenReturn(new SchemaObservationService.Observation(null, observedRow, Map.of(), "h", true, diff, false));

        SinkPersistenceService persistence = new SinkPersistenceService(
                rawEventRepository, processedReceiptRepository, alertRepository,
                schemaObservationService, mock(AlertIncidentService.class), metricWindowProjector,
                new ObjectMapper().findAndRegisterModules());

        SinkPersistenceService.PersistResult result = persistence.persist(processed);
        org.assertj.core.api.Assertions.assertThat(result.raw().getQualityStatus()).isEqualTo("FLAGGED");
    }
}
