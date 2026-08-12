package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.PayloadHasher;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.FieldFormatDetector;
import com.driftwatch.quality.FieldRangeDetector;
import com.driftwatch.quality.LateEventDetector;
import com.driftwatch.quality.SchemaDriftDetector;
import com.driftwatch.quality.schema.SchemaBaselineProvider;
import com.driftwatch.quality.schema.SchemaRegistry;
import com.driftwatch.persistence.SchemaVersionRepository;
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

    /** No expected leaf fields → the null-spike processor is inert. */
    private SchemaBaselineProvider noFields() {
        return eventType -> Map.of();
    }

    private TopologySettings settings() {
        return new TopologySettings(Duration.ofMinutes(5), Duration.ofMinutes(1), 0.6, 3, 2, 2, 3.0, 5);
    }

    /** Stubbed schema registry: every event is a fresh baseline, so drift never fires in unit tests. */
    private SchemaDriftDetector schemaDrift() {
        return new SchemaDriftDetector(new SchemaRegistry(fakeSchemaRepo(), objectMapper), objectMapper);
    }

    @SuppressWarnings("unchecked")
    private SchemaVersionRepository fakeSchemaRepo() {
        return (SchemaVersionRepository) java.lang.reflect.Proxy.newProxyInstance(
                SchemaVersionRepository.class.getClassLoader(),
                new Class<?>[]{SchemaVersionRepository.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findByEventTypeAndSchemaHash":
                        case "findFirstByEventTypeAndStatus":
                            return java.util.Optional.empty();
                        case "save":
                            return args[0];
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == long.class) return 0L;
                            if (method.getReturnType() == int.class) return 0;
                            if (List.class.isAssignableFrom(method.getReturnType())) return List.of();
                            if (method.getReturnType() == java.util.Optional.class) return java.util.Optional.empty();
                            return null;
                    }
                });
    }

    private Driver driver() {
        return driver(quietLate(), emptyRange(), emptyFormat(), noFields(), settings());
    }

    private Driver driver(LateEventDetector late,
                          FieldRangeDetector range,
                          FieldFormatDetector format,
                          SchemaBaselineProvider baseline,
                          TopologySettings topologySettings) {
        QualityStreamsTopology topology = new QualityStreamsTopology(
                serdes, hasher, late, range, format, schemaDrift(), baseline, objectMapper, topologySettings);
        return new Driver(topology);
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
        try (Driver driver = driver(late, emptyRange(), emptyFormat(), noFields(), settings())) {
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
        try (Driver driver = driver(quietLate(), range, format, noFields(), settings())) {
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
        SchemaBaselineProvider baseline = eventType -> Map.of("ask", "NUMBER");
        try (Driver driver = driver(quietLate(), emptyRange(), emptyFormat(), baseline, settings())) {
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
        private final TestInputTopic<String, DataEvent> input;
        private final TestOutputTopic<String, ProcessedEvent> output;

        Driver(QualityStreamsTopology topology) {
            StreamsBuilder builder = new StreamsBuilder();
            topology.apply(builder);

            Properties props = new Properties();
            props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-topology");
            props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");

            this.driver = new TopologyTestDriver(builder.build(), props);
            this.input = driver.createInputTopic(
                    KafkaTopics.RAW_EVENTS, new StringSerializer(), serdes.dataEventSerde().serializer());
            this.output = driver.createOutputTopic(
                    KafkaTopics.QUALITY_EVENTS, new StringDeserializer(), serdes.processedEventSerde().deserializer());
        }

        void pipe(DataEvent event) {
            input.pipeInput(event.source() + "|" + event.eventType(), event, event.eventTimestamp().toEpochMilli());
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
