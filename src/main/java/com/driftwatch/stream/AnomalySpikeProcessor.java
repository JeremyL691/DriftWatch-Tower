package com.driftwatch.stream;

import com.driftwatch.event.RawEnvelope;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.ScopeKey;
import com.driftwatch.quality.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.util.Locale;

/**
 * Windowed event-count anomaly detector (execution guide, sections 5.1-5.2).
 *
 * <p>The baseline is the mean of the two windows immediately before the current one, counting
 * zero windows that fall inside the observed range. Firing requires at least two complete
 * history windows, the minimum current count, a positive baseline and a ratio above the
 * threshold, and happens once per window ({@code fired}) rather than on a
 * previous-versus-current transition. Insufficient history records {@code WARMING_UP} and a
 * zero baseline records {@code BASELINE_ZERO}; neither fabricates history or triggers an alert.
 */
final class AnomalySpikeProcessor implements Processor<String, PendingEvent, String, PendingEvent> {

    private final ObjectMapper objectMapper;
    private final TopologySettings settings;
    private ProcessorContext<String, PendingEvent> context;
    private KeyValueStore<String, AnomalyWindowState> windowStore;
    private KeyValueStore<String, Long> observationStore;
    private KeyValueStore<String, Long> watermarkStore;

    AnomalySpikeProcessor(ObjectMapper objectMapper, TopologySettings settings) {
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    @Override
    public void init(ProcessorContext<String, PendingEvent> context) {
        this.context = context;
        this.windowStore = context.getStateStore(QualityStreamsTopology.ANOMALY_WINDOW_STORE);
        this.observationStore = context.getStateStore(QualityStreamsTopology.ANOMALY_SCOPE_STORE);
        this.watermarkStore = context.getStateStore(QualityStreamsTopology.SCOPE_WATERMARK_STORE);
        context.schedule(settings.metricsWindowSize(), PunctuationType.STREAM_TIME, this::evictExpired);
    }

    @Override
    public void process(Record<String, PendingEvent> record) {
        PendingEvent pending = record.value();
        if (pending.skipDetection) {
            context.forward(record);
            return;
        }
        RawEnvelope envelope = pending.envelope;
        String source = envelope.event().source();
        String eventType = envelope.event().eventType();
        String scope = ScopeKey.of(source, eventType);
        long windowSizeMs = settings.metricsWindowSize().toMillis();
        long eventMs = envelope.event().eventTimestamp().toEpochMilli();
        long windowStartMs = WindowPolicy.floorWindow(eventMs, windowSizeMs);

        if (!envelope.liveWindowEligible()) {
            if (pending.evaluation == null) {
                pending.evaluation = WindowPolicy.evaluation(scope, windowStartMs, windowSizeMs,
                        WindowEvaluation.Outcome.SKIPPED_MODE, Long.MIN_VALUE, envelope.mode().name());
            }
            context.forward(record);
            return;
        }

        Long watermarkBefore = watermarkStore.get(scope);
        long watermark = watermarkBefore == null ? eventMs : watermarkBefore;
        WindowEvaluation.Outcome outcome = WindowPolicy.classify(
                eventMs, windowSizeMs, settings.metricsGrace().toMillis(),
                settings.metricsFutureTolerance().toMillis(), watermark, envelope.receivedAt());
        if (outcome != WindowEvaluation.Outcome.INCLUDED) {
            if (pending.evaluation == null) {
                pending.evaluation = WindowPolicy.evaluation(scope, windowStartMs, windowSizeMs,
                        outcome, watermarkBefore == null ? Long.MIN_VALUE : watermarkBefore,
                        outcome == WindowEvaluation.Outcome.EXPIRED ? "WINDOW_CLOSED" : "FUTURE_TIMESTAMP");
            }
            context.forward(record);
            return;
        }
        if (pending.evaluation == null) {
            pending.evaluation = WindowPolicy.evaluation(scope, windowStartMs, windowSizeMs,
                    WindowEvaluation.Outcome.INCLUDED, watermark, null);
        }

        // Observation start per scope: zero windows inside the range still count as baseline samples.
        observationStore.putIfAbsent(scope, windowStartMs);
        long observationStartMs = observationStore.get(scope);

        String windowKey = ScopeKey.of(source, eventType, Long.toString(windowStartMs));
        AnomalyWindowState state = windowStore.get(windowKey);
        if (state == null) {
            state = new AnomalyWindowState(windowStartMs, 0, false);
        }
        AnomalyWindowState updated = state.incremented();
        windowStore.put(windowKey, updated);

        // A window can only be used as a baseline sample once the watermark has closed it.
        boolean historyComplete = watermarkBefore != null && watermarkBefore >= windowStartMs;
        long baselineStartMs = windowStartMs - (long) settings.anomalyBaselineWindows() * windowSizeMs;
        if (!historyComplete) {
            recordDetail(pending, "WARMING_UP");
            context.forward(record);
            return;
        }
        if (baselineStartMs < observationStartMs) {
            recordDetail(pending, "WARMING_UP");
            context.forward(record);
            return;
        }

        double baselineSum = 0;
        int samples = 0;
        for (int i = 1; i <= settings.anomalyBaselineWindows(); i++) {
            long sampleStartMs = windowStartMs - (long) i * windowSizeMs;
            AnomalyWindowState sample = windowStore.get(
                    ScopeKey.of(source, eventType, Long.toString(sampleStartMs)));
            baselineSum += sample == null ? 0 : sample.count();
            samples++;
        }
        double baseline = samples == 0 ? 0 : baselineSum / samples;
        if (baseline <= 0) {
            recordDetail(pending, "BASELINE_ZERO");
            context.forward(record);
            return;
        }

        double ratio = updated.count() / baseline;
        if (!updated.fired()
                && updated.count() >= settings.anomalyMinCurrentCount()
                && ratio > settings.anomalySpikeRatio()) {
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.put("window_start", Instant.ofEpochMilli(windowStartMs).toString());
            evidence.put("window_end", Instant.ofEpochMilli(windowStartMs + windowSizeMs).toString());
            evidence.put("baseline", baseline);
            evidence.put("current_value", updated.count());
            evidence.put("ratio", ratio);
            evidence.put("threshold", settings.anomalySpikeRatio());
            evidence.put("history_windows", samples);
            evidence.put("rule_version", RuleVersions.RULES_VERSION);
            pending.alerts.add(new ProcessedEvent.ProcessedAlert(
                    AlertType.ANOMALY_SPIKE, Severity.WARN, source, eventType, null,
                    "Anomaly spike in " + eventType + " window ("
                            + updated.count() + " vs baseline " + String.format(Locale.ROOT, "%.2f", baseline) + ")",
                    evidence));
            windowStore.put(windowKey, updated.firedOnce());
        }
        context.forward(record);
    }

    private void recordDetail(PendingEvent pending, String detail) {
        if (pending.evaluation != null && pending.evaluation.detail() == null) {
            pending.evaluation = new WindowEvaluation(pending.evaluation.scope(),
                    pending.evaluation.windowStart(), pending.evaluation.windowEnd(),
                    pending.evaluation.outcome(), pending.evaluation.watermark(), detail);
        }
    }

    /**
     * Drops window state older than the retention relative to the scope watermark, so a
     * far-future record cannot evict a window that is still inside its grace period.
     */
    private void evictExpired(long ignoredStreamTimeMs) {
        try (KeyValueIterator<String, AnomalyWindowState> iterator = windowStore.all()) {
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Long watermark = watermarkStore.get(ScopeKey.scopeOf(entry.key));
                if (watermark != null
                        && entry.value.windowStart() < watermark - settings.metricsStateRetention().toMillis()) {
                    windowStore.delete(entry.key);
                }
            }
        }
    }

    @Override
    public void close() {}
}