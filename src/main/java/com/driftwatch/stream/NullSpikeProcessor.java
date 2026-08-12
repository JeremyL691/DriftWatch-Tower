package com.driftwatch.stream;

import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.driftwatch.quality.schema.SchemaBaselineProvider;
import com.driftwatch.quality.schema.SchemaInferrer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.util.Map;

/**
 * Windowed null-rate detector. Mirrors {@code NullSpikeDetector}: per (source,eventType,fieldPath)
 * it tracks total/null counts in the current 1-minute window and fires when the rate crosses the
 * threshold after {@code minSamples}, comparing the rate before/after this event's increment.
 */
final class NullSpikeProcessor implements Processor<String, PendingEvent, String, PendingEvent> {

    private final ObjectMapper objectMapper;
    private final SchemaBaselineProvider baselineProvider;
    private final TopologySettings settings;
    private ProcessorContext<String, PendingEvent> context;
    private KeyValueStore<String, NullWindowState> store;

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
        this.store = context.getStateStore(QualityStreamsTopology.NULL_SPIKE_STORE);
    }

    @Override
    public void process(Record<String, PendingEvent> record) {
        PendingEvent p = record.value();
        String eventType = p.event.eventType();
        Map<String, String> expectedFields = baselineProvider.activeLeafFieldTypes(eventType);
        if (expectedFields.isEmpty()) {
            context.forward(record);
            return;
        }

        long windowSizeMs = settings.metricsWindowSize().toMillis();
        long windowStartMs = floorWindow(p.event.eventTimestamp().toEpochMilli(), windowSizeMs);
        Map<String, String> observedFields = SchemaInferrer.infer(objectMapper.valueToTree(p.event.payload()));

        for (String field : expectedFields.keySet()) {
            String key = p.event.source() + "|" + eventType + "|" + field;
            NullWindowState state = store.get(key);
            if (state == null || state.windowStart() != windowStartMs) {
                state = new NullWindowState(windowStartMs, 0, 0);
            }

            boolean nullish = !observedFields.containsKey(field) || "NULL".equals(observedFields.get(field));
            double previousRate = state.total() <= 0 ? 0 : state.nulls() / state.total();
            NullWindowState updated = new NullWindowState(windowStartMs, state.total() + 1, state.nulls() + (nullish ? 1 : 0));
            store.put(key, updated);
            double currentRate = updated.nulls() / updated.total();

            if (updated.total() >= settings.nullSpikeMinSamples()
                    && previousRate <= settings.nullSpikeThreshold()
                    && currentRate > settings.nullSpikeThreshold()) {
                ObjectNode evidence = objectMapper.createObjectNode();
                evidence.put("field_path", field);
                evidence.put("window_start", Instant.ofEpochMilli(windowStartMs).toString());
                evidence.put("window_end", Instant.ofEpochMilli(windowStartMs + windowSizeMs).toString());
                evidence.put("null_count", Math.round(updated.nulls()));
                evidence.put("total_count", Math.round(updated.total()));
                evidence.put("null_rate", currentRate);
                evidence.put("threshold", settings.nullSpikeThreshold());
                p.alerts.add(new ProcessedEvent.ProcessedAlert(
                        AlertType.NULL_SPIKE, Severity.WARN, p.event.source(), eventType, field,
                        "Null spike for " + field + " in " + eventType
                                + " (" + Math.round(updated.nulls()) + "/" + Math.round(updated.total()) + ")",
                        evidence));
            }
        }
        context.forward(record);
    }

    private static long floorWindow(long epochMs, long windowSizeMs) {
        return epochMs - Math.floorMod(epochMs, windowSizeMs);
    }

    @Override
    public void close() {}
}
