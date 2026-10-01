package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.PayloadHasher;
import com.driftwatch.event.RawEnvelope;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.DetectionContext;
import com.driftwatch.quality.DraftAlert;
import com.driftwatch.quality.FieldFormatDetector;
import com.driftwatch.quality.FieldRangeDetector;
import com.driftwatch.quality.LateEventDetector;
import com.driftwatch.quality.QualityDetector;
import com.driftwatch.quality.RuleVersions;
import com.driftwatch.quality.ScopeKey;
import com.driftwatch.quality.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds the Kafka Streams quality topology:
 * {@code raw-events} → ADAPTER → ENRICH → IDENTITY → (detector processors) → FINALIZE →
 * {@code quality-events}. The {@link QualityEventSink} persists the output.
 *
 * <p>Scopes are encoded with {@link ScopeKey} (canonical JSON arrays), so state-store keys and
 * partition keys cannot collide through separator characters. Delivery identity is checked
 * before any detector runs: a redelivered envelope is marked and skipped so business counters
 * stay stable, and a reused ingestion id with different content is marked as a conflict instead
 * of silently polluting detection.
 */
@Component
public class QualityStreamsTopology {

    static final String NULL_WINDOW_STORE = "null-window-store";
    static final String ANOMALY_WINDOW_STORE = "anomaly-window-store";
    static final String ANOMALY_SCOPE_STORE = "anomaly-scope-store";
    static final String SCOPE_WATERMARK_STORE = "scope-watermark-store";
    static final String ENVELOPE_DIGEST_STORE = "envelope-digest-store";
    static final String SCHEMA_BASELINE_STORE = "schema-baseline-store";
    static final String DUPLICATE_EVENT_ID_STORE = "duplicate-event-id-store";
    static final String DUPLICATE_PAYLOAD_STORE = "duplicate-payload-store";

    private final StreamSerdes serdes;
    private final PayloadHasher hasher;
    private final LateEventDetector lateEventDetector;
    private final FieldRangeDetector fieldRangeDetector;
    private final FieldFormatDetector fieldFormatDetector;
    private final ObjectMapper objectMapper;
    private final TopologySettings settings;

    public QualityStreamsTopology(StreamSerdes serdes,
                                  PayloadHasher hasher,
                                  LateEventDetector lateEventDetector,
                                  FieldRangeDetector fieldRangeDetector,
                                  FieldFormatDetector fieldFormatDetector,
                                  ObjectMapper objectMapper,
                                  TopologySettings settings) {
        this.serdes = serdes;
        this.hasher = hasher;
        this.lateEventDetector = lateEventDetector;
        this.fieldRangeDetector = fieldRangeDetector;
        this.fieldFormatDetector = fieldFormatDetector;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    public KStream<String, ProcessedEvent> apply(StreamsBuilder builder) {
        KStream<String, RawEnvelope> envelopes = builder
                .stream(KafkaTopics.RAW_EVENTS, Consumed.with(Serdes.String(), serdes.dataEventSerde()))
                .process(EnvelopeAdapter::new, Named.as("adapter"));
        return buildPipeline(builder, envelopes);
    }

    /**
     * Pipeline over an existing envelope stream. P3.1 feeds this from {@code raw-events-v1};
     * tests feed it directly so a specific ingestion id can be redelivered on purpose.
     */
    KStream<String, ProcessedEvent> buildPipeline(StreamsBuilder builder, KStream<String, RawEnvelope> envelopes) {
        long duplicateWindowMs = settings.duplicatePayloadWindow().toMillis();
        // Active baselines arrive from the compacted topic; the topology never queries JPA.
        builder.globalTable(KafkaTopics.SCHEMA_BASELINES,
                Consumed.with(Serdes.String(), serdes.baselineMessageSerde()),
                Materialized.as(SCHEMA_BASELINE_STORE));
        builder.addStateStore(windowStore(DUPLICATE_EVENT_ID_STORE, duplicateWindowMs, Serdes.Long()));
        builder.addStateStore(windowStore(DUPLICATE_PAYLOAD_STORE, duplicateWindowMs, Serdes.String()));
        builder.addStateStore(jsonStore(NULL_WINDOW_STORE, NullWindowState.class));
        builder.addStateStore(jsonStore(ANOMALY_WINDOW_STORE, AnomalyWindowState.class));
        builder.addStateStore(keyValueStore(ANOMALY_SCOPE_STORE, Serdes.Long()));
        builder.addStateStore(keyValueStore(SCOPE_WATERMARK_STORE, Serdes.Long()));
        builder.addStateStore(keyValueStore(ENVELOPE_DIGEST_STORE, Serdes.String()));

        KStream<String, ProcessedEvent> processed = envelopes
                .process(() -> new EnrichmentProcessor(hasher), Named.as("enrich"))
                .process(() -> new DeliveryIdentityProcessor(objectMapper), Named.as("identity"),
                        ENVELOPE_DIGEST_STORE)
                .process(() -> new DetectorProcessor(List.of(
                                lateEventDetector, fieldRangeDetector, fieldFormatDetector)),
                        Named.as("stateless"))
                .process(() -> new DuplicateProcessor(objectMapper, duplicateWindowMs),
                        Named.as("duplicate"), DUPLICATE_EVENT_ID_STORE, DUPLICATE_PAYLOAD_STORE)
                .process(() -> new NullSpikeProcessor(objectMapper, settings),
                        Named.as("null-spike"), NULL_WINDOW_STORE, SCOPE_WATERMARK_STORE)
                .process(() -> new AnomalySpikeProcessor(objectMapper, settings),
                        Named.as("anomaly"), ANOMALY_WINDOW_STORE, ANOMALY_SCOPE_STORE, SCOPE_WATERMARK_STORE)
                .process(FinalizeProcessor::new, Named.as("finalize"));

        processed.to(KafkaTopics.QUALITY_EVENTS,
                Produced.with(Serdes.String(), serdes.processedEventSerde()));
        return processed;
    }

    private static StoreBuilder<WindowStore<String, ?>> windowStore(String name, long windowMs, Serde<?> valueSerde) {
        @SuppressWarnings("unchecked")
        Serde<Object> serde = (Serde<Object>) valueSerde;
        return (StoreBuilder) Stores.windowStoreBuilder(
                Stores.persistentWindowStore(name, Duration.ofMillis(windowMs + 60_000),
                        Duration.ofMillis(windowMs), false),
                Serdes.String(), serde);
    }

    private static StoreBuilder<KeyValueStore<String, ?>> keyValueStore(String name, Serde<?> valueSerde) {
        @SuppressWarnings("unchecked")
        Serde<Object> serde = (Serde<Object>) valueSerde;
        return (StoreBuilder) Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(name), Serdes.String(), serde);
    }

