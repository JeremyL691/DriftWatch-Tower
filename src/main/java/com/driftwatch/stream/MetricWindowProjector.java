package com.driftwatch.stream;

import com.driftwatch.quality.schema.SchemaBaselineProvider;
import com.driftwatch.quality.schema.SchemaInferrer;
import com.driftwatch.quality.window.MetricWindowRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Re-projects the {@code metric_windows} rows that the legacy DB-backed spike detectors used to
 * write (EVENT_COUNT, NULL_TOTAL/NULL_COUNT/NULL_RATE), so {@code SourceHealthService.nullRate}
 * and the metric-windows panel keep working now that the topology uses state stores for detection.
 * Mirrors the metric-writing half of the old {@code NullSpikeDetector}/{@code AnomalySpikeDetector}.
 */
@Component
public class MetricWindowProjector {

    private static final String EVENT_COUNT = "EVENT_COUNT";

    private final MetricWindowRecorder metrics;
    private final SchemaBaselineProvider baselineProvider;
    private final ObjectMapper objectMapper;

    public MetricWindowProjector(MetricWindowRecorder metrics,
                                 SchemaBaselineProvider baselineProvider,
                                 ObjectMapper objectMapper) {
        this.metrics = metrics;
        this.baselineProvider = baselineProvider;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void project(ProcessedEvent p) {
        String source = p.event().source();
        String eventType = p.event().eventType();
        metrics.increment(source, eventType, EVENT_COUNT, p.event().eventTimestamp(), 1.0d);

        Map<String, String> expected = baselineProvider.activeLeafFieldTypes(eventType);
        if (expected.isEmpty()) {
            return;
        }
        Map<String, String> observed = SchemaInferrer.infer(objectMapper.valueToTree(p.event().payload()));
        for (String field : expected.keySet()) {
            boolean nullish = !observed.containsKey(field) || "NULL".equals(observed.get(field));
            MetricWindowRecorder.MetricUpdate total = metrics.increment(
                    source, eventType, "NULL_TOTAL:" + field, p.event().eventTimestamp(), 1.0d);
            MetricWindowRecorder.MetricUpdate nulls = metrics.increment(
                    source, eventType, "NULL_COUNT:" + field, p.event().eventTimestamp(), nullish ? 1.0d : 0.0d);
            double rate = total.currentValue() <= 0 ? 0.0d : nulls.currentValue() / total.currentValue();
            metrics.set(source, eventType, "NULL_RATE:" + field, p.event().eventTimestamp(), rate);
        }
    }
}
