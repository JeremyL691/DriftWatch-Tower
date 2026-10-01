package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.PayloadHasher;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.FieldFormatDetector;
import com.driftwatch.quality.FieldRangeDetector;
import com.driftwatch.quality.LateEventDetector;
import com.driftwatch.quality.schema.BaselineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class QualityStreamsTopologyTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final StreamSerdes serdes = new StreamSerdes(objectMapper);
    private final PayloadHasher hasher = new PayloadHasher();

    private LateEventDetector quietLate() {
        return new LateEventDetector(objectMapper, Duration.ofMinutes(5));
    }

    private FieldRangeDetector emptyRange() {
        return new FieldRangeDetector(objectMapper, Map.of());
    }

    private FieldFormatDetector emptyFormat() {
        return new FieldFormatDetector(objectMapper, "");
    }

    private static final Map<String, Map<String, String>> NO_BASELINE = Map.of();
    private static final Map<String, Map<String, String>> ASK_NUMBER_BASELINE =
            Map.of("demo_null_event", Map.of("ask", "NUMBER"));

    private TopologySettings settings() {
        return new TopologySettings(Duration.ofMinutes(5), Duration.ofMinutes(1), 0.6, 3, 2, 2, 3.0, 5);
    }

    private Driver driver() {
        return driver(quietLate(), emptyRange(), emptyFormat(), NO_BASELINE, settings());
    }

    private Driver driver(LateEventDetector late,
                          FieldRangeDetector range,
                          FieldFormatDetector format,
                          Map<String, Map<String, String>> baselines,
                          TopologySettings topologySettings) {
        QualityStreamsTopology topology = new QualityStreamsTopology(
                serdes, hasher, late, range, format, objectMapper, topologySettings);
        return new Driver(topology, baselines);
    }

    @Test
    void roundTripEnrichesAndSinksProcessedEvent() {
        try (Driver driver = driver()) {
            DataEvent event = new DataEvent("evt-1", "demo-api", "market_tick", Instant.now(), Map.of("bid", 1.0));
            driver.pipe(event);

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(1);
            ProcessedEvent p = results.get(0).value;
            assertThat(p.event().eventId()).isEqualTo("evt-1");
            assertThat(p.payloadHash()).isEqualTo(hasher.hash(event.payload()));
            assertThat(p.receivedAt()).isNotNull();
            assertThat(p.qualityStatus()).isEqualTo("OK");
            assertThat(p.alerts()).isEmpty();
        }
    }

    @Test
    void repeatedEventIdProducesDuplicateAlert() {
        try (Driver driver = driver()) {
            DataEvent event = new DataEvent("evt-dup", "demo-api", "market_tick", Instant.now(), Map.of("bid", 1.0));
            driver.pipe(event);
            driver.pipe(event);

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(2);
            assertThat(results.get(1).value.alerts()).anySatisfy(a -> {
                assertThat(a.type()).isEqualTo(AlertType.DUPLICATE_EVENT);
                assertThat(a.evidence().get("duplicate_kind").asText()).isEqualTo("REPEATED_EVENT_ID");
            });
        }
    }

    @Test
    void repeatedPayloadDifferentEventIdProducesDuplicateAlert() {
        try (Driver driver = driver()) {
            Instant ts = Instant.now();
            Map<String, Object> payload = Map.of("bid", 1.0);
            driver.pipe(new DataEvent("evt-a", "demo-api", "market_tick", ts, payload));
            driver.pipe(new DataEvent("evt-b", "demo-api", "market_tick", ts.plusSeconds(1), payload));

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(2);
            ProcessedEvent second = results.get(1).value;
            assertThat(second.alerts()).anySatisfy(a -> {
                assertThat(a.type()).isEqualTo(AlertType.DUPLICATE_EVENT);
                assertThat(a.evidence().get("duplicate_kind").asText()).isEqualTo("REPEATED_PAYLOAD");
                assertThat(a.evidence().get("first_event_id").asText()).isEqualTo("evt-a");
            });
        }
    }

    @Test
    void lateEventProducesLateAlert() {
        LateEventDetector late = new LateEventDetector(objectMapper, Duration.ofMinutes(5));
        try (Driver driver = driver(late, emptyRange(), emptyFormat(), NO_BASELINE, settings())) {
            DataEvent event = new DataEvent("evt-late", "demo-api", "market_tick",
                    Instant.now().minusSeconds(600), Map.of("bid", 1.0));
            driver.pipe(event);

            ProcessedEvent p = driver.output().get(0).value;
            assertThat(p.qualityStatus()).isEqualTo("LATE");
            assertThat(p.alerts()).anySatisfy(a -> {
                assertThat(a.type()).isEqualTo(AlertType.LATE_EVENT);
                assertThat(a.evidence().get("lateness_seconds").asLong()).isGreaterThanOrEqualTo(600);
            });
        }
    }

    @Test
    void fieldRangeAndFormatProduceAlerts() {
        FieldRangeDetector range = new FieldRangeDetector(objectMapper, Map.of("price", range(0, 100)));
        FieldFormatDetector format = new FieldFormatDetector(objectMapper, "code=^SKU-[0-9]{6}$");
        try (Driver driver = driver(quietLate(), range, format, NO_BASELINE, settings())) {
            DataEvent event = new DataEvent("evt-f", "demo-api", "demo_quality_event", Instant.now(),
                    Map.of("price", 150.0, "code", "SKU-12"));
            driver.pipe(event);

            ProcessedEvent p = driver.output().get(0).value;
            assertThat(p.qualityStatus()).isEqualTo("FLAGGED");
            assertThat(p.alerts()).extracting(a -> a.type())
                    .containsExactlyInAnyOrder(AlertType.FIELD_OUT_OF_RANGE, AlertType.FIELD_FORMAT_MISMATCH);
        }
    }

    @Test
    void nullSpikeProducesAlert() {
        try (Driver driver = driver(quietLate(), emptyRange(), emptyFormat(), ASK_NUMBER_BASELINE, settings())) {
            Instant ts = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("evt-n0", "demo-api", "demo_null_event", ts, Map.of("bid", 1.0, "ask", 2.0)));
            driver.pipe(new DataEvent("evt-n1", "demo-api", "demo_null_event", ts.plusSeconds(1), Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("evt-n2", "demo-api", "demo_null_event", ts.plusSeconds(2), Map.of("bid", 1.0)));

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(3);
            ProcessedEvent third = results.get(2).value;
            assertThat(third.alerts()).anySatisfy(a -> {
                assertThat(a.type()).isEqualTo(AlertType.NULL_SPIKE);
                assertThat(a.evidence().get("null_count").asLong()).isEqualTo(2);
                assertThat(a.evidence().get("total_count").asLong()).isEqualTo(3);
            });
        }
    }

    @Test
    void anomalySpikeProducesAlert() {
        try (Driver driver = driver()) {
            Instant base = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            String source = "demo-anomaly-source";
            driver.pipe(new DataEvent("a0", source, "demo_anomaly_event", base.minusSeconds(120), Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("a1", source, "demo_anomaly_event", base.minusSeconds(119), Map.of("bid", 2.0)));
            driver.pipe(new DataEvent("b0", source, "demo_anomaly_event", base.minusSeconds(60), Map.of("bid", 3.0)));
            driver.pipe(new DataEvent("b1", source, "demo_anomaly_event", base.minusSeconds(59), Map.of("bid", 4.0)));
            for (int i = 0; i < 8; i++) {
                driver.pipe(new DataEvent("c" + i, source, "demo_anomaly_event", base.plusSeconds(i), Map.of("bid", 100.0 + i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(12);
            // Fire happens on the 6th burst event (count 7 → ratio 3.5 > 3.0).
            ProcessedEvent burstEvent = results.get(10).value;
            assertThat(burstEvent.alerts()).anySatisfy(a -> {
                assertThat(a.type()).isEqualTo(AlertType.ANOMALY_SPIKE);
                assertThat(a.evidence().get("baseline").asDouble()).isEqualTo(2.0);
            });
        }
    }

    private static FieldRangeDetector.Bounds range(double min, double max) {
        FieldRangeDetector.Bounds b = new FieldRangeDetector.Bounds();
        b.setMin(min);
        b.setMax(max);
        return b;
    }

    /** Wraps a TopologyTestDriver wired to a fully-built topology. */
    private class Driver implements AutoCloseable {
        private final TopologyTestDriver driver;
        private final TestInputTopic<String, com.driftwatch.event.RawEnvelope> input;
        private final TestOutputTopic<String, ProcessedEvent> output;

        Driver(QualityStreamsTopology topology, Map<String, Map<String, String>> baselines) {
            StreamsBuilder builder = new StreamsBuilder();
            var envelopes = builder.stream(KafkaTopics.RAW_EVENTS_V1,
                    org.apache.kafka.streams.kstream.Consumed.with(
                            org.apache.kafka.common.serialization.Serdes.String(), serdes.rawEnvelopeSerde()));
            topology.buildPipeline(builder, envelopes);

            Properties props = new Properties();
            props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-topology");
            props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");

            this.driver = new TopologyTestDriver(builder.build(), props);
            this.input = driver.createInputTopic(
                    KafkaTopics.RAW_EVENTS_V1, new StringSerializer(), serdes.rawEnvelopeSerde().serializer());
            this.output = driver.createOutputTopic(
                    KafkaTopics.QUALITY_EVENTS_V1, new StringDeserializer(), serdes.processedEventSerde().deserializer());
            TestInputTopic<String, BaselineMessage> baselineInput = driver.createInputTopic(
                    KafkaTopics.SCHEMA_BASELINES, new StringSerializer(), serdes.baselineMessageSerde().serializer());
            baselines.forEach((eventType, leaves) ->
                    baselineInput.pipeInput(eventType, new BaselineMessage(eventType, 1L, leaves)));
        }

        void pipe(DataEvent event) {
            var envelope = com.driftwatch.event.RawEnvelope.forRest(event, java.time.Instant.now());
            input.pipeInput(com.driftwatch.quality.ScopeKey.of(event.source(), event.eventType()),
                    envelope, event.eventTimestamp().toEpochMilli());
        }

        List<KeyValue<String, ProcessedEvent>> output() {
            return output.readKeyValuesToList();
        }

        @Override
        public void close() {
            driver.close();
        }
    }
}
