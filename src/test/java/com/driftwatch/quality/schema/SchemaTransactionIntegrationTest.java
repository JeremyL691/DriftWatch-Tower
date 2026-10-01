package com.driftwatch.quality.schema;

import com.driftwatch.persistence.BaselineOutboxEntity;
import com.driftwatch.persistence.BaselineOutboxRepository;
import com.driftwatch.persistence.SchemaVersionEntity;
import com.driftwatch.persistence.SchemaVersionRepository;
import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G04: the schema transaction contract (execution guide, section 4.4).
 *
 * <ul>
 *   <li>concurrent first observations of one event type produce exactly one ACTIVE row</li>
 *   <li>a baseline change commits with an outbox row and is published only after broker
 *       acknowledgement, so a crash between commit and publish is recovered by the relay</li>
 *   <li>explicit activation demotes the previous ACTIVE version and reports a truthful sync
 *       state instead of claiming Streams applied it</li>
 * </ul>
 */
class SchemaTransactionIntegrationTest extends ContainerIntegrationTest {

    @Autowired SchemaObservationService observationService;
    @Autowired BaselineOutboxRelay relay;
    @Autowired BaselineOutboxRepository outboxRepository;
    @Autowired SchemaVersionRepository schemaVersionRepository;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void concurrentFirstObservationsProduceExactlyOneActiveVersion() throws Exception {
        String eventType = "g04_concurrent_" + System.nanoTime();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await(5, TimeUnit.SECONDS);
                observationService.observe(eventType,
                        objectMapper.valueToTree(Map.of("symbol", "BTC", "bid", 1.0)),
                        Instant.now());
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<SchemaVersionEntity> rows = schemaVersionRepository.findByEventTypeOrderByFirstSeenAtAsc(eventType);
        assertThat(rows).as("one row per distinct schema hash").hasSize(1);
        assertThat(rows.stream().filter(r -> SchemaVersionEntity.STATUS_ACTIVE.equals(r.getStatus())))
                .as("exactly one ACTIVE baseline under concurrency")
                .hasSize(1);
    }

    @Test
    void firstObservationCreatesBaselineOutboxRowThatIsPublishedAfterAcknowledgement() throws Exception {
        String eventType = "g04_outbox_" + System.nanoTime();
        SchemaObservationService.Observation observation = observationService.observe(
                eventType, objectMapper.valueToTree(Map.of("symbol", "ETH", "ask", 2.0)), Instant.now());
        assertThat(observation.baselineCreated()).isTrue();

        BaselineOutboxEntity pending = outboxRepository
                .findTop50ByStatusOrderByIdAsc(BaselineOutboxEntity.STATUS_PENDING).stream()
                .filter(row -> row.getEventType().equals(eventType))
                .findFirst()
                .orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(BaselineOutboxEntity.STATUS_PENDING);
        assertThat(pending.getSentAt()).isNull();

        assertThat(relay.publishNow(pending.getId())).isTrue();
        BaselineOutboxEntity sent = outboxRepository.findById(pending.getId()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(BaselineOutboxEntity.STATUS_SENT);
        assertThat(sent.getSentAt()).isNotNull();

        // The compacted topic carries the baseline for the Streams global store.
        List<ConsumerRecord<String, BaselineMessage>> records = consumeBaselines(Duration.ofSeconds(20));
        assertThat(records).anySatisfy(record -> {
            assertThat(record.key()).isEqualTo(eventType);
            assertThat(record.value().leafTypes()).containsEntry("symbol", "STRING")
                    .containsEntry("ask", "NUMBER");
        });
    }

    @Test
    void pendingOutboxRowWrittenBeforeACrashIsRepublishedByTheRelay() {
        // Simulates the crash window: the schema change committed, the publish never happened.
        String eventType = "g04_crash_" + System.nanoTime();
        SchemaVersionEntity version = observationService.observe(
                eventType, objectMapper.valueToTree(Map.of("value", 1.0)), Instant.now()).observedRow();
        outboxRepository.findTop50ByStatusOrderByIdAsc(BaselineOutboxEntity.STATUS_PENDING).stream()
                .filter(row -> row.getVersionId().equals(version.getId()))
                .forEach(row -> {
                    // pretend the process died before publishing: mark it pending again
                    row.setStatus(BaselineOutboxEntity.STATUS_PENDING);
                    row.setSentAt(null);
                    outboxRepository.save(row);
                });

        relay.publishPending();

        BaselineOutboxEntity row = outboxRepository
                .findTop50ByStatusOrderByIdAsc(BaselineOutboxEntity.STATUS_PENDING).stream()
                .filter(candidate -> candidate.getVersionId().equals(version.getId()))
                .findFirst()
                .orElse(null);
        assertThat(row).as("the pending row must not stay pending after a relay pass").isNull();
        assertThat(outboxRepository.findAll().stream()
                .filter(candidate -> candidate.getVersionId().equals(version.getId()))
                .findFirst()
                .orElseThrow()
                .getStatus()).isEqualTo(BaselineOutboxEntity.STATUS_SENT);
    }

    @Test
    void activationDemotesThePreviousActiveVersionAndReportsSyncState() throws Exception {
        String eventType = "g04_activate_" + System.nanoTime();
        SchemaVersionEntity first = observationService.observe(
                eventType, objectMapper.valueToTree(Map.of("a", 1.0)), Instant.now()).observedRow();
        SchemaVersionEntity second = observationService.observe(
                eventType, objectMapper.valueToTree(Map.of("a", 1.0, "b", "x")), Instant.now()).observedRow();
        assertThat(second.getStatus()).isEqualTo(SchemaVersionEntity.STATUS_DRIFTING);

        mockMvc.perform(put("/api/v1/schemas/{eventType}/baseline", eventType)
                        .with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version_id\":" + second.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version_id").value(second.getId()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.baseline_sync.state").value("PUBLISHED"));

        List<SchemaVersionEntity> rows = schemaVersionRepository.findByEventTypeOrderByFirstSeenAtAsc(eventType);
        assertThat(rows.stream().filter(r -> SchemaVersionEntity.STATUS_ACTIVE.equals(r.getStatus())))
                .as("only the activated version stays ACTIVE")
                .singleElement()
                .satisfies(row -> assertThat(row.getId()).isEqualTo(second.getId()));
        assertThat(rows.stream().filter(r -> r.getId().equals(first.getId())).findFirst().orElseThrow()
                .getStatus()).isEqualTo(SchemaVersionEntity.STATUS_DRIFTING);
        assertThat(rows).as("no version is deleted by activation").hasSize(2);
    }

    private List<ConsumerRecord<String, BaselineMessage>> consumeBaselines(Duration timeout) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "g04-baseline-reader-" + System.nanoTime());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.driftwatch.quality.schema");
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, BaselineMessage.class.getName());

        List<ConsumerRecord<String, BaselineMessage>> collected = new ArrayList<>();
        try (KafkaConsumer<String, BaselineMessage> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of("schema-baselines-v1"));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline) && collected.isEmpty()) {
                ConsumerRecords<String, BaselineMessage> records = consumer.poll(Duration.ofSeconds(2));
                records.forEach(collected::add);
            }
        }
        return collected;
    }
}