package com.driftwatch.dlt;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.RawEnvelope;
import com.driftwatch.persistence.DeadLetterRecordEntity;
import com.driftwatch.persistence.DeadLetterRecordRepository;
import com.driftwatch.stream.ProcessedEvent;
import com.driftwatch.stream.WindowEvaluation;
import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G06: the dead-letter path end to end (execution guide, sections 4.5 and 7.1).
 *
 * <ul>
 *   <li>a malformed or unsupported raw record becomes a dead letter and does not block later records</li>
 *   <li>the Kafka dead letter is projected idempotently into {@code dead_letter_records}</li>
 *   <li>a SINK replay re-enters at the sink with the original identity and cannot double count</li>
 *   <li>a STREAM replay re-enters at the raw topic with the original envelope</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.Random.class)
class DeadLetterIntegrationTest extends ContainerIntegrationTest {

    @Autowired
    KafkaTemplate<String, DltMessage> dltTemplate;
    @Autowired
    DeadLetterRecordRepository recordRepository;

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private void recordRepetitionSeed(int repetition, long seed) {
        String line = "repetition=" + repetition + " seed=" + seed + System.lineSeparator();
        System.out.print("G06 interleaving " + line);
        String path = System.getProperty("dwt.test.seed-log");
        if (path == null || path.isBlank()) return;
        Path seedLog = Path.of(path).toAbsolutePath();
        try {
            Files.createDirectories(seedLog.getParent());
            Files.writeString(seedLog, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new UncheckedIOException("unable to persist G06 repetition seed", error);
        }
    }

    /** Publishes true bytes with no type headers, like a foreign producer would. */
    private RecordMetadata sendRawBytes(String key, byte[] payload) throws Exception {
        return sendRawBytes(key, payload, null);
    }

    private RecordMetadata sendRawBytes(String key, byte[] payload, Integer partition) throws Exception {
        java.util.Properties properties = new java.util.Properties();
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArraySerializer.class);
        try (org.apache.kafka.clients.producer.KafkaProducer<String, byte[]> producer =
                     new org.apache.kafka.clients.producer.KafkaProducer<>(properties)) {
            return producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                    KafkaTopics.RAW_EVENTS_V1, partition, key, payload)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private long rawRows(String ingestionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_events WHERE ingestion_id = ?", Integer.class, ingestionId);
        return count == null ? 0 : count;
    }

    private DeadLetterRecordEntity awaitRecord(String diagnosticId) {
        await().atMost(Duration.ofSeconds(60)).until(() -> recordRepository
                .findByDiagnosticId(diagnosticId).isPresent());
        return recordRepository.findByDiagnosticId(diagnosticId).orElseThrow();
    }

    @RepeatedTest(value = 20, name = "malformed record remains isolated ({currentRepetition}/{totalRepetitions})")
    void malformedRecordIsDeadLetteredAndDoesNotBlockLaterRecords(RepetitionInfo repetitionInfo) throws Exception {
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        recordRepetitionSeed(repetitionInfo.getCurrentRepetition(), seed);
        RecordMetadata badRecord = sendRawBytes("g06-bad-" + Long.toUnsignedString(seed),
                "{not-json".getBytes(StandardCharsets.UTF_8));

        String diagnosticId = DltMessage.diagnosticIdFor(null, KafkaTopics.RAW_EVENTS_V1,
                badRecord.partition(), badRecord.offset());
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(recordRepository.findByDiagnosticId(diagnosticId)).isPresent());

        // Project a newer row with the same reason. A global "latest MALFORMED_RECORD" query
        // would now select this interference row instead of the record sent by this repetition.
        String unrelatedDiagnosticId = "ingestion:" + UUID.randomUUID();
        DltMessage unrelatedMalformedRecord = new DltMessage(unrelatedDiagnosticId, DltStage.STREAM,
                unrelatedDiagnosticId.substring("ingestion:".length()), "g06-source-" + Long.toUnsignedString(seed), "g06_event",
                "MALFORMED_RECORD: unrelated later record", 1, KafkaTopics.RAW_EVENTS_V1,
                0, Math.floorMod(seed, 100_000L), "{not-json", Instant.now());
        dltTemplate.send(KafkaTopics.DEAD_LETTER_EVENTS, unrelatedDiagnosticId, unrelatedMalformedRecord)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        awaitRecord(unrelatedDiagnosticId);

        // A valid record right after the bad one must still be processed.
        String ingestionId = UUID.randomUUID().toString();
        RawEnvelope envelope = new RawEnvelope(RawEnvelope.CONTRACT_VERSION, UUID.fromString(ingestionId),
                new DataEvent("g06-good-" + ingestionId, "g06-source", "g06_event",
                        Instant.now().minusSeconds(5), Map.of("bid", 1.0)),
                Instant.now(), RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, null);
        RecordMetadata laterRecord = sendRawBytes("g06-good-" + ingestionId,
                objectMapper.writeValueAsBytes(envelope), badRecord.partition());

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(recordRepository.findByDiagnosticId(diagnosticId)).isPresent();
        });
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT diagnostic_id, stage, kafka_topic, kafka_partition, kafka_offset"
                        + " FROM dead_letter_records WHERE diagnostic_id = ?", diagnosticId);
        assertThat(row.get("diagnostic_id")).as("seed=%d", seed).isEqualTo(diagnosticId);
        assertThat(row.get("stage")).as("seed=%d", seed).isEqualTo(DltStage.STREAM.name());
        assertThat(row.get("kafka_topic")).as("seed=%d", seed).isEqualTo(KafkaTopics.RAW_EVENTS_V1);
        assertThat(((Number) row.get("kafka_partition")).intValue()).as("seed=%d", seed).isEqualTo(badRecord.partition());
        assertThat(((Number) row.get("kafka_offset")).longValue()).as("seed=%d", seed).isEqualTo(badRecord.offset());
        assertThat(laterRecord.partition()).as("seed=%d", seed).isEqualTo(badRecord.partition());
        assertThat(laterRecord.offset()).as("seed=%d", seed).isGreaterThan(badRecord.offset());

        await().atMost(Duration.ofSeconds(60)).until(() -> rawRows(ingestionId) == 1);
        assertThat(rawRows(ingestionId)).as("the bad record must not block the next one; seed=%d", seed).isEqualTo(1);
    }

    @Test
    void unsupportedContractVersionIsAnExplicitFailurePath() throws Exception {
        String ingestionId = UUID.randomUUID().toString();
        String payload = objectMapper.writeValueAsString(Map.of(
                "contract_version", 2,
                "ingestion_id", ingestionId,
                "event", Map.of("event_id", "g06-v2", "source", "g06-source", "event_type", "g06_event",
                        "event_timestamp", Instant.now().toString(), "payload", Map.of("bid", 1.0)),
                "received_at", Instant.now().toString(),
                "origin", "REST",
                "mode", "LIVE"));
        sendRawBytes("g06-v2", payload.getBytes(StandardCharsets.UTF_8));

        DeadLetterRecordEntity record = awaitRecord("ingestion:" + ingestionId);
        assertThat(record.getReason()).contains("UNSUPPORTED_CONTRACT_VERSION");
        assertThat(record.getIngestionId()).isEqualTo(ingestionId);
    }

    @Test
    void deadLetterProjectionIsIdempotent() throws Exception {
        String diagnosticId = "ingestion:" + UUID.randomUUID();
        DltMessage message = new DltMessage(diagnosticId, DltStage.STREAM, diagnosticId.substring(10),
                "g06-source", "g06_event", "MALFORMED_RECORD: probe", 1,
                KafkaTopics.RAW_EVENTS_V1, 1, 42L, "{\"raw\":\"probe\"}", Instant.now());
        dltTemplate.send(KafkaTopics.DEAD_LETTER_EVENTS, diagnosticId, message)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        dltTemplate.send(KafkaTopics.DEAD_LETTER_EVENTS, diagnosticId, message)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);

        DeadLetterRecordEntity record = awaitRecord(diagnosticId);
        Thread.sleep(2_000);
        assertThat(recordRepository.findByDiagnosticId(diagnosticId)).isPresent();
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dead_letter_records WHERE diagnostic_id = ?", Integer.class, diagnosticId);
        assertThat(rows).as("a redelivered dead letter must not duplicate the projection").isEqualTo(1);
        assertThat(record.getKafkaOffset()).isEqualTo(42L);
    }

    @Test
    void sinkReplayReentersAtTheSinkAndCannotDoubleCount() throws Exception {
        String ingestionId = UUID.randomUUID().toString();
        DataEvent event = new DataEvent("g06-replay-" + ingestionId, "g06-source", "g06_event",
                Instant.now().minusSeconds(10), Map.of("bid", 2.0));
        ProcessedEvent processed = new ProcessedEvent(event, UUID.fromString(ingestionId), Instant.now(),
                "REST", "LIVE", "hash-g06", "OK", "rules-test", "APPLIED",
                new WindowEvaluation("[]", null, null, WindowEvaluation.Outcome.INCLUDED, null, null),
                List.of());
        String diagnosticId = "ingestion:" + ingestionId;
        DltMessage message = new DltMessage(diagnosticId, DltStage.SINK, ingestionId, "g06-source", "g06_event",
                "DataIntegrityViolationException: db down", 4, KafkaTopics.QUALITY_EVENTS_V1, null, null,
                objectMapper.writeValueAsString(processed), Instant.now());
        dltTemplate.send(KafkaTopics.DEAD_LETTER_EVENTS, diagnosticId, message)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);

        DeadLetterRecordEntity record = awaitRecord(diagnosticId);
        assertThat(record.getStage()).isEqualTo(DltStage.SINK.name());

        // The management API exposes the record without secrets and records the replay attempt.
        mockMvc.perform(get("/api/v1/dead-letters/" + record.getId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostic_id").value(diagnosticId))
                .andExpect(jsonPath("$.stage").value("SINK"));

        mockMvc.perform(post("/api/v1/dead-letters/" + record.getId() + "/replay").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replay_attempt_id").isNotEmpty())
                .andExpect(jsonPath("$.outcome").value("PUBLISHED"));

        await().atMost(Duration.ofSeconds(60)).until(() -> rawRows(ingestionId) == 1);

        // A second replay keeps the same identity and must not add side effects.
        mockMvc.perform(post("/api/v1/dead-letters/" + record.getId() + "/replay").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted());
        Thread.sleep(3_000);
        assertThat(rawRows(ingestionId)).isEqualTo(1);
        Integer receipts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_receipts WHERE ingestion_id = ?", Integer.class, ingestionId);
        assertThat(receipts).isEqualTo(1);

        mockMvc.perform(get("/api/v1/dead-letters/" + record.getId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay_history.length()").value(2))
                .andExpect(jsonPath("$.status").value("REPLAYED"));

        mockMvc.perform(get("/api/v1/dead-letters?stage=SINK").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].diagnostic_id").isNotEmpty());
    }

    @Test
    void streamReplayReentersAtTheRawTopic() throws Exception {
        String ingestionId = UUID.randomUUID().toString();
        RawEnvelope envelope = new RawEnvelope(RawEnvelope.CONTRACT_VERSION, UUID.fromString(ingestionId),
                new DataEvent("g06-stream-replay-" + ingestionId, "g06-source", "g06_event",
                        Instant.now().minusSeconds(10), Map.of("bid", 3.0)),
                Instant.now(), RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, null);
        String diagnosticId = "ingestion:" + ingestionId;
        DltMessage message = new DltMessage(diagnosticId, DltStage.STREAM, ingestionId, "g06-source", "g06_event",
                "MALFORMED_RECORD: probe", 1, KafkaTopics.RAW_EVENTS_V1, 0, 7L,
                objectMapper.writeValueAsString(envelope), Instant.now());
        dltTemplate.send(KafkaTopics.DEAD_LETTER_EVENTS, diagnosticId, message)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);

        DeadLetterRecordEntity record = awaitRecord(diagnosticId);
        mockMvc.perform(post("/api/v1/dead-letters/" + record.getId() + "/replay").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("STREAM"));

        await().atMost(Duration.ofSeconds(60)).until(() -> rawRows(ingestionId) == 1);
        assertThat(rawRows(ingestionId)).isEqualTo(1);
    }
}
