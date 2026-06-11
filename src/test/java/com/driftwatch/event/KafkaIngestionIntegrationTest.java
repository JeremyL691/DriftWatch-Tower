package com.driftwatch.event;

import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KafkaIngestionIntegrationTest extends ContainerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void postedEventFlowsThroughKafkaAndIsPersisted() throws Exception {
        String eventId = "evt-" + UUID.randomUUID();
        // Use a per-test event_type so schema-drift detection doesn't pick up
        // baselines created by other integration tests against the same database.
        String eventType = "kafka_ingest_smoke_" + UUID.randomUUID().toString().replace("-", "");
        DataEvent event = new DataEvent(
                eventId, "binance", eventType,
                Instant.now(),
                Map.of("symbol", "BTC/USDT", "bid", 108000.1, "trace", UUID.randomUUID().toString())
        );

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(rawEventRepository.existsByEventId(eventId)).isTrue());

        var stored = rawEventRepository.findAllByOrderByReceivedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 50)).getContent().stream()
                .filter(e -> e.getEventId().equals(eventId))
                .findFirst().orElseThrow();
        assertThat(stored.getQualityStatus()).isEqualTo("OK");
    }
}
