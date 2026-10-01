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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression inputs from the execution guide, section 5.4. Two of these (the all-missing
 * null spike and the small-baseline anomaly spike) reproduce known missed detections in the
 * audited implementation; they are expected to fail until the P2 detection contract fixes
 * land. The remaining cases lock behaviour that already works so a fix cannot special-case
 * only the new inputs.
 *
 * <p>Thresholds are the documented defaults (null rate 0.6 with 3 samples, anomaly ratio 3.0
 * with min count 5 and two baseline windows) and must not be tuned to make cases pass.
 *
 * <p>Cases that currently fail on the audited implementation are tagged {@code expected-failure}
 * and excluded from the default surefire run so the baseline suite stays meaningful. Run them
 * explicitly with {@code ./mvnw test -Dgroups=expected-failure -Dtest=Guide54DetectionRegressionTest}.
 * The P2 detection fixes must make them pass; remove the tag and the surefire exclusion then.
 */
class Guide54DetectionRegressionTest {

    private static final String SOURCE = "guide54-source";
    private static final String NULL_TYPE = "guide54_null_event";
    private static final String ANOMALY_TYPE = "guide54_anomaly_event";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final StreamSerdes serdes = new StreamSerdes(objectMapper);
    private final PayloadHasher hasher = new PayloadHasher();

