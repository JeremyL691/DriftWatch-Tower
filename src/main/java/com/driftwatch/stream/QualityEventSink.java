package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.dashboard.DashboardWebSocketHandler;
import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.ProcessedReceiptEntity;
import com.driftwatch.persistence.ProcessedReceiptRepository;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.RuleVersions;
import com.driftwatch.quality.Severity;
import com.driftwatch.quality.schema.SchemaObservationService;
import com.driftwatch.source.SourceHealthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final ProcessedReceiptRepository processedReceiptRepository;
    private final QualityAlertRepository alertRepository;
    private final SourceHealthService sourceHealthService;
    private final SchemaObservationService schemaObservationService;
    private final MetricWindowProjector metricWindowProjector;
    private final DashboardWebSocketHandler ws;
    private final ObjectMapper objectMapper;
    private final Counter eventCounter;
    private final Counter alertCounter;

    public QualityEventSink(RawEventRepository rawEventRepository,
                            ProcessedReceiptRepository processedReceiptRepository,
                            QualityAlertRepository alertRepository,
                            SourceHealthService sourceHealthService,
                            SchemaObservationService schemaObservationService,
                            MetricWindowProjector metricWindowProjector,
                            DashboardWebSocketHandler ws,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry) {
        this.rawEventRepository = rawEventRepository;
        this.processedReceiptRepository = processedReceiptRepository;
        this.alertRepository = alertRepository;
        this.sourceHealthService = sourceHealthService;
        this.schemaObservationService = schemaObservationService;
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
            topics = KafkaTopics.QUALITY_EVENTS_V1,
            groupId = "driftwatch-sink",
            properties = {
                    "spring.json.value.default.type=com.driftwatch.stream.ProcessedEvent",
                    "spring.json.trusted.packages=com.driftwatch.event,com.driftwatch.quality,com.driftwatch.stream"
            })
    @Transactional
    public void onProcessed(ProcessedEvent p) {
        Instant now = p.receivedAt();
        String ingestionId = p.ingestionId() == null
                ? "legacy:" + p.payloadHash()
                : p.ingestionId().toString();
        String contentDigest = contentDigest(p);
        // Idempotency guard (guide 4.3): a redelivered ingestion is recognised by its receipt and
        // returns without touching any projection. A receipt with different content is a failure.
        var existingReceipt = processedReceiptRepository.findById(ingestionId);
        if (existingReceipt.isPresent()) {
            if (existingReceipt.get().getContentDigest().equals(contentDigest)) {
                return;
            }
            throw new IllegalStateException("ingestion " + ingestionId
                    + " was already processed with different content");
        }
        ProcessedReceiptEntity receipt = new ProcessedReceiptEntity();
        receipt.setIngestionId(ingestionId);
        receipt.setProcessedAt(now);
        receipt.setRuleVersion(p.ruleVersion() == null ? "unknown" : p.ruleVersion());
        receipt.setContentDigest(contentDigest);
        processedReceiptRepository.save(receipt);
        DataEvent event = p.event();

        RawEventEntity raw = new RawEventEntity();
        raw.setEventId(event.eventId());
        raw.setIngestionId(ingestionId);
        raw.setOrigin(p.origin());
        raw.setMode(p.mode());
        raw.setBaselineStatus(p.baselineStatus());
        raw.setWindowEvaluation(p.windowEvaluation() == null
                ? null : objectMapper.valueToTree(p.windowEvaluation()));
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
            alerts.add(toEntity(a, ingestionId, p, now));
        }
        // Schema observation happens in this transaction under an advisory lock (guide 4.4);
        // drift alerting therefore never runs on a topology thread.
        SchemaObservationService.Observation observation =
                schemaObservationService.observe(event.eventType(), raw.getPayloadJson(), now);
        if (observation.drift() && !observation.changedFields().isEmpty()) {
            alerts.add(schemaDriftAlert(event, observation, now));
        }
        List<QualityAlertEntity> staleAlerts = sourceHealthService.refreshAllAndPersist(now);
        if (!alerts.isEmpty()) {
            alertRepository.saveAll(alerts);
        }
        alerts.addAll(staleAlerts);

        metricWindowProjector.project(p);
        eventCounter.increment();
        alertCounter.increment(alerts.size());

        ws.broadcastEvent(raw);
        alerts.forEach(ws::broadcastAlert);
    }

    private QualityAlertEntity schemaDriftAlert(DataEvent event,
                                                SchemaObservationService.Observation observation,
                                                Instant now) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("baseline_version_id",
                observation.baseline() == null ? null : observation.baseline().getId());
        evidence.put("observed_version_id", observation.observedRow().getId());
        evidence.put("observed_hash", observation.observedHash());
        evidence.put("rule_version", RuleVersions.RULES_VERSION);
        if (observation.baseline() != null) {
            evidence.set("expected_schema", observation.baseline().getSchemaJson());
        }
        evidence.set("observed_schema", objectMapper.valueToTree(observation.observedSchema()));
        evidence.set("missing_fields", arrayOf(observation.diff().missing()));
        evidence.set("added_fields", arrayOf(observation.diff().added()));
        ObjectNode typeChanged = objectMapper.createObjectNode();
        observation.diff().typeChanged().forEach((field, change) -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("expected", change[0]);
            node.put("observed", change[1]);
            typeChanged.set(field, node);
        });
        evidence.set("type_changed", typeChanged);

        String firstField = observation.diff().typeChanged().keySet().stream().findFirst()
                .or(() -> observation.diff().missing().stream().findFirst())
                .or(() -> observation.diff().added().stream().findFirst())
                .orElse(null);

        QualityAlertEntity alert = new QualityAlertEntity();
        alert.setAlertType(AlertType.SCHEMA_DRIFT);
        alert.setSeverity(Severity.WARN);
        alert.setSource(event.source());
        alert.setEventType(event.eventType());
        alert.setFieldPath(firstField);
        alert.setMessage("Schema drift in " + event.eventType()
                + " (missing=" + observation.diff().missing().size()
                + ", added=" + observation.diff().added().size()
                + ", type_changed=" + observation.diff().typeChanged().size() + ")");
        alert.setEvidenceJson(evidence);
        alert.setCreatedAt(now);
        return alert;
    }

    private JsonNode arrayOf(java.util.Collection<String> values) {
        var array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private String contentDigest(ProcessedEvent p) {
        String material = p.payloadHash() + "|" + (p.event() == null ? "" : p.event().eventId());
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private QualityAlertEntity toEntity(ProcessedEvent.ProcessedAlert a, String ingestionId,
                                        ProcessedEvent p, Instant now) {
        QualityAlertEntity e = new QualityAlertEntity();
        e.setIngestionId(ingestionId);
        e.setDetectorKey(a.type().name() + ":" + (a.fieldPath() == null ? "-" : a.fieldPath()));
        e.setWindowKey(p.windowEvaluation() == null || p.windowEvaluation().windowStart() == null
                ? null : p.windowEvaluation().windowStart().toString());
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
