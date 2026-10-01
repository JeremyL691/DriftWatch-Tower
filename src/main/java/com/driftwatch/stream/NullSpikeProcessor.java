package com.driftwatch.stream;

import com.driftwatch.event.RawEnvelope;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.RuleVersions;
import com.driftwatch.quality.ScopeKey;
import com.driftwatch.quality.Severity;
import com.driftwatch.quality.schema.SchemaBaselineProvider;
import com.driftwatch.quality.schema.SchemaInferrer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.util.Map;

/**
 * Windowed null-rate detector (execution guide, sections 5.1-5.3).
 *
 * <p>Each (scope, field, window) keeps its own totals, so an out-of-order event from an older
 * window can never erase the current window. The threshold is evaluated against the window
 * state and fires exactly once per window and field ({@code fired}); it is not a
 * previous-versus-current transition, which is what made the audited implementation miss a
 * window whose rate was already above the threshold.
 *
 * <p>windowEnd + grace behind the scope watermark -> EXPIRED (evidence kept, window untouched);
 * beyond the future tolerance -> FUTURE (watermark untouched). A scope without an active
 * baseline is marked BASELINE_PENDING: only baseline-dependent checks are skipped, and the
 * event is never reported as fully checked.
 */
final class NullSpikeProcessor implements Processor<String, PendingEvent, String, PendingEvent> {

    private final ObjectMapper objectMapper;
    private final SchemaBaselineProvider baselineProvider;
    private final TopologySettings settings;
    private ProcessorContext<String, PendingEvent> context;
    private KeyValueStore<String, NullWindowState> windowStore;
    private KeyValueStore<String, Long> watermarkStore;

    NullSpikeProcessor(ObjectMapper objectMapper,
                       SchemaBaselineProvider baselineProvider,
                       TopologySettings settings) {
        this.objectMapper = objectMapper;
        this.baselineProvider = baselineProvider;
        this.settings = settings;
    }

    @Override
    public void init(ProcessorContext<String, PendingEvent> context) {
        this.context = context;
        this.windowStore = context.getStateStore(QualityStreamsTopology.NULL_WINDOW_STORE);
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
            pending.evaluation = WindowPolicy.evaluation(scope, windowStartMs, windowSizeMs,
                    WindowEvaluation.Outcome.SKIPPED_MODE,
                    watermarkStore.get(scope) == null ? Long.MIN_VALUE : watermarkStore.get(scope),
                    envelope.mode().name());
            context.forward(record);
            return;
        }

        Long watermarkBefore = watermarkStore.get(scope);
        long watermark = watermarkBefore == null ? eventMs : watermarkBefore;
        WindowEvaluation.Outcome outcome = WindowPolicy.classify(
                eventMs, windowSizeMs, settings.metricsGrace().toMillis(),
                settings.metricsFutureTolerance().toMillis(), watermark, envelope.receivedAt());

        if (outcome == WindowEvaluation.Outcome.INCLUDED && watermarkBefore == null) {
            watermarkStore.put(scope, eventMs);
            watermark = eventMs;
        }

        if (outcome != WindowEvaluation.Outcome.INCLUDED) {
            pending.evaluation = WindowPolicy.evaluation(scope, windowStartMs, windowSizeMs,
                    outcome, watermarkBefore == null ? Long.MIN_VALUE : watermarkBefore,
                    outcome == WindowEvaluation.Outcome.EXPIRED ? "WINDOW_CLOSED" : "FUTURE_TIMESTAMP");
            context.forward(record);
            return;
        }
        if (watermarkBefore != null && eventMs > watermarkBefore) {
            watermarkStore.put(scope, eventMs);
            watermark = eventMs;
        }

        Map<String, String> expectedFields = baselineProvider.activeLeafFieldTypes(eventType);
        if (expectedFields.isEmpty()) {
            // Only baseline-dependent checks are skipped; the event is never reported as fully checked.
            pending.baselineStatus = "PENDING";
            if (pending.evaluation == null) {
                pending.evaluation = WindowPolicy.evaluation(scope, windowStartMs, windowSizeMs,
                        WindowEvaluation.Outcome.INCLUDED, watermark, null);
            }
            context.forward(record);
            return;
        }
        pending.baselineStatus = "APPLIED";
        if (pending.evaluation == null) {
            pending.evaluation = WindowPolicy.evaluation(scope, windowStartMs, windowSizeMs,
                    WindowEvaluation.Outcome.INCLUDED, watermark, null);
        }

        Map<String, String> observedFields = SchemaInferrer.infer(objectMapper.valueToTree(envelope.event().payload()));
        for (String field : expectedFields.keySet()) {
            String key = ScopeKey.of(source, eventType, field, Long.toString(windowStartMs));
            NullWindowState state = windowStore.get(key);
            if (state == null) {
                state = new NullWindowState(windowStartMs, 0, 0, false);
            }
            boolean nullish = !observedFields.containsKey(field) || "NULL".equals(observedFields.get(field));
            NullWindowState updated = state.incremented(nullish);
            windowStore.put(key, updated);

            if (!updated.fired()
                    && updated.total() >= settings.nullSpikeMinSamples()
                    && updated.nullRate() > settings.nullSpikeThreshold()) {
                ObjectNode evidence = objectMapper.createObjectNode();
                evidence.put("field_path", field);
                evidence.put("window_start", Instant.ofEpochMilli(windowStartMs).toString());
                evidence.put("window_end", Instant.ofEpochMilli(windowStartMs + windowSizeMs).toString());
                evidence.put("null_count", Math.round(updated.nulls()));
                evidence.put("total_count", Math.round(updated.total()));
                evidence.put("null_rate", updated.nullRate());
                evidence.put("threshold", settings.nullSpikeThreshold());
                evidence.put("rule_version", RuleVersions.RULES_VERSION);
                pending.alerts.add(new ProcessedEvent.ProcessedAlert(
                        AlertType.NULL_SPIKE, Severity.WARN, source, eventType, field,
                        "Null spike for " + field + " in " + eventType
                                + " (" + Math.round(updated.nulls()) + "/" + Math.round(updated.total()) + ")",
                        evidence));
                windowStore.put(key, updated.firedOnce());
            }
        }
        context.forward(record);
    }

    /**
     * Drops window state older than the retention relative to the scope watermark. Using the
     * watermark (not raw stream time) means a far-future record cannot evict a window that is
     * still inside its grace period.
     */
    private void evictExpired(long ignoredStreamTimeMs) {
        try (KeyValueIterator<String, NullWindowState> iterator = windowStore.all()) {
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