    /** Guide 5.4 case 1: five consecutive missing values must yield exactly one NULL_SPIKE. */
    @Test
    @Tag("expected-failure")
    void allMissingValuesProduceExactlyOneNullSpike() {
        SchemaBaselineProvider baseline = eventType -> Map.of("ask", "NUMBER");
        try (Driver driver = driver(baseline)) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            for (int i = 0; i < 5; i++) {
                driver.pipe(new DataEvent("null-" + i, SOURCE, NULL_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", 100.0 + i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(5);

            List<ProcessedEvent.ProcessedAlert> nullAlerts = nullAlerts(results);
            assertThat(nullAlerts)
                    .as("threshold is met from the 3rd event onward, but the window must fire only once")
                    .hasSize(1);
            ProcessedEvent.ProcessedAlert alert = nullAlerts.get(0);
            assertThat(alert.evidence().get("null_count").asLong()).isEqualTo(3);
            assertThat(alert.evidence().get("total_count").asLong()).isEqualTo(3);
            assertThat(alert.evidence().get("null_rate").asDouble()).isEqualTo(1.0);
            assertThat(alert.evidence().get("window_start").asText())
                    .isEqualTo(windowStart.toString());
        }
    }

    /** Guide 5.4 case 3 (pre-existing input): normal, missing, missing keeps firing once. */
    @Test
    void normalThenTwoMissingStillProducesOneNullSpike() {
        SchemaBaselineProvider baseline = eventType -> Map.of("ask", "NUMBER");
        try (Driver driver = driver(baseline)) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("old-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0, "ask", 2.0)));
            driver.pipe(new DataEvent("old-1", SOURCE, NULL_TYPE, windowStart.plusSeconds(1), Map.of("bid", 2.0)));
            driver.pipe(new DataEvent("old-2", SOURCE, NULL_TYPE, windowStart.plusSeconds(2), Map.of("bid", 3.0)));

            List<ProcessedEvent.ProcessedAlert> nullAlerts = nullAlerts(driver.output());
            assertThat(nullAlerts).hasSize(1);
            assertThat(nullAlerts.get(0).evidence().get("null_count").asLong()).isEqualTo(2);
            assertThat(nullAlerts.get(0).evidence().get("total_count").asLong()).isEqualTo(3);
        }
    }

    /** Guide 5.4 case 2: one event in each of the two prior windows, then a five-event burst. */
    @Test
    @Tag("expected-failure")
    void fiveEventBurstOverSingleEventBaselineProducesOneAnomalySpike() {
        try (Driver driver = driver(eventType -> Map.of())) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("base-2", SOURCE, ANOMALY_TYPE,
                    windowStart.minusSeconds(120), Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("base-1", SOURCE, ANOMALY_TYPE,
                    windowStart.minusSeconds(60), Map.of("bid", 2.0)));
            for (int i = 0; i < 5; i++) {
                driver.pipe(new DataEvent("burst-" + i, SOURCE, ANOMALY_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", 10.0 + i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(7);

            List<ProcessedEvent.ProcessedAlert> anomalyAlerts = anomalyAlerts(results);
            assertThat(anomalyAlerts)
                    .as("the 5th event reaches min count 5 and ratio 5.0 against baseline 1.0; fire exactly once")
                    .hasSize(1);
            ProcessedEvent.ProcessedAlert alert = anomalyAlerts.get(0);
            assertThat(alert.evidence().get("baseline").asDouble()).isEqualTo(1.0);
            assertThat(alert.evidence().get("current_value").asLong()).isEqualTo(5);
            assertThat(alert.evidence().get("ratio").asDouble()).isEqualTo(5.0);
        }
    }

    /** Guide 5.4 case 3 (pre-existing input): two two-event baseline windows then eight events. */
    @Test
    void twoByTwoBaselineThenEightEventBurstStillProducesOneAnomalySpike() {
        try (Driver driver = driver(eventType -> Map.of())) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("old-a0", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(120), Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("old-a1", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(119), Map.of("bid", 2.0)));
            driver.pipe(new DataEvent("old-b0", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(60), Map.of("bid", 3.0)));
            driver.pipe(new DataEvent("old-b1", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(59), Map.of("bid", 4.0)));
            for (int i = 0; i < 8; i++) {
                driver.pipe(new DataEvent("old-c" + i, SOURCE, ANOMALY_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", 100.0 + i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(12);
            List<ProcessedEvent.ProcessedAlert> anomalyAlerts = anomalyAlerts(results);
            assertThat(anomalyAlerts).hasSize(1);
            assertThat(anomalyAlerts.get(0).evidence().get("baseline").asDouble()).isEqualTo(2.0);
        }
    }

    /**
     * Guide 5.4 case 7: an out-of-order event from the previous window must not erase the
     * counts already accumulated in the current window.
     */
    @Test
    @Tag("expected-failure")
    void outOfOrderPreviousWindowEventDoesNotEraseCurrentWindowCounts() {
        SchemaBaselineProvider baseline = eventType -> Map.of("ask", "NUMBER");
        try (Driver driver = driver(baseline)) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("ooo-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0, "ask", 2.0)));
            driver.pipe(new DataEvent("ooo-1", SOURCE, NULL_TYPE, windowStart.plusSeconds(1), Map.of("bid", 2.0)));
            // Late arrival belonging to the previous window.
            driver.pipe(new DataEvent("ooo-2", SOURCE, NULL_TYPE,
                    windowStart.minusSeconds(60), Map.of("bid", 3.0, "ask", 4.0)));
            driver.pipe(new DataEvent("ooo-3", SOURCE, NULL_TYPE, windowStart.plusSeconds(2), Map.of("bid", 4.0)));

            List<ProcessedEvent.ProcessedAlert> nullAlerts = nullAlerts(driver.output());
            assertThat(nullAlerts)
                    .as("the T window reaches 2 missing of 3 total only if the T-1 event did not wipe it")
                    .hasSize(1);
            assertThat(nullAlerts.get(0).evidence().get("null_count").asLong()).isEqualTo(2);
            assertThat(nullAlerts.get(0).evidence().get("total_count").asLong()).isEqualTo(3);
            assertThat(nullAlerts.get(0).evidence().get("window_start").asText())
                    .isEqualTo(windowStart.toString());
        }
    }

    /** Guide 5.4 case 5: a repeated event_id keeps both inputs and flags a business duplicate. */
    @Test
    void repeatedEventIdKeepsInputsAndFlagsBusinessDuplicate() {
        try (Driver driver = driver(eventType -> Map.of())) {
            DataEvent event = new DataEvent("dup-1", SOURCE, ANOMALY_TYPE,
                    Instant.now().truncatedTo(ChronoUnit.MINUTES), Map.of("bid", 1.0));
            driver.pipe(event);
            driver.pipe(event);

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(2);
            assertThat(results).allSatisfy(r ->
                    assertThat(r.value.event().eventId()).isEqualTo("dup-1"));
            assertThat(anomalyAlerts(results)).isEmpty();
            assertThat(results.get(1).value.alerts()).anySatisfy(a ->
                    assertThat(a.type()).isEqualTo(AlertType.DUPLICATE_EVENT));
            assertThat(results.get(0).value.alerts()).isEmpty();
        }
    }

    private static List<ProcessedEvent.ProcessedAlert> nullAlerts(
            List<KeyValue<String, ProcessedEvent>> results) {
        return results.stream()
                .flatMap(r -> r.value.alerts().stream())
                .filter(a -> a.type() == AlertType.NULL_SPIKE)
                .toList();
    }

    private static List<ProcessedEvent.ProcessedAlert> anomalyAlerts(
            List<KeyValue<String, ProcessedEvent>> results) {
        return results.stream()
                .flatMap(r -> r.value.alerts().stream())
                .filter(a -> a.type() == AlertType.ANOMALY_SPIKE)
                .toList();
    }

    private Driver driver(SchemaBaselineProvider baseline) {
        TopologySettings settings = new TopologySettings(
                Duration.ofMinutes(5), Duration.ofMinutes(1), 0.6, 3, 2, 2, 3.0, 5);
        QualityStreamsTopology topology = new QualityStreamsTopology(
                serdes, hasher,
                new LateEventDetector(objectMapper, Duration.ofMinutes(5)),
                new FieldRangeDetector(objectMapper, Map.of()),
                new FieldFormatDetector(objectMapper, ""),
                new SchemaDriftDetector(new SchemaRegistry(fakeSchemaRepo(), objectMapper), objectMapper),
                baseline, objectMapper, settings);
        return new Driver(topology);
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

    /** Wraps a TopologyTestDriver wired to the full quality topology. */
    private class Driver implements AutoCloseable {
        private final TopologyTestDriver driver;
        private final TestInputTopic<String, DataEvent> input;
        private final TestOutputTopic<String, ProcessedEvent> output;

        Driver(QualityStreamsTopology topology) {
            StreamsBuilder builder = new StreamsBuilder();
            topology.apply(builder);

            Properties props = new Properties();
            props.put(StreamsConfig.APPLICATION_ID_CONFIG, "guide54-regression");
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
