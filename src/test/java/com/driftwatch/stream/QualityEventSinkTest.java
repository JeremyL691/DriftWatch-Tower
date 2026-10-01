package com.driftwatch.stream;

import com.driftwatch.dashboard.DashboardWebSocketHandler;
import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.driftwatch.source.SourceHealthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityEventSinkTest {

    @Test
    void doesNotPersistStaleAlertsTwice() {
        RawEventRepository rawEventRepository = mock(RawEventRepository.class);
        QualityAlertRepository alertRepository = mock(QualityAlertRepository.class);
        SourceHealthService sourceHealthService = mock(SourceHealthService.class);
        com.driftwatch.quality.schema.SchemaObservationService schemaObservationService =
                mock(com.driftwatch.quality.schema.SchemaObservationService.class);
        MetricWindowProjector metricWindowProjector = mock(MetricWindowProjector.class);
        DashboardWebSocketHandler webSocket = mock(DashboardWebSocketHandler.class);
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
                java.util.UUID.randomUUID(),
                receivedAt,
                "REST",
                "LIVE",
                "hash",
                "PASSED",
                "rules-test",
                "APPLIED",
                new WindowEvaluation("[]", receivedAt, receivedAt.plusSeconds(60),
                        WindowEvaluation.Outcome.INCLUDED, receivedAt, null),
                List.of()
        );

        QualityAlertEntity staleAlert = new QualityAlertEntity();
        staleAlert.setAlertType(AlertType.STALE_SOURCE);
        staleAlert.setSeverity(Severity.WARN);
        staleAlert.setSource("legacy-feed");
        staleAlert.setEventType("order_created");
        staleAlert.setMessage("Source legacy-feed is stale");
        staleAlert.setCreatedAt(receivedAt);
        when(sourceHealthService.refreshAllAndPersist(receivedAt)).thenReturn(List.of(staleAlert));

        when(schemaObservationService.observe(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.driftwatch.quality.schema.SchemaObservationService.Observation(
                        null, null, java.util.Map.of(), "hash", false,
                        new com.driftwatch.quality.schema.SchemaInferrer.SchemaDiff(java.util.Set.of(), java.util.Set.of(), java.util.Map.of()),
                        false));

        QualityEventSink sink = new QualityEventSink(
                rawEventRepository,
                alertRepository,
                sourceHealthService,
                schemaObservationService,
                metricWindowProjector,
                webSocket,
                new ObjectMapper(),
                meterRegistry
        );

        sink.onProcessed(processed);

        verify(alertRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(webSocket).broadcastAlert(staleAlert);
        verify(metricWindowProjector).project(processed);
    }
}
