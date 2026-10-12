package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.dlt.DltMessage;
import com.driftwatch.dlt.DltStage;
import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.DeadLetterRecordRepository;
import com.driftwatch.quality.AlertType;
import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regressions found by the functional audit: client-controlled identifiers and malformed or
 * conflicting records must take an explicit failure path without wedging a consumer, and valid
 * events with long field paths must persist.
 */
class PipelineHardeningIntegrationTest extends ContainerIntegrationTest {

    @Autowired KafkaTemplate<String, DltMessage> dltTemplate;
    @Autowired KafkaTemplate<String, ProcessedEvent> processedTemplate;
    @Autowired DeadLetterRecordRepository deadLetters;
    @Autowired MeterRegistry meterRegistry;

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void overLongDeadLetterIdentifiersDoNotWedgeTheProjection() throws Exception {
        String longId = "ingestion:" + UUID.randomUUID();
        DltMessage oversized = new DltMessage(longId, DltStage.SINK, longId.substring(10),
                "s".repeat(300), "t".repeat(300), "DataIntegrityViolationException: value too long", 4,
                KafkaTopics.QUALITY_EVENTS_V1, null, null, "{}", Instant.now());
        String nextId = "ingestion:" + UUID.randomUUID();
        DltMessage next = new DltMessage(nextId, DltStage.SINK, nextId.substring(10), "audit-source", "audit_event",
                "probe", 4, KafkaTopics.QUALITY_EVENTS_V1, null, null, "{}", Instant.now());
        dltTemplate.send(KafkaTopics.DEAD_LETTER_EVENTS, longId, oversized).get(10, java.util.concurrent.TimeUnit.SECONDS);
        dltTemplate.send(KafkaTopics.DEAD_LETTER_EVENTS, nextId, next).get(10, java.util.concurrent.TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(60)).until(() -> deadLetters.findByDiagnosticId(nextId).isPresent());
        assertThat(deadLetters.findByDiagnosticId(longId)).hasValueSatisfying(row ->
                assertThat(row.getSource()).hasSize(128));
    }

    @Test
    void envelopeMissingRequiredFieldsIsDeadLetteredInsteadOfStoppingTheStream() throws Exception {
        String badId = UUID.randomUUID().toString();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", "audit-missing-ts");
        event.put("source", "audit-source");
        event.put("event_type", "audit_event");
        event.put("payload", Map.of("bid", 1.0));
        String bad = objectMapper.writeValueAsString(Map.of("contract_version", 1, "ingestion_id", badId,
                "event", event, "received_at", Instant.now().toString(), "origin", "REST", "mode", "LIVE"));
        sendRaw("audit-missing", bad);

        await().atMost(Duration.ofSeconds(60)).until(() ->
                deadLetters.findByDiagnosticId("ingestion:" + badId).isPresent());
        assertThat(deadLetters.findByDiagnosticId("ingestion:" + badId).orElseThrow().getReason())
                .contains("MALFORMED_RECORD").contains("event.event_timestamp");

        // The stream thread is still alive: a later valid event is processed.
        String goodId = ingest(new DataEvent("audit-after-bad", "audit-source", "audit_event",
                Instant.now(), Map.of("bid", 2.0)));
        await().atMost(Duration.ofSeconds(60)).until(() -> rawEventRepository.findByIngestionId(goodId).isPresent());
    }

    @Test
    void longFieldPathsPersistAndAreCountedInTheAlertMetric() throws Exception {
        String type = "audit_long_" + UUID.randomUUID().toString().substring(0, 8);
        double driftBefore = alertCount(AlertType.SCHEMA_DRIFT);
        String first = ingest(new DataEvent("audit-long-1", "audit-long", type, Instant.now(),
                Map.of("ok", 1, "b".repeat(250), 1.0)));
        await().atMost(Duration.ofSeconds(60)).until(() -> rawEventRepository.findByIngestionId(first).isPresent());

        String longKey = "k".repeat(300);
        String second = ingest(new DataEvent("audit-long-2", "audit-long", type, Instant.now(),
                Map.of("ok", 1, "b".repeat(250), 2.0, longKey, 2)));
        await().atMost(Duration.ofSeconds(60)).until(() -> rawEventRepository.findByIngestionId(second).isPresent());

        List<String> fieldPaths = jdbcTemplate.queryForList(
                "SELECT field_path FROM quality_alerts WHERE event_type = ? AND alert_type = 'SCHEMA_DRIFT'",
                String.class, type);
        assertThat(fieldPaths).isNotEmpty().allSatisfy(path -> assertThat(path).hasSizeLessThanOrEqualTo(256));
        // The raw row can be visible before the sink has incremented the Micrometer counter.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(alertCount(AlertType.SCHEMA_DRIFT)).isGreaterThan(driftBefore));
    }

    @Test
    void conflictingRedeliveryTakesTheSinkFailurePath() throws Exception {
        UUID ingestionId = UUID.randomUUID();
        DataEvent event = new DataEvent("audit-conflict", "audit-source", "audit_event",
                Instant.now(), Map.of("bid", 3.0));
        ProcessedEvent original = new ProcessedEvent(event, ingestionId, Instant.now(), "REST", "LIVE",
                "hash-audit", "OK", "rules-test", "APPLIED",
                new WindowEvaluation("[]", null, null, WindowEvaluation.Outcome.INCLUDED, null, null), List.of());
        processedTemplate.send(KafkaTopics.QUALITY_EVENTS_V1, "audit-source", original).get(10, java.util.concurrent.TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(60)).until(() ->
                rawEventRepository.findByIngestionId(ingestionId.toString()).isPresent());

        ProcessedEvent conflict = new ProcessedEvent(event, ingestionId, Instant.now(), "REST", "LIVE",
                "hash-audit", "OK", "rules-test", "APPLIED",
                WindowEvaluation.notApplicable("[]", WindowEvaluation.Outcome.CONFLICT,
                        "INGESTION_ID_REUSED_WITH_DIFFERENT_CONTENT"), List.of());
        processedTemplate.send(KafkaTopics.QUALITY_EVENTS_V1, "audit-source", conflict).get(10, java.util.concurrent.TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(90)).until(() ->
                deadLetters.findByDiagnosticId("ingestion:" + ingestionId).isPresent());
        assertThat(deadLetters.findByDiagnosticId("ingestion:" + ingestionId).orElseThrow().getStage())
                .isEqualTo("SINK");
    }

    @Test
    void deadLetterPayloadKeepsAComplete256KibRecordAndCutsOnCharacterBoundaries() {
        String record = "x".repeat(300 * 1024);
        assertThat(DltMessage.truncatePayload(record)).isEqualTo(record);
        String multibyte = "é".repeat(DltMessage.MAX_PAYLOAD_BYTES);
        String cut = DltMessage.truncatePayload(multibyte);
        assertThat(cut).endsWith("...[truncated]");
        String kept = cut.substring(0, cut.length() - "...[truncated]".length());
        assertThat(kept.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(DltMessage.MAX_PAYLOAD_BYTES);
        assertThat(kept).matches("é+");
    }

    private double alertCount(AlertType type) {
        return meterRegistry.find("driftwatch_alerts_fired_total").tag("detector", type.name())
                .counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private String ingest(DataEvent event) throws Exception {
        String body = mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("ingestion_id").asText();
    }

    private void sendRaw(String key, String payload) throws Exception {
        java.util.Properties properties = new java.util.Properties();
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArraySerializer.class);
        try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<String, byte[]>(properties)) {
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(KafkaTopics.RAW_EVENTS_V1, key,
                    payload.getBytes(StandardCharsets.UTF_8))).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
