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
}
