package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.PayloadHasher;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.DetectionContext;
import com.driftwatch.quality.DraftAlert;
import com.driftwatch.quality.FieldFormatDetector;
import com.driftwatch.quality.FieldRangeDetector;
import com.driftwatch.quality.LateEventDetector;
import com.driftwatch.quality.QualityDetector;
import com.driftwatch.quality.SchemaDriftDetector;
import com.driftwatch.quality.Severity;
import com.driftwatch.quality.schema.SchemaBaselineProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Builds the Kafka Streams quality topology: {@code raw-events} → ENRICH → (detector processors)
 * → FINALIZE → {@code quality-events}. The {@link QualityEventSink} persists the output.
 *
 * <p>State-store keys (duplicate window, null/anomaly windows) are NOT partition-aligned with the
 * {@code source|eventType} partitioning of {@code raw-events}. Fine for the single-node demo; a
 * multi-node deployment would need {@code selectKey().repartition()} first.
 */
@Component
public class QualityStreamsTopology {

    static final String NULL_SPIKE_STORE = "null-spike-store";
    static final String ANOMALY_STORE = "anomaly-count-store";

    private final StreamSerdes serdes;
    private final PayloadHasher hasher;
    private final LateEventDetector lateEventDetector;
    private final FieldRangeDetector fieldRangeDetector;
    private final FieldFormatDetector fieldFormatDetector;
    private final SchemaDriftDetector schemaDriftDetector;
    private final SchemaBaselineProvider baselineProvider;
    private final ObjectMapper objectMapper;
    private final TopologySettings settings;

    public QualityStreamsTopology(StreamSerdes serdes,
                                  PayloadHasher hasher,
                                  LateEventDetector lateEventDetector,
                                  FieldRangeDetector fieldRangeDetector,
                                  FieldFormatDetector fieldFormatDetector,
                                  SchemaDriftDetector schemaDriftDetector,
                                  SchemaBaselineProvider baselineProvider,
                                  ObjectMapper objectMapper,
                                  TopologySettings settings) {
        this.serdes = serdes;
        this.hasher = hasher;
        this.lateEventDetector = lateEventDetector;
        this.fieldRangeDetector = fieldRangeDetector;
        this.fieldFormatDetector = fieldFormatDetector;
        this.schemaDriftDetector = schemaDriftDetector;
        this.baselineProvider = baselineProvider;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    public KStream<String, ProcessedEvent> apply(StreamsBuilder builder) {
        long duplicateWindowMs = settings.duplicatePayloadWindow().toMillis();
        StoreBuilder<WindowStore<String, Long>> eventIdStore = Stores.windowStoreBuilder(
                Stores.persistentWindowStore("duplicate-event-id-store",
                        Duration.ofMillis(duplicateWindowMs + 60_000), Duration.ofMillis(duplicateWindowMs), false),
                Serdes.String(), Serdes.Long());
        StoreBuilder<WindowStore<String, String>> payloadStore = Stores.windowStoreBuilder(
                Stores.persistentWindowStore("duplicate-payload-store",
                        Duration.ofMillis(duplicateWindowMs + 60_000), Duration.ofMillis(duplicateWindowMs), false),
                Serdes.String(), Serdes.String());
        builder.addStateStore(eventIdStore);
        builder.addStateStore(payloadStore);

        long windowSizeMs = settings.metricsWindowSize().toMillis();
        JsonDeserializer<NullWindowState> nullStateDeser = new JsonDeserializer<>(NullWindowState.class, objectMapper);
        nullStateDeser.addTrustedPackages("com.driftwatch.stream");
        Serde<NullWindowState> nullStateSerde = Serdes.serdeFrom(
                new JsonSerializer<NullWindowState>(objectMapper).noTypeInfo(), nullStateDeser);
        StoreBuilder<KeyValueStore<String, NullWindowState>> nullSpikeStore = Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(NULL_SPIKE_STORE), Serdes.String(), nullStateSerde);
        builder.addStateStore(nullSpikeStore);

        StoreBuilder<WindowStore<String, Long>> anomalyStore = Stores.windowStoreBuilder(
                Stores.persistentWindowStore(ANOMALY_STORE,
                        Duration.ofMillis(windowSizeMs * (settings.anomalyBaselineWindows() + 2)),
                        Duration.ofMillis(windowSizeMs), false),
                Serdes.String(), Serdes.Long());
        builder.addStateStore(anomalyStore);

        KStream<String, DataEvent> raw = builder.stream(KafkaTopics.RAW_EVENTS,
                Consumed.with(Serdes.String(), serdes.dataEventSerde()));

        KStream<String, ProcessedEvent> processed = raw
                .process(() -> new EnrichProcessor(hasher), Named.as("enrich"))
                .process(() -> new DetectorProcessor(List.of(
                                lateEventDetector, fieldRangeDetector, fieldFormatDetector)),
                        Named.as("stateless"))
                .process(() -> new DuplicateProcessor(objectMapper, duplicateWindowMs),
                        Named.as("duplicate"), "duplicate-event-id-store", "duplicate-payload-store")
                .process(() -> new DetectorProcessor(List.of(schemaDriftDetector)),
                        Named.as("schema"))
                .process(() -> new NullSpikeProcessor(objectMapper, baselineProvider, settings),
                        Named.as("null-spike"), NULL_SPIKE_STORE)
                .process(() -> new AnomalySpikeProcessor(objectMapper, settings),
                        Named.as("anomaly"), ANOMALY_STORE)
                .process(FinalizeProcessor::new, Named.as("finalize"));

        processed.to(KafkaTopics.QUALITY_EVENTS,
                Produced.with(Serdes.String(), serdes.processedEventSerde()));
        return processed;
    }

