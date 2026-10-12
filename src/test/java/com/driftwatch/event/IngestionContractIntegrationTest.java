package com.driftwatch.event;

import com.driftwatch.persistence.IngestionReceiptEntity;
import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ingest contract regressions against the real database and MVC stack. The producer is mocked so
 * "nothing was published" is checked exactly; the receipts are real rows.
 */
class IngestionContractIntegrationTest extends ContainerIntegrationTest {

    private static final String TIMESTAMP = "2026-10-01T00:00:00Z";

    @MockitoBean
    RawEventProducer producer;

    @Autowired
    RequestDigests digests;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String eventJson(String eventId, String source, String eventType, String payloadJson) {
        return """
                {"event_id":"%s","source":"%s","event_type":"%s","event_timestamp":"%s","payload":%s}
                """.formatted(eventId, source, eventType, TIMESTAMP, payloadJson);
    }

    /** A payload JSON whose container depth is exactly {@code depth}. */
    private static String payloadJsonOfDepth(int depth) {
        String json = "{\"leaf\":1}";
        for (int level = 2; level <= depth; level++) {
            json = "{\"child\":" + json + "}";
        }
        return json;
    }

    private static String uniqueKey(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private long receiptRows(String source, String eventType, String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ingestion_receipts WHERE source = ? AND event_type = ? AND idempotency_key = ?",
                Integer.class, source, eventType, key);
        return count == null ? 0 : count;
    }

    @Test
    void aSingleEventNestedBeyondTwentyLevelsIsRejectedBeforePublishing() throws Exception {
        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("deep-single", "contract-src", "contract_x", payloadJsonOfDepth(25))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        verify(producer, never()).publishAndAwait(any());
    }

    @Test
    void aBatchWithATooDeepItemPublishesNothing() throws Exception {
        String batch = "[" + eventJson("ok-batch", "contract-src", "contract_x", "{\"bid\":1.0}") + ","
                + eventJson("deep-batch", "contract-src", "contract_x", payloadJsonOfDepth(25)) + "]";

        mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest());