    private <T> StoreBuilder<KeyValueStore<String, T>> jsonStore(String name, Class<T> type) {
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(type, objectMapper);
        deserializer.addTrustedPackages("com.driftwatch.stream");
        Serde<T> serde = Serdes.serdeFrom(new JsonSerializer<T>(objectMapper).noTypeInfo(), deserializer);
        return Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(name), Serdes.String(), serde);
    }

    /**
     * Temporary bridge while {@code raw-events} still carries bare {@link DataEvent} values: the
     * envelope is created here. P3.1 moves envelope creation into the ingest API and the new
     * {@code raw-events-v1} topic.
     */
    static class EnvelopeAdapter implements Processor<String, DataEvent, String, RawEnvelope> {
        private ProcessorContext<String, RawEnvelope> context;

        @Override
        public void init(ProcessorContext<String, RawEnvelope> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, DataEvent> record) {
            RawEnvelope envelope = RawEnvelope.forRest(record.value(), Instant.now());
            context.forward(record.withValue(envelope));
        }

        @Override
        public void close() {}
    }

    /** Attaches the payload hash and normalises stream time to the event timestamp. */
    static class EnrichmentProcessor implements Processor<String, RawEnvelope, String, PendingEvent> {
        private final PayloadHasher hasher;
        private ProcessorContext<String, PendingEvent> context;

        EnrichmentProcessor(PayloadHasher hasher) {
            this.hasher = hasher;
        }

        @Override
        public void init(ProcessorContext<String, PendingEvent> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, RawEnvelope> record) {
            RawEnvelope envelope = record.value();
            PendingEvent pending = new PendingEvent(envelope, hasher.hash(envelope.event().payload()));
            context.forward(record.withValue(pending)
                    .withTimestamp(envelope.event().eventTimestamp().toEpochMilli()));
        }

        @Override
        public void close() {}
    }

    /**
     * Delivery identity guard (execution guide, section 4.1): the first delivery of an
     * ingestion id is recorded with a digest of its envelope; a later delivery with the same
     * digest is a redelivery and is skipped before any detector runs, while the same id with a
     * different digest is marked as a conflict so the sink can send it to the failure path.
     */
    static class DeliveryIdentityProcessor implements Processor<String, PendingEvent, String, PendingEvent> {
        private final ObjectMapper objectMapper;
        private ProcessorContext<String, PendingEvent> context;
        private KeyValueStore<String, String> digestStore;

        DeliveryIdentityProcessor(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void init(ProcessorContext<String, PendingEvent> context) {
            this.context = context;
            this.digestStore = context.getStateStore(ENVELOPE_DIGEST_STORE);
        }

        @Override
        public void process(Record<String, PendingEvent> record) {
            PendingEvent pending = record.value();
            String id = pending.envelope.ingestionId().toString();
            String digest = digest(pending.envelope);
            String seen = digestStore.get(id);
            if (seen == null) {
                digestStore.put(id, digest);
                context.forward(record);
                return;
            }
            String scope = ScopeKey.of(pending.envelope.event().source(),
                    pending.envelope.event().eventType());
            pending.skipDetection = true;
            pending.evaluation = digest.equals(seen)
                    ? WindowEvaluation.notApplicable(scope, WindowEvaluation.Outcome.REDELIVERY,
                            "SAME_INGESTION_ID")
                    : WindowEvaluation.notApplicable(scope, WindowEvaluation.Outcome.CONFLICT,
                            "INGESTION_ID_REUSED_WITH_DIFFERENT_CONTENT");
            context.forward(record);
        }

        private String digest(RawEnvelope envelope) {
            try {
                byte[] canonical = objectMapper.writeValueAsBytes(envelope);
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
            } catch (Exception e) {
                return HexFormat.of().formatHex(envelope.ingestionId().toString().getBytes(StandardCharsets.UTF_8));
            }
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
            if (p.skipDetection) {
                context.forward(record);
                return;
            }
            DetectionContext ctx = new DetectionContext(p.event(), p.payloadHash, p.receivedAt());
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
     * rather than an unbounded database lookup.
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
            this.eventIdStore = context.getStateStore(DUPLICATE_EVENT_ID_STORE);
            this.payloadStore = context.getStateStore(DUPLICATE_PAYLOAD_STORE);
        }

        @Override
        public void process(Record<String, PendingEvent> record) {
            PendingEvent p = record.value();
            if (p.skipDetection) {
                context.forward(record);
                return;
            }
            String eventId = p.event().eventId();
            long nowMs = p.receivedAt().toEpochMilli();
            long fromMs = nowMs - windowMs;
            // Keys are scoped and canonically encoded: the same event_id or payload in a
            // different source/event_type must not be reported as a duplicate.
            String source = p.event().source();
            String eventType = p.event().eventType();
            String eventKey = ScopeKey.of(source, eventType, eventId);
            String payloadKey = ScopeKey.of(source, eventType, p.payloadHash);

            if (seen(eventIdStore, eventKey, fromMs, nowMs)) {
                ObjectNode evidence = objectMapper.createObjectNode();
                evidence.put("duplicate_kind", "REPEATED_EVENT_ID");
                evidence.put("event_id", eventId);
                evidence.put("source", source);
                evidence.put("rule_version", RuleVersions.RULES_VERSION);
                p.alerts.add(new ProcessedEvent.ProcessedAlert(
                        AlertType.DUPLICATE_EVENT, Severity.INFO, source, eventType,
                        null, "Repeated event_id " + eventId, evidence));
            }

            String firstEventId = firstSeenEventId(payloadStore, payloadKey, fromMs, nowMs);
            if (firstEventId != null && !firstEventId.equals(eventId)) {
                ObjectNode evidence = objectMapper.createObjectNode();
                evidence.put("duplicate_kind", "REPEATED_PAYLOAD");
                evidence.put("payload_hash", p.payloadHash);
                evidence.put("window", Duration.ofMillis(windowMs).toString());
                evidence.put("current_event_id", eventId);
                evidence.put("first_event_id", firstEventId);
                evidence.put("rule_version", RuleVersions.RULES_VERSION);
                p.alerts.add(new ProcessedEvent.ProcessedAlert(
                        AlertType.DUPLICATE_EVENT, Severity.INFO, source, eventType,
                        null, "Repeated payload hash within " + Duration.ofMillis(windowMs) + "; first event_id=" + firstEventId,
                        evidence));
            }

            eventIdStore.put(eventKey, nowMs, nowMs);
            payloadStore.put(payloadKey, eventId, nowMs);
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
            PendingEvent p = record.value();
            if (p.evaluation == null) {
                p.evaluation = WindowEvaluation.notApplicable(
                        ScopeKey.of(p.event().source(), p.event().eventType()),
                        WindowEvaluation.Outcome.INCLUDED, null);
            }
            context.forward(record.withValue(p.toProcessed()));
        }

        @Override
        public void close() {}
    }
}