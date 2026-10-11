package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.PayloadHasher;
import com.driftwatch.event.RawEnvelope;
import com.driftwatch.quality.*;
import com.driftwatch.quality.schema.SchemaObservationService;
import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real broker/changelog restart with fresh physical state directories and stable application identity. */
class WindowStateNamespaceKafkaIntegrationTest extends ContainerIntegrationTest {
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired StreamSerdes serdes;
    @Autowired SchemaObservationService schemas;
    @TempDir Path directories;

    @Test void persistedChangelogRestoresSamePolicyAndIsolatesNewPolicyWithoutResettingDeliveryIdentity() throws Exception {
        String appId = "policy-restart-" + UUID.randomUUID();
        String inputTopic = appId + "-input";
        String eventType = "policy_" + UUID.randomUUID().toString().replace("-", "");
        String source = "policy-restart-test";
        String bootstrap = environment.getProperty("spring.kafka.bootstrap-servers");
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            admin.createTopics(List.of(new NewTopic(inputTopic, 1, (short) 1))).all().get();
        }
        Long baselineId = schemas.observe(eventType, mapper.valueToTree(Map.of("ask", 1, "nonce", 0)), Instant.now()).baseline().getId();
        long startMs = Math.floorDiv(Instant.now().toEpochMilli(), 120000) * 120000;
        Instant start = Instant.ofEpochMilli(startMs);
        var original = settings(1);
        String oldKey = WindowStateNamespace.key(original, source, eventType, "ask", startMs + "", baselineId + "");
        var firstEnvelope = envelope("one", source, eventType, start, 1);
        Properties producerProperties = new Properties(); producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (var producer = new KafkaProducer<String, byte[]>(producerProperties, new StringSerializer(), new ByteArraySerializer())) {
            try (var first = start(appId, inputTopic, bootstrap, original, directories.resolve("first"))) {
                awaitBaseline(first, eventType);
                send(producer, inputTopic, firstEnvelope);
                send(producer, inputTopic, envelope("two", source, eventType, start.plusSeconds(1), 2));
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(nullState(first, oldKey)).isNotNull().satisfies(state -> assertThat(state.total()).isEqualTo(2)));
            }
            // Different, initially empty directory forces Kafka changelog restoration, not reuse of an open Java object.
            try (var same = start(appId, inputTopic, bootstrap, original, directories.resolve("same-policy"))) {
                awaitBaseline(same, eventType);
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(nullState(same, oldKey)).isNotNull().satisfies(state -> assertThat(state.total()).isEqualTo(2)));
                send(producer, inputTopic, envelope("three", source, eventType, start.plusSeconds(2), 3));
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    assertThat(nullState(same, oldKey)).isNotNull().satisfies(state -> {
                        assertThat(state.total()).isEqualTo(3);
                        assertThat(state.fired()).isTrue();
                    });
                });
            }
            var changed = settings(2);
            String newKey = WindowStateNamespace.key(changed, source, eventType, "ask", startMs + "", baselineId + "");
            try (var different = start(appId, inputTopic, bootstrap, changed, directories.resolve("new-policy"))) {
                awaitBaseline(different, eventType);
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(nullState(different, oldKey)).isNotNull().satisfies(state -> assertThat(state.fired()).isTrue()));
                assertThat(nullState(different, newKey)).isNull();
                send(producer, inputTopic, envelope("four", source, eventType, start.plusSeconds(3), 4));
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(nullState(different, newKey)).isNotNull().satisfies(state -> assertThat(state.total()).isEqualTo(1)));
                send(producer, inputTopic, firstEnvelope);
                // Send a new identity afterwards as an ordering barrier, proving the redelivery was actually consumed.
                send(producer, inputTopic, envelope("five", source, eventType, start.plusSeconds(4), 5));
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(nullState(different, newKey)).isNotNull().satisfies(state -> assertThat(state.total()).isEqualTo(2)));
                assertThat(nullState(different, oldKey).total()).isEqualTo(3);
                assertThat(different.store(StoreQueryParameters.fromNameAndType(QualityStreamsTopology.ENVELOPE_DIGEST_STORE,
                        QueryableStoreTypes.<String,String>keyValueStore())).get(firstEnvelope.ingestionId().toString())).isNotNull();
            }
        }
    }

    private TopologySettings settings(int minutes) {
        return new TopologySettings(Duration.ofMinutes(5), Duration.ofMinutes(minutes), Duration.ofMinutes(10),
                Duration.ofMinutes(2), Duration.ofMinutes(15), .6, 3, 2, 2, 3, 5);
    }
    private KafkaStreams start(String app, String topic, String bootstrap, TopologySettings settings, Path stateDir) {
        var builder = new StreamsBuilder();
        var topology = new QualityStreamsTopology(serdes, new PayloadHasher(), new LateEventDetector(mapper, Duration.ofDays(1)),
                new FieldRangeDetector(mapper, Map.of()), new FieldFormatDetector(mapper, ""), mapper, settings);
        topology.buildPipeline(builder, builder.stream(topic, Consumed.with(Serdes.String(), serdes.rawEnvelopeSerde())));
        Properties p = new Properties(); p.put(StreamsConfig.APPLICATION_ID_CONFIG, app);
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); p.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        p.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        p.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100); p.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        p.put("auto.offset.reset", "earliest");
        var streams = new KafkaStreams(builder.build(), p); streams.start();
        try {
            await().atMost(Duration.ofSeconds(60)).until(() -> streams.state() == KafkaStreams.State.RUNNING);
            return streams;
        } catch (RuntimeException | AssertionError failure) {
            streams.close(Duration.ofSeconds(10));
            throw failure;
        }
    }
    private void awaitBaseline(KafkaStreams streams, String type) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(streams.store(StoreQueryParameters.fromNameAndType(
                QualityStreamsTopology.SCHEMA_BASELINE_STORE, QueryableStoreTypes.<String,com.driftwatch.quality.schema.BaselineMessage>timestampedKeyValueStore())).get(type)).isNotNull());
    }
    private NullWindowState nullState(KafkaStreams streams, String key) {
        return streams.store(StoreQueryParameters.fromNameAndType(QualityStreamsTopology.NULL_WINDOW_STORE,
                QueryableStoreTypes.<String,NullWindowState>keyValueStore())).get(key);
    }
    private RawEnvelope envelope(String id, String source, String type, Instant time, int nonce) {
        return RawEnvelope.forRest(new DataEvent(id, source, type, time, Map.of("nonce", nonce)), Instant.now());
    }
    private void send(KafkaProducer<String,byte[]> producer, String topic, RawEnvelope envelope) throws Exception {
        producer.send(new ProducerRecord<>(topic, ScopeKey.of(envelope.event().source(), envelope.event().eventType()),
                mapper.writeValueAsBytes(envelope))).get();
    }
}
