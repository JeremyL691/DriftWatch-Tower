package com.driftwatch.stream;

import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Windowed event-count anomaly detector. Mirrors {@code AnomalySpikeDetector}: counts events per
 * (source,eventType) in the current window, computes a baseline from the most recent completed
 * windows, and fires when the current window's count climbs well above that baseline.
 */
final class AnomalySpikeProcessor implements Processor<String, PendingEvent, String, PendingEvent> {

    private final ObjectMapper objectMapper;
    private final TopologySettings settings;
    private ProcessorContext<String, PendingEvent> context;
    private WindowStore<String, Long> store;

    AnomalySpikeProcessor(ObjectMapper objectMapper, TopologySettings settings) {
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    @Override
    public void init(ProcessorContext<String, PendingEvent> context) {
        this.context = context;
        this.store = context.getStateStore(QualityStreamsTopology.ANOMALY_STORE);
    }

    @Override
    public void process(Record<String, PendingEvent> record) {
        PendingEvent p = record.value();
        String key = p.event.source() + "|" + p.event.eventType();
        long windowSizeMs = settings.metricsWindowSize().toMillis();
        long windowStartMs = floorWindow(p.event.eventTimestamp().toEpochMilli(), windowSizeMs);

        long countBefore = currentCount(store, key, windowStartMs, windowSizeMs);
        long countAfter = countBefore + 1;
        store.put(key, countAfter, windowStartMs);

        long fromMs = windowStartMs - (long) settings.anomalyBaselineWindows() * windowSizeMs;
        List<KeyValue<Long, Long>> completed = new ArrayList<>();
        try (WindowStoreIterator<Long> it = store.fetch(key, fromMs, windowStartMs - 1)) {
            while (it.hasNext()) {
                completed.add(it.next());
            }
        }
        if (completed.size() < settings.anomalyMinHistoryWindows()) {
            context.forward(record);
            return;
        }
        completed.sort((a, b) -> Long.compare(b.key, a.key));
        List<Long> baselineCounts = completed.subList(0, Math.min(settings.anomalyBaselineWindows(), completed.size()))
                .stream().map(kv -> kv.value).toList();
        double baseline = baselineCounts.stream().mapToLong(Long::longValue).average().orElse(0.0);
        if (baseline <= 0) {
            context.forward(record);
            return;
        }

        double previousRatio = countBefore / baseline;
        double currentRatio = countAfter / baseline;
        if (countAfter < settings.anomalyMinCurrentCount()
                || previousRatio > settings.anomalySpikeRatio()
                || currentRatio <= settings.anomalySpikeRatio()) {
            context.forward(record);
            return;
        }

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("window_start", Instant.ofEpochMilli(windowStartMs).toString());
        evidence.put("window_end", Instant.ofEpochMilli(windowStartMs + windowSizeMs).toString());
        evidence.put("baseline", baseline);
        evidence.put("current_value", countAfter);
        evidence.put("ratio", currentRatio);
        evidence.put("threshold", settings.anomalySpikeRatio());
        evidence.put("history_windows", baselineCounts.size());
        p.alerts.add(new ProcessedEvent.ProcessedAlert(
                AlertType.ANOMALY_SPIKE, Severity.WARN, p.event.source(), p.event.eventType(), null,
                "Anomaly spike in " + p.event.eventType() + " window ("
                        + countAfter + " vs baseline " + String.format(Locale.ROOT, "%.2f", baseline) + ")",
                evidence));
        context.forward(record);
    }

    private static long currentCount(WindowStore<String, Long> store, String key, long windowStartMs, long windowSizeMs) {
        try (WindowStoreIterator<Long> it = store.fetch(key, windowStartMs, windowStartMs + windowSizeMs - 1)) {
            return it.hasNext() ? it.next().value : 0;
        }
    }

    private static long floorWindow(long epochMs, long windowSizeMs) {
        return epochMs - Math.floorMod(epochMs, windowSizeMs);
    }

    @Override
    public void close() {}
}
