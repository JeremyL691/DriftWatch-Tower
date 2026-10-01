package com.driftwatch.event;

import com.driftwatch.persistence.IngestionReceiptEntity;
import com.driftwatch.persistence.IngestionReceiptRepository;
import com.driftwatch.support.ContainerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G05, part one: a publish that never reached the broker must be reported as unconfirmed
 * (503) while the reserved identity survives, so a retry with the same Idempotency-Key keeps
 * exactly one identity and later confirms it. The producer is mocked here because the real
 * broker is healthy; the rest of G05 runs against the real pipeline.
 */
class PreAckFailureTest extends ContainerIntegrationTest {

    @MockitoBean
    RawEventProducer producer;

    @Autowired
    IngestionReceiptRepository receiptRepository;

    @Test
    void unconfirmedPublishReturns503AndRetryKeepsTheSameIdentity() throws Exception {
        String key = "g05-preack-" + System.nanoTime();
        String body = """
                {"event_id":"preack-1","source":"g05-source","event_type":"g05_event",
                 "event_timestamp":"2026-10-01T00:00:00Z","payload":{"bid":1.0}}
                """;

        when(producer.publishAndAwait(any())).thenReturn(false);
        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());

        IngestionReceiptEntity pending = receiptRepository
                .findById(new IngestionReceiptEntity.Key("g05-source", "g05_event", key))
                .orElseThrow();
        assertThat(pending.getPublishState()).as("the identity stays unconfirmed")
                .isNotEqualTo(IngestionReceiptEntity.STATE_CONFIRMED);
        String reservedIdentity = pending.getIngestionId();

        when(producer.publishAndAwait(any())).thenReturn(true);
        mockMvc.perform(post("/api/v1/events").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ingestion_id").value(reservedIdentity));

        assertThat(receiptRepository
                .findById(new IngestionReceiptEntity.Key("g05-source", "g05_event", key))
                .orElseThrow().getPublishState()).isEqualTo(IngestionReceiptEntity.STATE_CONFIRMED);
    }

    @Test
    void aRetriedBatchResendsOnlyTheUnconfirmedItemsAndCompletes() throws Exception {
        // Guide 4.2: the batch receipt keeps per-item state and a retry sends only what is not
        // confirmed. Reporting the same unconfirmed items forever would never complete the batch.
        String key = "g05-batch-preack-" + System.nanoTime();
        String batch = """
                [{"event_id":"batch-a","source":"g05-batch","event_type":"g05_batch_event",
                  "event_timestamp":"2026-10-01T00:00:00Z","payload":{"bid":1.0}},
                 {"event_id":"batch-b","source":"g05-batch","event_type":"g05_batch_event",
                  "event_timestamp":"2026-10-01T00:00:01Z","payload":{"bid":2.0}}]
                """;

        when(producer.publishAndAwait(any())).thenReturn(true, false);
        mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isMultiStatus());

        IngestionReceiptEntity first = receiptRepository
                .findById(new IngestionReceiptEntity.Key("g05-batch", "g05_batch_event", key))
                .orElseThrow();
        // One row covers every item of this (source, event_type) in the batch, so the per-item
        // truth is in batch_items while the row state summarises them.
        assertThat(first.isBatch()).isTrue();
        var items = first.getBatchItems().path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(items.get(1).path("status").asText()).isEqualTo("FAILED");
        assertThat(first.getPublishState()).isEqualTo(IngestionReceiptEntity.STATE_FAILED);
        String firstIdentity = items.get(0).path("ingestion_id").asText();

        // The retry re-sends the unconfirmed item with its reserved identity.
        when(producer.publishAndAwait(any())).thenReturn(true);
        mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isAccepted());

        IngestionReceiptEntity after = receiptRepository
                .findById(new IngestionReceiptEntity.Key("g05-batch", "g05_batch_event", key))
                .orElseThrow();
        var afterItems = after.getBatchItems().path("items");
        assertThat(afterItems.get(0).path("ingestion_id").asText())
                .as("the confirmed item keeps its original identity across the retry")
                .isEqualTo(firstIdentity);
        assertThat(afterItems.get(1).path("status").asText())
                .as("the retry confirmed the item that was still unconfirmed")
                .isEqualTo("CONFIRMED");
        assertThat(after.getPublishState()).isEqualTo(IngestionReceiptEntity.STATE_CONFIRMED);
    }

    @Test
    void aBatchOverTheTotalSizeLimitIsRejectedWithoutPublishing() throws Exception {
        // Guide 4.2: the batch is bounded by item count and by 4 MiB of request payload.
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) body.append(",");
            body.append("{\"event_id\":\"big-").append(i)
                .append("\",\"source\":\"g05-big\",\"event_type\":\"g05_big_event\",")
                .append("\"event_timestamp\":\"2026-10-01T00:00:00Z\",\"payload\":{\"blob\":\"")
                .append("x".repeat(250 * 1024)).append("\"}}");
        }
        body.append("]");

        mockMvc.perform(post("/api/v1/events/batch").with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
    }
}
