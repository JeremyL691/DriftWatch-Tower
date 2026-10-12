package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.PayloadHasher;
import com.driftwatch.event.RawEnvelope;
import com.driftwatch.quality.*;
import com.driftwatch.quality.schema.BaselineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Serialized store/changelog restoration coverage; physical restart is tested separately with Kafka. */
class WindowStateNamespaceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final StreamSerdes serdes = new StreamSerdes(mapper);
    private static final Instant T = Instant.parse("2026-10-10T00:00:00Z");
    private TopologySettings settings(int windowMinutes, double threshold) {
        return new TopologySettings(Duration.ofMinutes(5), Duration.ofMinutes(windowMinutes), Duration.ofMinutes(10),
                Duration.ofMinutes(2), Duration.ofMinutes(15), threshold, 3, 2, 2, 3, 5);
    }

    @Test void samePolicyRestoresCountsFiredFlagsAndReceiptsButChangedPolicyIsolatesEqualWindowStart() throws Exception {
        Map<String, Map<String, byte[]>> persisted;
        RawEnvelope repeated = envelope("one", T, 1);
        var original = settings(1, .6);
        try (Harness h = new Harness(original, T)) {
            h.pipe(repeated); h.pipe(envelope("two", T.plusSeconds(1), 2)); h.pipe(envelope("three", T.plusSeconds(2), 3));
            assertThat(h.outputs()).flatExtracting(ProcessedEvent::alerts).anyMatch(a -> a.type() == AlertType.NULL_SPIKE);
            persisted = h.capture();
        }
        try (Harness same = new Harness(settings(1, .9), T.plusSeconds(10))) {
            same.restore(persisted);
            same.pipe(envelope("four", T.plusSeconds(3), 4));
            assertThat(same.outputs()).flatExtracting(ProcessedEvent::alerts).noneMatch(a -> a.type() == AlertType.NULL_SPIKE);
            assertThat(same.nullStates(original.windowPolicyNamespace())).singleElement().satisfies(s -> {
                assertThat(s.total()).isEqualTo(4); assertThat(s.fired()).isTrue();
            });
            persisted = same.capture();
        }
        var changed = settings(2, .6);
        assertThat(changed.windowPolicyNamespace()).isNotEqualTo(original.windowPolicyNamespace());
        try (Harness different = new Harness(changed, T.plusSeconds(20))) {
            different.restore(persisted);
            different.pipe(envelope("five", T, 5));
            assertThat(different.outputs()).flatExtracting(ProcessedEvent::alerts).noneMatch(a -> a.type() == AlertType.NULL_SPIKE);
            assertThat(different.nullStates(changed.windowPolicyNamespace())).singleElement().satisfies(s -> assertThat(s.total()).isEqualTo(1));
            different.pipe(repeated);
            assertThat(different.outputs()).singleElement().satisfies(p -> assertThat(p.windowEvaluation().outcome()).isEqualTo(WindowEvaluation.Outcome.REDELIVERY));
            assertThat(different.nullStates(changed.windowPolicyNamespace())).singleElement().satisfies(s -> assertThat(s.total()).isEqualTo(1));
            persisted = different.capture();
        }
        try (Harness back = new Harness(original, T.plusSeconds(30))) {
            back.restore(persisted); back.pipe(envelope("six", T.plusSeconds(4), 6));
            assertThat(back.nullStates(original.windowPolicyNamespace())).singleElement().satisfies(s -> assertThat(s.total()).isEqualTo(5));
            assertThat(back.outputs()).flatExtracting(ProcessedEvent::alerts).noneMatch(a -> a.type() == AlertType.NULL_SPIKE);
        }
    }

    @Test void inactivityCleansEveryPolicyAndMetadataWithoutFutureRecordDrivingEviction() throws Exception {
        var policy = settings(1, .6);
        try (Harness h = new Harness(policy, T)) {
            h.pipe(envelope("valid", T, 1)); h.outputs();
            h.pipe(RawEnvelope.forRest(new DataEvent("future", "namespace-test", "probe", T.plus(Duration.ofDays(100)), Map.of("nonce", 2)), T));
            assertThat(h.outputs()).singleElement().satisfies(p -> assertThat(p.windowEvaluation().outcome()).isEqualTo(WindowEvaluation.Outcome.FUTURE));
            assertThat(h.nullStates(policy.windowPolicyNamespace())).hasSize(1);
            h.driver.advanceWallClockTime(Duration.ofMinutes(16));
            assertThat(h.nullStates(policy.windowPolicyNamespace())).isEmpty();
            assertThat(h.capture().get(QualityStreamsTopology.ANOMALY_WINDOW_STORE)).isEmpty();
            assertOnlyRetiredWatermarkSeeds(h);
            assertThat(h.capture().get(QualityStreamsTopology.ANOMALY_SCOPE_STORE)).isEmpty();
            h.pipe(envelope("new-after-expiry", T, 3));
            assertThat(h.nullStates(policy.windowPolicyNamespace())).singleElement().satisfies(s -> assertThat(s.total()).isEqualTo(1));
        }
    }

    @Test void anomalyHistoryAndFiredFlagSurviveSamePolicyRestoreAndExpiredSwitchBackWarmsUp() throws Exception {
        Map<String, Map<String, byte[]>> persisted;
        var original = settings(1, .6);
        try (Harness h = new Harness(original, T)) {
            h.pipe(envelope("history-1", T.minusSeconds(120), 1));
            h.pipe(envelope("history-2", T.minusSeconds(60), 2));
            for (int i=0; i<5; i++) h.pipe(envelope("burst-"+i, T.plusSeconds(i), i+3));
            assertThat(h.outputs()).flatExtracting(ProcessedEvent::alerts).filteredOn(a -> a.type() == AlertType.ANOMALY_SPIKE).hasSize(1);
            persisted = h.capture();
        }
        try (Harness same = new Harness(original, T.plusSeconds(10))) {
            same.restore(persisted); same.pipe(envelope("burst-after-restart", T.plusSeconds(5), 10));
            assertThat(same.outputs()).flatExtracting(ProcessedEvent::alerts).noneMatch(a -> a.type() == AlertType.ANOMALY_SPIKE);
            var windowKey = WindowStateNamespace.key(original, "namespace-test", "probe", T.toEpochMilli()+"");
            var state = same.driver.<String,AnomalyWindowState>getKeyValueStore(QualityStreamsTopology.ANOMALY_WINDOW_STORE).get(windowKey);
            assertThat(state.count()).isEqualTo(6); assertThat(state.fired()).isTrue();
        }
        try (Harness expiredBack = new Harness(original, T.plus(Duration.ofMinutes(16)))) {
            expiredBack.restore(persisted);
            expiredBack.pipe(envelope("expired-switch-back", T, 11));
            assertThat(expiredBack.outputs()).singleElement().satisfies(p -> assertThat(p.windowEvaluation().detail()).isEqualTo("WARMING_UP"));
            assertThat(expiredBack.nullStates(original.windowPolicyNamespace())).singleElement().satisfies(s -> assertThat(s.total()).isEqualTo(1));
        }
        // Both abandoned and current namespaces, including their metadata, are swept by wall-clock time.
        try (Harness switched = new Harness(settings(2,.6), T.plusSeconds(10))) {
            switched.restore(persisted); switched.pipe(envelope("second-policy", T, 12));
            switched.driver.advanceWallClockTime(Duration.ofMinutes(16));
            assertThat(switched.capture().get(QualityStreamsTopology.NULL_WINDOW_STORE)).isEmpty();
            assertThat(switched.capture().get(QualityStreamsTopology.ANOMALY_WINDOW_STORE)).isEmpty();
            assertThat(switched.capture().get(QualityStreamsTopology.ANOMALY_SCOPE_STORE)).isEmpty();
            assertOnlyRetiredWatermarkSeeds(switched);
            // Seeds are bounded too: after their retention the metadata store is empty.
            switched.driver.advanceWallClockTime(Duration.ofMillis(WindowStateNamespace.SEED_RETENTION_MS));
            assertThat(switched.capture().get(QualityStreamsTopology.SCOPE_WATERMARK_STORE)).isEmpty();
        }
    }

    @Test void oldEventAfterAnIdleLeaseStaysExpiredInsteadOfReseedingTheScope() throws Exception {
        var policy = settings(1, .6);
        Instant wall = T.plus(Duration.ofHours(1));
        try (Harness h = new Harness(policy, wall)) {
            h.pipe(RawEnvelope.forRest(new DataEvent("current", "namespace-test", "probe", wall, Map.of("nonce", 1)), wall));
            h.outputs();
            // The scope goes quiet past its 15 minute lease: window counts are evicted.
            h.driver.advanceWallClockTime(Duration.ofMinutes(20));
            Instant now = wall.plus(Duration.ofMinutes(20));
            // An event from 40 minutes before the last watermark belongs to a window closed long ago.
            h.pipe(RawEnvelope.forRest(new DataEvent("stale", "namespace-test", "probe",
                    wall.minus(Duration.ofMinutes(40)), Map.of("nonce", 2)), now));
            assertThat(h.outputs()).singleElement().satisfies(p ->
                    assertThat(p.windowEvaluation().outcome()).isEqualTo(WindowEvaluation.Outcome.EXPIRED));
            assertThat(h.nullStates(policy.windowPolicyNamespace())).isEmpty();
            // New traffic after the idle period is evaluated normally and the watermark never moves back.
            h.pipe(RawEnvelope.forRest(new DataEvent("resumed", "namespace-test", "probe", now, Map.of("nonce", 3)), now));
            assertThat(h.outputs()).singleElement().satisfies(p ->
                    assertThat(p.windowEvaluation().outcome()).isEqualTo(WindowEvaluation.Outcome.INCLUDED));
            assertThat(h.driver.<String, Long>getKeyValueStore(QualityStreamsTopology.SCOPE_WATERMARK_STORE)
                    .get(WindowStateNamespace.key(policy, "namespace-test", "probe"))).isEqualTo(now.toEpochMilli());
        }
    }

    private void assertOnlyRetiredWatermarkSeeds(Harness h) throws Exception {
        assertThat(h.capture().get(QualityStreamsTopology.SCOPE_WATERMARK_STORE).keySet())
                .as("only bounded watermark seeds outlive the inactivity lease")
                .allMatch(k -> k.startsWith(WindowStateNamespace.SEED) || k.startsWith(WindowStateNamespace.SEED_AT));
    }

    @Test void missingLeaseNeverResurrectsCountersBetweenIndependentCleanupPunctuations() throws Exception {
        var policy = settings(1,.6);
        Map<String, Map<String, byte[]>> persisted;
        try (Harness h = new Harness(policy,T)) {
            h.pipe(envelope("leased-1", T, 1)); h.pipe(envelope("leased-2", T.plusSeconds(1), 2));
            persisted = h.capture();
        }
        // Represents shared metadata already removed by one processor while the other's sweep is not yet due.
        persisted.get(QualityStreamsTopology.SCOPE_WATERMARK_STORE).remove("touch:" + WindowStateNamespace.key(policy,"namespace-test","probe"));
        try (Harness resumed = new Harness(policy,T.plusSeconds(2))) {
            resumed.restore(persisted); resumed.pipe(envelope("unleased",T.plusSeconds(2),3));
            assertThat(resumed.nullStates(policy.windowPolicyNamespace())).singleElement().satisfies(s -> assertThat(s.total()).isEqualTo(1));
            var count = resumed.driver.<String,AnomalyWindowState>getKeyValueStore(QualityStreamsTopology.ANOMALY_WINDOW_STORE)
                    .get(WindowStateNamespace.key(policy,"namespace-test","probe",T.toEpochMilli()+""));
            assertThat(count.count()).isEqualTo(1);
            assertThat(resumed.outputs()).singleElement().satisfies(p -> assertThat(p.windowEvaluation().detail()).isEqualTo("WARMING_UP"));
        }
    }

    @Test void changedPolicyNeverInheritsWatermarkOrAnomalyHistoryAndLegacyStateWarmsUp() throws Exception {
        Map<String, Map<String, byte[]>> persisted;
        try (Harness old = new Harness(settings(1,.6), T)) {
            old.pipe(envelope("old-watermark", T.plusSeconds(3600), 1));
            persisted = old.capture();
        }
        var policy = settings(2,.6);
        try (Harness current = new Harness(policy, T.plusSeconds(1))) {
            current.restore(persisted);
            current.pipe(envelope("fresh-policy", T, 2));
            assertThat(current.outputs()).singleElement().satisfies(p -> {
                assertThat(p.windowEvaluation().outcome()).isEqualTo(WindowEvaluation.Outcome.INCLUDED);
                assertThat(p.windowEvaluation().detail()).isEqualTo("WARMING_UP");
                assertThat(p.windowEvaluation().scope()).isEqualTo(ScopeKey.of("namespace-test", "probe"));
            });
            // Legacy JSON-array keys are intentionally not guessed to belong to a policy.
            current.driver.<String, NullWindowState>getKeyValueStore(QualityStreamsTopology.NULL_WINDOW_STORE)
                    .put(ScopeKey.of("namespace-test", "probe", "ask", T.toEpochMilli()+"", "1"), new NullWindowState(T.toEpochMilli(), 100, 100, true));
            current.driver.<String, Long>getKeyValueStore(QualityStreamsTopology.SCOPE_WATERMARK_STORE)
                    .put(ScopeKey.of("namespace-test", "probe"), T.plusSeconds(3600).toEpochMilli());
            current.driver.<String, AnomalyWindowState>getKeyValueStore(QualityStreamsTopology.ANOMALY_WINDOW_STORE)
                    .put(ScopeKey.of("namespace-test", "probe", T.toEpochMilli()+""), new AnomalyWindowState(T.toEpochMilli(), 100, true));
            current.driver.<String, Long>getKeyValueStore(QualityStreamsTopology.ANOMALY_SCOPE_STORE)
                    .put(ScopeKey.of("namespace-test", "probe"), T.toEpochMilli());
            current.driver.advanceWallClockTime(Duration.ofMinutes(1));
            assertThat(current.capture().get(QualityStreamsTopology.ANOMALY_WINDOW_STORE).keySet()).noneMatch(k -> k.startsWith("["));
            assertThat(current.capture().get(QualityStreamsTopology.ANOMALY_SCOPE_STORE).keySet()).noneMatch(k -> k.startsWith("["));
            assertThat(current.nullStates("[")).isEmpty();
            assertThat(current.capture().get(QualityStreamsTopology.SCOPE_WATERMARK_STORE).keySet()).noneMatch(k -> k.startsWith("["));
        }
    }

    private RawEnvelope envelope(String id, Instant eventTime, int nonce) {
        return RawEnvelope.forRest(new DataEvent(id, "namespace-test", "probe", eventTime, Map.of("nonce", nonce)), eventTime);
    }

    private class Harness implements AutoCloseable {
        final TopologyTestDriver driver;
        final TestInputTopic<String, RawEnvelope> input;
        final TestOutputTopic<String, ProcessedEvent> output;
        Harness(TopologySettings settings, Instant wallClock) {
            var topology = new QualityStreamsTopology(serdes, new PayloadHasher(), new LateEventDetector(mapper, Duration.ofDays(1)),
                    new FieldRangeDetector(mapper, Map.of()), new FieldFormatDetector(mapper, ""), mapper, settings);
            var builder = new StreamsBuilder();
            topology.buildPipeline(builder, builder.stream(KafkaTopics.RAW_EVENTS_V1, Consumed.with(Serdes.String(), serdes.rawEnvelopeSerde())));
            Properties p = new Properties(); p.put(StreamsConfig.APPLICATION_ID_CONFIG, "namespace-test"); p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
            driver = new TopologyTestDriver(builder.build(), p, wallClock);
            input = driver.createInputTopic(KafkaTopics.RAW_EVENTS_V1, new StringSerializer(), serdes.rawEnvelopeSerde().serializer());
            output = driver.createOutputTopic(KafkaTopics.QUALITY_EVENTS_V1, new StringDeserializer(), serdes.processedEventSerde().deserializer());
            driver.createInputTopic(KafkaTopics.SCHEMA_BASELINES, new StringSerializer(), serdes.baselineMessageSerde().serializer())
                    .pipeInput("probe", new BaselineMessage("probe", 1L, Map.of("ask", "NUMBER")));
        }
        void pipe(RawEnvelope e) { input.pipeInput(ScopeKey.of(e.event().source(), e.event().eventType()), e, e.event().eventTimestamp().toEpochMilli()); }
        List<ProcessedEvent> outputs() { return output.readValuesToList(); }
        List<NullWindowState> nullStates(String prefix) {
            List<NullWindowState> result = new ArrayList<>();
            try (var it = driver.<String, NullWindowState>getKeyValueStore(QualityStreamsTopology.NULL_WINDOW_STORE).all()) {
                while(it.hasNext()) { var e=it.next(); if(e.key.startsWith(prefix)) result.add(e.value); }
            }
            return result;
        }
        Map<String, Map<String, byte[]>> capture() throws Exception {
            Map<String, Map<String, byte[]>> result = new HashMap<>();
            for (String name : List.of(QualityStreamsTopology.NULL_WINDOW_STORE, QualityStreamsTopology.ANOMALY_WINDOW_STORE,
                    QualityStreamsTopology.SCOPE_WATERMARK_STORE, QualityStreamsTopology.ANOMALY_SCOPE_STORE, QualityStreamsTopology.ENVELOPE_DIGEST_STORE)) {
                Map<String, byte[]> rows = new HashMap<>();
                try (var it = driver.<String,Object>getKeyValueStore(name).all()) { while(it.hasNext()) { var e=it.next(); rows.put(e.key, mapper.writeValueAsBytes(e.value)); } }
                result.put(name, rows);
            }
            return result;
        }
        void restore(Map<String, Map<String, byte[]>> persisted) throws Exception {
            for(var store : persisted.entrySet()) {
                Class<?> type = switch(store.getKey()) {
                    case QualityStreamsTopology.NULL_WINDOW_STORE -> NullWindowState.class;
                    case QualityStreamsTopology.ANOMALY_WINDOW_STORE -> AnomalyWindowState.class;
                    case QualityStreamsTopology.ENVELOPE_DIGEST_STORE -> String.class;
                    default -> Long.class;
                };
                KeyValueStore<String,Object> target = driver.getKeyValueStore(store.getKey());
                for(var row : store.getValue().entrySet()) target.put(row.getKey(), mapper.readValue(row.getValue(), type));
            }
        }
        public void close() { driver.close(); }
    }
}