    /** Attaches payloadHash + receivedAt and rewrites stream time to the event timestamp. */
    static class EnrichProcessor implements Processor<String, DataEvent, String, PendingEvent> {
        private final PayloadHasher hasher;
        private ProcessorContext<String, PendingEvent> context;

        EnrichProcessor(PayloadHasher hasher) {
            this.hasher = hasher;
        }

        @Override
        public void init(ProcessorContext<String, PendingEvent> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, DataEvent> record) {
            DataEvent event = record.value();
            PendingEvent pending = new PendingEvent(event, hasher.hash(event.payload()), Instant.now());
            // Windowed detectors are event-time based; rewrite stream time to event_timestamp.
            context.forward(record.withValue(pending).withTimestamp(event.eventTimestamp().toEpochMilli()));
        }

        @Override
        public void close() {}
    }

    /** Runs a fixed list of detector beans (config-only or DB-backed registry) per event. */
    static class DetectorProcessor implements Processor<String, PendingEvent, String, PendingEvent> {
        private final List<QualityDetector> detectors;
        private ProcessorContext<String, PendingEvent> context;

        DetectorProcessor(List<QualityDetector> detectors) {
            this.detectors = detectors;
        }

        @Override
        public void init(ProcessorContext<String, PendingEvent> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, PendingEvent> record) {
            PendingEvent p = record.value();
            DetectionContext ctx = new DetectionContext(p.event, p.payloadHash, p.receivedAt);
            for (QualityDetector detector : detectors) {
                addAll(p, detector.detect(ctx));
            }
            context.forward(record);
        }

        private static void addAll(PendingEvent p, List<DraftAlert> drafts) {
            for (DraftAlert d : drafts) {
                p.alerts.add(new ProcessedEvent.ProcessedAlert(
                        d.type(), d.severity(), d.source(), d.eventType(), d.fieldPath(), d.message(), d.evidence()));
            }
        }

        @Override
        public void close() {}
    }

    /**
     * Flags repeated event_id and repeated payload hashes within the configured window, using
     * time-windowed state stores instead of the raw_events table. Retention is the dedupe window
     * (was "forever" in the DB version) — see plan Flaw #4 note.
     */
    static class DuplicateProcessor implements Processor<String, PendingEvent, String, PendingEvent> {
        private final ObjectMapper objectMapper;
        private final long windowMs;
        private ProcessorContext<String, PendingEvent> context;
        private WindowStore<String, Long> eventIdStore;
        private WindowStore<String, String> payloadStore;

        DuplicateProcessor(ObjectMapper objectMapper, long windowMs) {
            this.objectMapper = objectMapper;
            this.windowMs = windowMs;
        }

        @Override
        public void init(ProcessorContext<String, PendingEvent> context) {
            this.context = context;
            this.eventIdStore = context.getStateStore("duplicate-event-id-store");
            this.payloadStore = context.getStateStore("duplicate-payload-store");
        }

        @Override
        public void process(Record<String, PendingEvent> record) {
            PendingEvent p = record.value();
            String eventId = p.event.eventId();
            long nowMs = p.receivedAt.toEpochMilli();
            long fromMs = nowMs - windowMs;

            if (seen(eventIdStore, eventId, fromMs, nowMs)) {
                ObjectNode evidence = objectMapper.createObjectNode();
                evidence.put("duplicate_kind", "REPEATED_EVENT_ID");
                evidence.put("event_id", eventId);
                evidence.put("source", p.event.source());
                p.alerts.add(new ProcessedEvent.ProcessedAlert(
                        AlertType.DUPLICATE_EVENT, Severity.INFO, p.event.source(), p.event.eventType(),
                        null, "Repeated event_id " + eventId, evidence));
            }

            String firstEventId = firstSeenEventId(payloadStore, p.payloadHash, fromMs, nowMs);
            if (firstEventId != null && !firstEventId.equals(eventId)) {
                ObjectNode evidence = objectMapper.createObjectNode();
                evidence.put("duplicate_kind", "REPEATED_PAYLOAD");
                evidence.put("payload_hash", p.payloadHash);
                evidence.put("window", Duration.ofMillis(windowMs).toString());
                evidence.put("current_event_id", eventId);
                evidence.put("first_event_id", firstEventId);
                p.alerts.add(new ProcessedEvent.ProcessedAlert(
                        AlertType.DUPLICATE_EVENT, Severity.INFO, p.event.source(), p.event.eventType(),
                        null, "Repeated payload hash within " + Duration.ofMillis(windowMs) + "; first event_id=" + firstEventId,
                        evidence));
            }

            eventIdStore.put(eventId, nowMs, nowMs);
            payloadStore.put(p.payloadHash, eventId, nowMs);
            context.forward(record);
        }

        private static boolean seen(WindowStore<String, Long> store, String key, long from, long to) {
            try (WindowStoreIterator<Long> it = store.fetch(key, from, to)) {
                return it.hasNext();
            }
        }

        private static String firstSeenEventId(WindowStore<String, String> store, String key, long from, long to) {
            try (WindowStoreIterator<String> it = store.fetch(key, from, to)) {
                return it.hasNext() ? it.next().value : null;
            }
        }

        @Override
        public void close() {}
    }

    /** Materializes the accumulated {@link PendingEvent} into the topic value. */
    static class FinalizeProcessor implements Processor<String, PendingEvent, String, ProcessedEvent> {
        private ProcessorContext<String, ProcessedEvent> context;

        @Override
        public void init(ProcessorContext<String, ProcessedEvent> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, PendingEvent> record) {
            context.forward(record.withValue(record.value().toProcessed()));
        }

        @Override
        public void close() {}
    }
}
