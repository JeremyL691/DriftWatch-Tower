package com.driftwatch.event;

import com.driftwatch.persistence.IngestionReceiptEntity;
import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Keyed batch reservation (execution guide 4.2): an Idempotency-Key on a batch reserves one identity
 * per item before anything is published. Concurrent requests with one key share those identities,
 * and a different batch under the same key is a 409 with nothing of it published. Runs against the
 * real Postgres and Kafka pipeline.
 */
class KeyedBatchReservationIntegrationTest extends ContainerIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    RequestDigests digests;

    private record Outcome(int status, String body) {}

    private static String item(String eventId, String source, String eventType, double bid) {
        return """
                {"event_id":"%s","source":"%s","event_type":"%s",
                 "event_timestamp":"2026-10-01T00:00:00Z","payload":{"bid":%s}}
                """.formatted(eventId, source, eventType, bid);
    }

    private Outcome postBatch(String key, String batch) throws Exception {
        var response = mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andReturn().getResponse();
        return new Outcome(response.getStatus(), response.getContentAsString());
    }

    /** Posts one body per thread, all released at the same instant, under one key. */
    private List<Outcome> raceBatches(String key, List<String> bodies) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(bodies.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (String body : bodies) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return postBatch(key, body);
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(90, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<String> acceptedField(Outcome outcome, String field) throws Exception {
        List<String> values = new ArrayList<>();
        for (JsonNode accepted : objectMapper.readTree(outcome.body()).path("accepted")) {
            values.add(accepted.path(field).asText());
        }
        return values;
    }

    private long rawRows(String ingestionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_events WHERE ingestion_id = ?", Integer.class, ingestionId);
        return count == null ? 0 : count;
    }

    private long rawRowsForEvent(String source, String eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_events WHERE source = ? AND event_id = ?", Integer.class, source, eventId);
        return count == null ? 0 : count;
    }

    private long rawRowsForSource(String source) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_events WHERE source = ?", Integer.class, source);
        return count == null ? 0 : count;
    }

    private long deadLetters(String ingestionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dead_letter_records WHERE ingestion_id = ?", Integer.class, ingestionId);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentSameKeySameContentBatchesShareOneIdentityPerItem() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String source = "g06-same-" + suffix;
        String key = "g06-same-key-" + suffix;
        String batch = "[" + item("same-" + suffix + "-1", source, "g06_a", 1.0)
                + "," + item("same-" + suffix + "-2", source, "g06_b", 2.0)
                + "," + item("same-" + suffix + "-3", source, "g06_a", 3.0) + "]";

        List<Outcome> outcomes = raceBatches(key, Collections.nCopies(6, batch));

        Set<List<String>> identityLists = new HashSet<>();
        for (Outcome outcome : outcomes) {
            assertThat(outcome.status()).as(outcome.body()).isEqualTo(202);
            assertThat(acceptedField(outcome, "event_id")).containsExactly(
                    "same-" + suffix + "-1", "same-" + suffix + "-2", "same-" + suffix + "-3");
            identityLists.add(acceptedField(outcome, "ingestion_id"));
        }
        assertThat(identityLists).as("every request sees the same identity for each item").hasSize(1);
        List<String> identities = identityLists.iterator().next();
        assertThat(identities).hasSize(3).doesNotHaveDuplicates();

        Integer receipts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ingestion_receipts WHERE source = ? AND idempotency_key = ?",
                Integer.class, source, key);
        assertThat(receipts).as("one receipt row per distinct (source, event_type)").isEqualTo(2);

        await().atMost(Duration.ofSeconds(60)).until(() -> identities.stream().allMatch(id -> rawRows(id) == 1));
        // Every duplicate publish of an item is a redelivery of the same identity: give the sink time
        // to see them all before counting.
        Thread.sleep(3_000);
        for (String identity : identities) {
            assertThat(rawRows(identity)).as("exactly one raw row per item identity").isEqualTo(1);
            assertThat(deadLetters(identity)).as("a same-identity redelivery is not dead-lettered").isZero();
        }
        assertThat(rawRowsForSource(source)).as("each item is stored once").isEqualTo(3);
    }

    @Test
    void concurrentSameKeyDifferentContentNeverPublishesTheLoser() throws Exception {
        String source = "g06-race-" + System.nanoTime();
        List<String> winningEventIds = new ArrayList<>();
        List<String> losingEventIds = new ArrayList<>();
        int rounds = 3;
        for (int round = 0; round < rounds; round++) {
            String suffix = round + "-" + System.nanoTime();
            String key = "g06-race-key-" + suffix;
            String prefixX = "x-" + suffix;
            String prefixY = "y-" + suffix;
            String contentX = "[" + item(prefixX + "-1", source, "g06_a", 1.0)
                    + "," + item(prefixX + "-2", source, "g06_b", 1.5) + "]";
            String contentY = "[" + item(prefixY + "-1", source, "g06_a", 2.0)
                    + "," + item(prefixY + "-2", source, "g06_b", 2.5) + "]";
            List<String> bodies = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                bodies.add(i % 2 == 0 ? contentX : contentY);
            }

            List<Outcome> outcomes = raceBatches(key, bodies);

            List<List<String>> acceptedEventIds = new ArrayList<>();
            for (Outcome outcome : outcomes) {
                if (outcome.status() == 202) {
                    acceptedEventIds.add(acceptedField(outcome, "event_id"));
                } else {
                    assertThat(outcome.status()).as(outcome.body()).isEqualTo(409);
                    assertThat(objectMapper.readTree(outcome.body()).path("error").asText())
                            .isEqualTo("idempotency_conflict");
                }
            }
            assertThat(acceptedEventIds).as("one content wins the key").isNotEmpty();
            assertThat(new HashSet<>(acceptedEventIds)).as("every 202 carries the winning content").hasSize(1);
            List<String> winner = acceptedEventIds.get(0);
            List<String> loser;
            if (winner.equals(List.of(prefixX + "-1", prefixX + "-2"))) {
                loser = List.of(prefixY + "-1", prefixY + "-2");
            } else {
                assertThat(winner).isEqualTo(List.of(prefixY + "-1", prefixY + "-2"));
                loser = List.of(prefixX + "-1", prefixX + "-2");
            }
            winningEventIds.addAll(winner);
            losingEventIds.addAll(loser);
        }

        await().atMost(Duration.ofSeconds(60))
                .until(() -> winningEventIds.stream().allMatch(id -> rawRowsForEvent(source, id) >= 1));
        // Give a losing publish, had there been one, time to reach the sink before asserting absence.
        Thread.sleep(3_000);
        for (String eventId : losingEventIds) {
            assertThat(rawRowsForEvent(source, eventId))
                    .as("the losing content of a conflicting key is never persisted: %s", eventId)
                    .isZero();
        }
    }

    @Test
    void aRetriedAcceptedKeyedBatchKeepsItsIdentitiesAndAddsNoRows() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String source = "g06-retry-" + suffix;
        String key = "g06-retry-key-" + suffix;
        String batch = "[" + item("retry-" + suffix + "-1", source, "g06_a", 1.0)
                + "," + item("retry-" + suffix + "-2", source, "g06_b", 2.0) + "]";

        Outcome first = postBatch(key, batch);
        assertThat(first.status()).as(first.body()).isEqualTo(202);
        List<String> identities = acceptedField(first, "ingestion_id");
        assertThat(identities).hasSize(2);

        Outcome retry = postBatch(key, batch);
        assertThat(retry.status()).as(retry.body()).isEqualTo(202);
        assertThat(objectMapper.readTree(retry.body()).path("status").asText()).isEqualTo("accepted");
        assertThat(acceptedField(retry, "ingestion_id")).as("a retry keeps each item's identity")
                .isEqualTo(identities);

        await().atMost(Duration.ofSeconds(60)).until(() -> identities.stream().allMatch(id -> rawRows(id) == 1));
        Thread.sleep(2_000);
        for (String identity : identities) {
            assertThat(rawRows(identity)).isEqualTo(1);
        }
        assertThat(rawRowsForSource(source)).as("the retry adds no raw rows").isEqualTo(2);
        Integer confirmedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ingestion_receipts WHERE source = ? AND idempotency_key = ? "
                        + "AND publish_state = 'CONFIRMED'",
                Integer.class, source, key);
        assertThat(confirmedRows).as("both receipt rows are confirmed").isEqualTo(2);
    }

    @Test
    void aLegacyPartialBatchReceiptKeepsItsReservedItemAndCompletesTheRest() throws Exception {
        // A row written by the earlier code may hold only some of its items. The retry must keep the
        // identity that item 0 already reserved and give item 2 (missing from the row) a fresh one.
        String suffix = String.valueOf(System.nanoTime());
        String source = "g06-legacy-" + suffix;
        String key = "g06-legacy-key-" + suffix;
        String x = "legacy-" + suffix + "-1";
        String y = "legacy-" + suffix + "-2";
        String z = "legacy-" + suffix + "-3";
        String batch = "[" + item(x, source, "g06_a", 1.0)
                + "," + item(y, source, "g06_b", 2.0)
                + "," + item(z, source, "g06_a", 3.0) + "]";

        // The same batch digest the service computes for these three items.
        String digest = digests.sha256(List.of(
                digests.of(new DataEvent(x, source, "g06_a", Instant.parse("2026-10-01T00:00:00Z"), Map.of("bid", 1.0))),
                digests.of(new DataEvent(y, source, "g06_b", Instant.parse("2026-10-01T00:00:00Z"), Map.of("bid", 2.0))),
                digests.of(new DataEvent(z, source, "g06_a", Instant.parse("2026-10-01T00:00:00Z"), Map.of("bid", 3.0)))));
        String legacyIdentity = UUID.randomUUID().toString();
        String received = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS).toString();
        String legacyItems = "{\"batch_id\":\"legacy\",\"items\":[{\"index\":0,\"event_id\":\"" + x
                + "\",\"ingestion_id\":\"" + legacyIdentity + "\",\"received_at\":\"" + received
                + "\",\"status\":\"FAILED\"}]}";
        jdbcTemplate.update("""
                        INSERT INTO ingestion_receipts (source, event_type, idempotency_key, request_digest,
                            ingestion_id, event_id, publish_state, batch, batch_items, expires_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, true, ?::jsonb, ?, ?)
                        """,
                source, "g06_a", key, digest, legacyIdentity, x, IngestionReceiptEntity.STATE_FAILED, legacyItems,
                Timestamp.from(Instant.now().plus(Duration.ofHours(1))),
                Timestamp.from(Instant.now().minusSeconds(60)));

        Outcome outcome = postBatch(key, batch);
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(202);
        assertThat(acceptedField(outcome, "event_id")).containsExactly(x, y, z);
        List<String> identities = acceptedField(outcome, "ingestion_id");
        assertThat(identities.get(0)).as("the item the legacy row reserved keeps its identity")
                .isEqualTo(legacyIdentity);
        assertThat(identities).doesNotHaveDuplicates();

        await().atMost(Duration.ofSeconds(60)).until(() -> identities.stream().allMatch(id -> rawRows(id) == 1));
        for (String identity : identities) {
            assertThat(deadLetters(identity)).isZero();
        }

        String storedText = jdbcTemplate.queryForObject(
                "SELECT batch_items::text FROM ingestion_receipts WHERE source = ? AND event_type = ? "
                        + "AND idempotency_key = ?",
                String.class, source, "g06_a", key);
        JsonNode stored = objectMapper.readTree(storedText).path("items");
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).path("ingestion_id").asText()).isEqualTo(legacyIdentity);
        assertThat(stored.get(1).path("ingestion_id").asText()).isEqualTo(identities.get(2));
        assertThat(stored.get(0).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(stored.get(1).path("status").asText()).isEqualTo("CONFIRMED");
    }
}
