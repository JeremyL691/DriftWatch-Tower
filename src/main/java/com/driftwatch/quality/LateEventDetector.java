package com.driftwatch.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** Flags events whose received_at is more than {threshold} after event_timestamp. */
@Component
@Order(20)
public class LateEventDetector implements QualityDetector {

    private final ObjectMapper objectMapper;
    private final Duration threshold;

    public LateEventDetector(ObjectMapper objectMapper,
                             @Value("${driftwatch.detector.late.threshold:PT5M}") Duration threshold) {
        this.objectMapper = objectMapper;
        this.threshold = threshold;
    }

    @Override
    public List<DraftAlert> detect(DetectionContext ctx) {
        Duration lateness = Duration.between(ctx.event().eventTimestamp(), ctx.receivedAt());
        if (lateness.compareTo(threshold) <= 0) {
            return List.of();
        }
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("event_timestamp", ctx.event().eventTimestamp().toString());
        evidence.put("received_at", ctx.receivedAt().toString());
        evidence.put("lateness_seconds", lateness.getSeconds());
        evidence.put("threshold", threshold.toString());
        return List.of(new DraftAlert(
                AlertType.LATE_EVENT,
                lateness.compareTo(threshold.multipliedBy(10)) > 0 ? Severity.WARN : Severity.INFO,
                ctx.event().source(), ctx.event().eventType(),
                null,
                "Event arrived " + lateness.getSeconds() + "s after event_timestamp (threshold " + threshold + ")",
                evidence
        ));
    }
}
