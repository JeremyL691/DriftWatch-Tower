package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.dashboard.DashboardWebSocketHandler;
import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.source.SourceHealthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminal persistence consumer for {@code quality-events}: writes the raw event + alerts,
 * refreshes source health (idempotent transition-only STALE alert), broadcasts to the dashboard
 * over WebSocket, and updates Micrometer counters. Replaces the persistence half of the legacy
 * {@code QualityProcessor} once the streams path is enabled.
 */
@Component
@ConditionalOnProperty(name = "driftwatch.streams.enabled", havingValue = "true")
public class QualityEventSink {

    private final RawEventRepository rawEventRepository;
    private final QualityAlertRepository alertRepository;
    private final SourceHealthService sourceHealthService;
    private final MetricWindowProjector metricWindowProjector;
    private final DashboardWebSocketHandler ws;
    private final ObjectMapper objectMapper;
    private final Counter eventCounter;
    private final Counter alertCounter;

    public QualityEventSink(RawEventRepository rawEventRepository,
                            QualityAlertRepository alertRepository,
                            SourceHealthService sourceHealthService,
                            MetricWindowProjector metricWindowProjector,
                            DashboardWebSocketHandler ws,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry) {
        this.rawEventRepository = rawEventRepository;
        this.alertRepository = alertRepository;
        this.sourceHealthService = sourceHealthService;
        this.metricWindowProjector = metricWindowProjector;
        this.ws = ws;
        this.objectMapper = objectMapper;
        this.eventCounter = Counter.builder("driftwatch.events.ingested")
                .description("Total events ingested")
                .register(meterRegistry);
        this.alertCounter = Counter.builder("driftwatch.alerts.fired")
                .description("Total alerts fired")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = KafkaTopics.QUALITY_EVENTS,
            groupId = "driftwatch-sink",
            properties = {
                    "spring.json.value.default.type=com.driftwatch.stream.ProcessedEvent",
                    "spring.json.trusted.packages=com.driftwatch.event,com.driftwatch.quality,com.driftwatch.stream"
            })
    @Transactional
    public void onProcessed(ProcessedEvent p) {
        Instant now = p.receivedAt();
        DataEvent event = p.event();

        RawEventEntity raw = new RawEventEntity();
        raw.setEventId(event.eventId());
        raw.setSource(event.source());
        raw.setEventType(event.eventType());
        raw.setEventTimestamp(event.eventTimestamp());
        raw.setReceivedAt(now);
        raw.setPayloadJson(objectMapper.valueToTree(event.payload()));
        raw.setPayloadHash(p.payloadHash());
        raw.setQualityStatus(p.qualityStatus());
        rawEventRepository.save(raw);

        List<QualityAlertEntity> alerts = new ArrayList<>();
        for (ProcessedEvent.ProcessedAlert a : p.alerts()) {
            alerts.add(toEntity(a, now));
        }
        List<QualityAlertEntity> staleAlerts = sourceHealthService.refreshAllAndPersist(now);
        if (!alerts.isEmpty()) {
            alertRepository.saveAll(alerts);
        }
        if (!staleAlerts.isEmpty()) {
            alertRepository.saveAll(staleAlerts);
            alerts.addAll(staleAlerts);
        }

        metricWindowProjector.project(p);
        eventCounter.increment();
        alertCounter.increment(alerts.size());

        ws.broadcastEvent(raw);
        alerts.forEach(ws::broadcastAlert);
    }

    private QualityAlertEntity toEntity(ProcessedEvent.ProcessedAlert a, Instant now) {
        QualityAlertEntity e = new QualityAlertEntity();
        e.setAlertType(a.type());
        e.setSeverity(a.severity());
        e.setSource(a.source());
        e.setEventType(a.eventType());
        e.setFieldPath(a.fieldPath());
        e.setMessage(a.message());
        e.setEvidenceJson(a.evidence());
        e.setCreatedAt(now);
        return e;
    }
}
