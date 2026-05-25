package com.driftwatch.quality;

import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Flags two duplicate cases:
 *   - REPEATED_EVENT_ID: same event_id has already been persisted
 *   - REPEATED_PAYLOAD: same payload_hash seen within configured window under a different event_id
 */
@Component
public class DuplicateDetector implements QualityDetector {

    private final RawEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Duration payloadWindow;

    public DuplicateDetector(RawEventRepository repository,
                             ObjectMapper objectMapper,
                             @Value("${driftwatch.detector.duplicate.payload-window:PT5M}") Duration payloadWindow) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.payloadWindow = payloadWindow;
    }

    @Override
    public List<DraftAlert> detect(DetectionContext ctx) {
        List<DraftAlert> alerts = new ArrayList<>(2);

        if (repository.existsByEventId(ctx.event().eventId())) {
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.put("duplicate_kind", "REPEATED_EVENT_ID");
            evidence.put("event_id", ctx.event().eventId());
            evidence.put("source", ctx.event().source());
            alerts.add(new DraftAlert(
                    AlertType.DUPLICATE_EVENT, Severity.INFO,
                    ctx.event().source(), ctx.event().eventType(),
                    null,
                    "Repeated event_id " + ctx.event().eventId(),
                    evidence
            ));
        }

        Optional<RawEventEntity> hashMatch = repository
                .findFirstByPayloadHashAndReceivedAtAfterAndEventIdNot(
                        ctx.payloadHash(),
                        ctx.receivedAt().minus(payloadWindow),
                        ctx.event().eventId());
        if (hashMatch.isPresent()) {
            RawEventEntity prev = hashMatch.get();
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.put("duplicate_kind", "REPEATED_PAYLOAD");
            evidence.put("payload_hash", ctx.payloadHash());
            evidence.put("window", payloadWindow.toString());
            evidence.put("current_event_id", ctx.event().eventId());
            evidence.put("first_event_id", prev.getEventId());
            evidence.put("first_seen_at", prev.getReceivedAt().toString());
            alerts.add(new DraftAlert(
                    AlertType.DUPLICATE_EVENT, Severity.INFO,
                    ctx.event().source(), ctx.event().eventType(),
                    null,
                    "Repeated payload hash within " + payloadWindow + "; first event_id=" + prev.getEventId(),
                    evidence
            ));
        }

        return alerts;
    }
}
