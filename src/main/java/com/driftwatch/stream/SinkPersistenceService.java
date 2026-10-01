package com.driftwatch.stream;

import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.ProcessedReceiptEntity;
import com.driftwatch.persistence.ProcessedReceiptRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.RuleVersions;
import com.driftwatch.quality.Severity;
import com.driftwatch.quality.schema.SchemaObservationService;
import com.driftwatch.source.AlertIncidentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The single transaction of the sink (execution guide, section 4.3): receipt, raw event, schema
 * observation, alerts and metric projections commit together. Nothing is broadcast and no
 * success counter moves here — the caller does that only after this transaction committed.
 *
 * <p>Kept separate from {@link QualityEventSink} so the bounded retry loop can start a fresh
 * transaction per attempt.
 */
@Service
public class SinkPersistenceService {

    private final RawEventRepository rawEventRepository;
    private final ProcessedReceiptRepository processedReceiptRepository;
    private final QualityAlertRepository alertRepository;
    private final SchemaObservationService schemaObservationService;
    private final AlertIncidentService alertIncidentService;
    private final MetricWindowProjector metricWindowProjector;
    private final ObjectMapper objectMapper;

    public SinkPersistenceService(RawEventRepository rawEventRepository,
                                  ProcessedReceiptRepository processedReceiptRepository,
                                  QualityAlertRepository alertRepository,
                                  SchemaObservationService schemaObservationService,
                                  AlertIncidentService alertIncidentService,
                                  MetricWindowProjector metricWindowProjector,
                                  ObjectMapper objectMapper) {
        this.rawEventRepository = rawEventRepository;
        this.processedReceiptRepository = processedReceiptRepository;
        this.alertRepository = alertRepository;
        this.schemaObservationService = schemaObservationService;
        this.alertIncidentService = alertIncidentService;
        this.metricWindowProjector = metricWindowProjector;
        this.objectMapper = objectMapper;
    }

    /** Outcome of one persistence attempt. */
    public record PersistResult(boolean duplicate, RawEventEntity raw, List<QualityAlertEntity> alerts) {}

    @Transactional
    public PersistResult persist(ProcessedEvent p) {
        Instant now = p.receivedAt();
        String ingestionId = ingestionIdOf(p);
        String contentDigest = contentDigest(p);
        var existingReceipt = processedReceiptRepository.findById(ingestionId);
        if (existingReceipt.isPresent()) {
            if (existingReceipt.get().getContentDigest().equals(contentDigest)) {
                return new PersistResult(true, null, List.of());
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
        // Schema observation runs in this transaction under an advisory lock (guide 4.4).
        SchemaObservationService.Observation observation =
                schemaObservationService.observe(event.eventType(), raw.getPayloadJson(), now);
        if (observation.drift() && !observation.changedFields().isEmpty()) {
            alerts.add(schemaDriftAlert(event, observation, now));
        }
        if (!alerts.isEmpty()) {
            alertRepository.saveAll(alerts);
        }
        // Source health is owned by the scheduled refresh (guide 7.2), not by this path: it is an
        // aggregate over rolling windows, so recomputing it per event made the pipeline O(rows)
        // per event and was the measured throughput ceiling under load. The scheduler keeps the
        // dashboard's freshness within its 30s tick, and collector poll state carries the
        // realtime signal.
        List<QualityAlertEntity> persisted = new ArrayList<>(alerts);
        // Non-INFO alerts join an incident for their scope; INFO alerts stay in the alert list so
        // repeated notices cannot flood the incident view (guide 7.2).
        for (QualityAlertEntity alert : persisted) {
            alertIncidentService.correlate(alert);
        }

        metricWindowProjector.project(p);
        return new PersistResult(false, raw, persisted);
    }

    public static String ingestionIdOf(ProcessedEvent p) {
        return p.ingestionId() == null ? "legacy:" + p.payloadHash() : p.ingestionId().toString();
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