        verify(producer, never()).publishAndAwait(any());
    }

    @Test
    void anOverlongIdempotencyKeyIsRejectedUpFront() throws Exception {
        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", "k".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("long-key", "contract-src", "contract_x", "{\"bid\":1.0}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        verify(producer, never()).publishAndAwait(any());
    }

    @Test
    void aBlankIdempotencyKeyIsRejectedUpFront() throws Exception {
        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("blank-key", "contract-src", "contract_x", "{\"bid\":1.0}")))
                .andExpect(status().isBadRequest());

        verify(producer, never()).publishAndAwait(any());
    }

    @Test
    void aKeyOfExactly128CharactersIsAccepted() throws Exception {
        when(producer.publishAndAwait(any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", "k".repeat(128))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("max-key", "contract-src", "contract_x", "{\"bid\":1.0}")))
                .andExpect(status().isAccepted());
    }

    @Test
    void aNullBatchElementIsRejectedWithoutPublishing() throws Exception {
        String batch = "[" + eventJson("ok-null", "contract-src", "contract_x", "{\"bid\":1.0}") + ",null]";

        mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("events[1]")));

        verify(producer, never()).publishAndAwait(any());
    }

    @Test
    void anOverlongSourceIsRejectedAsValidationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("long-source", "s".repeat(300), "contract_x", "{\"bid\":1.0}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("source")));

        verify(producer, never()).publishAndAwait(any());
    }

    @Test
    void aBatchItemFailingValidationNamesTheFieldAndPublishesNothing() throws Exception {
        String batch = "[" + eventJson("ok-item", "contract-src", "contract_x", "{\"bid\":1.0}") + ","
                + eventJson("bad-item", "contract-src", "t".repeat(65), "{\"bid\":2.0}") + "]";

        mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("eventType")));

        verify(producer, never()).publishAndAwait(any());
    }

    @Test
    void aKeyedBatchConflictingWithAnEarlierReceiptPublishesNothingNew() throws Exception {
        String key = uniqueKey("batch-conflict");
        when(producer.publishAndAwait(any())).thenReturn(true);
        String first = "[" + eventJson("conf-a", "contract-src", "contract_x", "{\"bid\":1.0}") + "]";
        mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isAccepted());

        // [B(typeY), A'(typeX)] reuses the key of [A(typeX)]: B must not be published before the 409.
        String second = "[" + eventJson("conf-b", "contract-src", "contract_y", "{\"bid\":2.0}") + ","
                + eventJson("conf-a", "contract-src", "contract_x", "{\"bid\":9.0}") + "]";
        mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict"));

        verify(producer, times(1)).publishAndAwait(any());
        assertThat(receiptRows("contract-src", "contract_y", key))
                .as("no receipt is written for the item that was never published").isZero();
    }

    @Test
    void anExpiredSingleReceiptNoLongerBindsTheKey() throws Exception {
        String key = uniqueKey("stale-single");
        DataEvent event = new DataEvent("stale-1", "contract-src", "contract_x",
                Instant.parse(TIMESTAMP), Map.of("bid", 1.0));
        String staleIdentity = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                        INSERT INTO ingestion_receipts (source, event_type, idempotency_key, request_digest,
                            ingestion_id, event_id, publish_state, batch, batch_items, expires_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, false, NULL, ?, ?)
                        """,
                "contract-src", "contract_x", key, digests.of(event), staleIdentity, "stale-1",
                IngestionReceiptEntity.STATE_CONFIRMED,
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))),
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))));
        when(producer.publishAndAwait(any())).thenReturn(true);

        String response = mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("stale-1", "contract-src", "contract_x", "{\"bid\":1.0}")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andReturn().getResponse().getContentAsString();

        String freshIdentity = objectMapper.readTree(response).get("ingestion_id").asText();
        assertThat(freshIdentity).isNotEqualTo(staleIdentity);
        verify(producer, times(1)).publishAndAwait(any());
        String stored = jdbcTemplate.queryForObject(
                "SELECT ingestion_id FROM ingestion_receipts WHERE source = ? AND event_type = ? AND idempotency_key = ?",
                String.class, "contract-src", "contract_x", key);
        assertThat(stored).isEqualTo(freshIdentity);
        Boolean fresh = jdbcTemplate.queryForObject(
                "SELECT expires_at > now() FROM ingestion_receipts WHERE idempotency_key = ?", Boolean.class, key);
        assertThat(fresh).as("the replacement receipt has a new 24 hour window").isTrue();
    }

    @Test
    void anExpiredBatchReceiptDoesNotConflictOrReplay() throws Exception {
        String key = uniqueKey("stale-batch");
        String staleIdentity = UUID.randomUUID().toString();
        String staleItems = "{\"batch_id\":\"old\",\"items\":[{\"index\":0,\"event_id\":\"stale-b\","
                + "\"ingestion_id\":\"" + staleIdentity + "\",\"status\":\"CONFIRMED\"}]}";
        jdbcTemplate.update("""
                        INSERT INTO ingestion_receipts (source, event_type, idempotency_key, request_digest,
                            ingestion_id, event_id, publish_state, batch, batch_items, expires_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, true, ?::jsonb, ?, ?)
                        """,
                "contract-src", "contract_batch", key, "0".repeat(64), staleIdentity, "stale-b",
                IngestionReceiptEntity.STATE_CONFIRMED, staleItems,
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))),
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))));
        when(producer.publishAndAwait(any())).thenReturn(true);

        // The stored digest differs from this batch, so only the expiry lets it through.
        String batch = "[" + eventJson("stale-b", "contract-src", "contract_batch", "{\"bid\":1.0}") + "]";
        mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.accepted[0].ingestion_id").value(org.hamcrest.Matchers.not(staleIdentity)));

        verify(producer, times(1)).publishAndAwait(any());
    }

    @Test
    void theTrailingSlashDashboardPathServesTheDashboardPage() throws Exception {
        mockMvc.perform(get("/dashboard/").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/dashboard/index.html"));
    }

    @Test
    void aMissingDashboardFileIsNotFoundWithAJsonError() throws Exception {
        mockMvc.perform(get("/dashboard/no-such-file.js").with(asAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void anUnnegotiableResponseIsNotAcceptableNotServerError() throws Exception {
        // A JSON map cannot be written as text/plain, so the framework raises 406 while writing.
        mockMvc.perform(get("/api/v1/alerts/stats").with(asAdmin()).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
    }
}
