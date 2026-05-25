package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.driftwatch.event.PayloadHasher;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single ingress for events arriving off the Kafka topic.
 * Runs every registered detector against the event, persists the raw event with a
 * quality_status reflecting the detector outcome, and writes alert rows.
 */
@Service
public class QualityProcessor {

    private static final Logger log = LoggerFactory.getLogger(QualityProcessor.class);

    private final List<QualityDetector> detectors;
    private final RawEventRepository rawEventRepository;
    private final QualityAlertRepository alertRepository;
    private final PayloadHasher hasher;
    private final ObjectMapper objectMapper;

    public QualityProcessor(List<QualityDetector> detectors,
                            RawEventRepository rawEventRepository,
                            QualityAlertRepository alertRepository,
                            PayloadHasher hasher,
                            ObjectMapper objectMapper) {
        this.detectors = detectors;
        this.rawEventRepository = rawEventRepository;
        this.alertRepository = alertRepository;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProcessResult process(DataEvent event) {
        Instant now = Instant.now();
        String payloadHash = hasher.hash(event.payload());
        DetectionContext ctx = new DetectionContext(event, payloadHash, now);

        List<DraftAlert> drafts = new ArrayList<>();
        for (QualityDetector detector : detectors) {
            try {
                drafts.addAll(detector.detect(ctx));
            } catch (RuntimeException e) {
                log.warn("Detector {} failed for event_id={}", detector.getClass().getSimpleName(), event.eventId(), e);
            }
        }

        String qualityStatus = qualityStatusFor(drafts);

        RawEventEntity raw = new RawEventEntity();
        raw.setEventId(event.eventId());
        raw.setSource(event.source());
        raw.setEventType(event.eventType());
        raw.setEventTimestamp(event.eventTimestamp());
        raw.setReceivedAt(now);
        raw.setPayloadJson(objectMapper.valueToTree(event.payload()));
        raw.setPayloadHash(payloadHash);
        raw.setQualityStatus(qualityStatus);
        rawEventRepository.save(raw);

        List<QualityAlertEntity> alerts = drafts.stream()
                .map(d -> toEntity(d, now))
                .collect(Collectors.toList());
        if (!alerts.isEmpty()) {
            alertRepository.saveAll(alerts);
        }

        return new ProcessResult(raw, alerts);
    }

    private static String qualityStatusFor(List<DraftAlert> drafts) {
        if (drafts.isEmpty()) return "OK";
        if (drafts.stream().anyMatch(d -> d.type() == AlertType.DUPLICATE_EVENT)) return "DUPLICATE";
        if (drafts.stream().anyMatch(d -> d.type() == AlertType.LATE_EVENT)) return "LATE";
        return "FLAGGED";
    }

    private QualityAlertEntity toEntity(DraftAlert d, Instant now) {
        QualityAlertEntity e = new QualityAlertEntity();
        e.setAlertType(d.type());
        e.setSeverity(d.severity());
        e.setSource(d.source());
        e.setEventType(d.eventType());
        e.setFieldPath(d.fieldPath());
        e.setMessage(d.message());
        e.setEvidenceJson(d.evidence());
        e.setCreatedAt(now);
        return e;
    }

    public record ProcessResult(RawEventEntity rawEvent, List<QualityAlertEntity> alerts) {}
}
