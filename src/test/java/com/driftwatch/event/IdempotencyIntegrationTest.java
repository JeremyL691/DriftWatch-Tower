package com.driftwatch.event;

import com.driftwatch.persistence.IngestionReceiptEntity;
import com.driftwatch.persistence.IngestionReceiptRepository;
import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G05: delivery identity and idempotency against the real pipeline.
 *
 * <ul>
 *   <li>an acknowledged publish whose response was lost replays with the same identity and does
 *       not add a second side effect</li>
 *   <li>the same Idempotency-Key with different content is rejected with 409</li>
 *   <li>two business duplicates keep two inputs and produce duplicate evidence</li>
 *   <li>a redelivered ProcessedEvent (DB committed, Kafka offset not) is skipped by the receipt</li>
 *   <li>concurrent requests with one key yield a single identity</li>
 * </ul>
 */
class IdempotencyIntegrationTest extends ContainerIntegrationTest {

    @Autowired
    IngestionReceiptRepository receiptRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String body(String eventId, double bid) {
        return """
                {"event_id":"%s","source":"g05-source","event_type":"g05_event",
                 "event_timestamp":"2026-10-01T00:00:00Z","payload":{"bid":%s}}
                """.formatted(eventId, bid);
    }

    private long rawRows(String ingestionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_events WHERE ingestion_id = ?", Integer.class, ingestionId);
        return count == null ? 0 : count;
    }

    private long processedReceipts(String ingestionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_receipts WHERE ingestion_id = ?", Integer.class, ingestionId);
        return count == null ? 0 : count;
    }

    @Test
    void replayedKeyAfterALostResponseKeepsOneIdentityAndOneSideEffect() throws Exception {
        String key = "g05-replay-" + System.nanoTime();

        String first = mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("replay-1", 1.0)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String ingestionId = objectMapper.readTree(first).get("ingestion_id").asText();

        // The client did not see the first response and retries the identical request.
        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("replay-1", 1.0)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ingestion_id").value(ingestionId))
                .andExpect(jsonPath("$.status").value("already_accepted"));

        await().atMost(Duration.ofSeconds(60))
                .until(() -> rawRows(ingestionId) == 1 && processedReceipts(ingestionId) == 1);
        assertThat(rawRows(ingestionId)).as("a replay must not create a second raw row").isEqualTo(1);
        assertThat(processedReceipts(ingestionId)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentContentIsRejected() throws Exception {
        String key = "g05-conflict-" + System.nanoTime();
        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("conflict-1", 1.0)))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("conflict-1", 2.0)))
                .andExpect(status().isConflict());
    }

    @Test
    void twoBusinessDuplicatesAreKeptAndFlagged() throws Exception {
        String eventId = "g05-business-dup-" + System.nanoTime();
        String first = mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON).content(body(eventId, 3.0)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON).content(body(eventId, 3.0)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).get("ingestion_id").asText();
        String secondId = objectMapper.readTree(second).get("ingestion_id").asText();
        assertThat(firstId).isNotEqualTo(secondId);

        await().atMost(Duration.ofSeconds(60)).until(() -> rawRows(firstId) == 1 && rawRows(secondId) == 1);
        assertThat(rawRows(firstId)).as("both business inputs are kept").isEqualTo(1);
        assertThat(rawRows(secondId)).isEqualTo(1);
        // The duplicate evidence is produced once the second record flows through the topology.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Integer duplicates = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM quality_alerts WHERE alert_type = 'DUPLICATE_EVENT' AND event_type = 'g05_event'",
                    Integer.class);
            assertThat(duplicates).isNotNull().isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void redeliveredProcessedEventIsSkippedByTheReceipt() throws Exception {
        // Simulates: the sink committed the transaction but the Kafka offset was not committed,
        // so the same record is delivered again after a restart.
        String ingestionId = java.util.UUID.randomUUID().toString();
        var event = new DataEvent("redelivery-1", "g05-source", "g05_event",
                Instant.parse("2026-10-01T00:05:00Z"), java.util.Map.of("bid", 5.0));
        var processed = new com.driftwatch.stream.ProcessedEvent(
                event, java.util.UUID.fromString(ingestionId), Instant.now(), "REST", "LIVE", "hash-1",
                "OK", "rules-test", "APPLIED",
                new com.driftwatch.stream.WindowEvaluation("[]", null, null,
                        com.driftwatch.stream.WindowEvaluation.Outcome.INCLUDED, null, null),
                java.util.List.of());

        sink.onProcessed(processed);
        long afterFirst = rawRows(ingestionId);
        sink.onProcessed(processed);

        assertThat(afterFirst).isEqualTo(1);
        assertThat(rawRows(ingestionId)).as("the redelivery must not add a second raw row").isEqualTo(1);
        assertThat(processedReceipts(ingestionId)).isEqualTo(1);
    }

    @Autowired
    com.driftwatch.stream.QualityEventSink sink;

    @Test
    void concurrentRequestsWithOneKeyYieldASingleIdentity() throws Exception {
        String key = "g05-concurrent-" + System.nanoTime();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> identities = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Void> task = () -> {
                start.await(5, TimeUnit.SECONDS);
                String response = mockMvc.perform(post("/api/v1/events").with(asIngest())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON).content(body("concurrent-1", 7.0)))
                        .andReturn().getResponse().getContentAsString();
                identities.add(objectMapper.readTree(response).get("ingestion_id").asText());
                return null;
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(identities).as("one key must resolve to exactly one identity").hasSize(1);
        String ingestionId = identities.iterator().next();
        await().atMost(Duration.ofSeconds(60)).until(() -> rawRows(ingestionId) >= 1);
        assertThat(rawRows(ingestionId)).isEqualTo(1);
    }
}
