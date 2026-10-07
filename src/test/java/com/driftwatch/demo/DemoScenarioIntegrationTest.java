package com.driftwatch.demo;

import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.support.ContainerIntegrationTest;
import com.driftwatch.quality.AlertType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoScenarioIntegrationTest extends ContainerIntegrationTest {


    @Test
    void duplicateEventsScenarioCreatesDuplicateAlerts() throws Exception {
        long before = qualityAlertRepository.count();

        mockMvc.perform(post("/api/v1/demo/run-scenario/duplicate-events").with(asAdmin()).with(csrf()))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<QualityAlertEntity> recent = qualityAlertRepository.findAllByOrderByCreatedAtDesc(
                    org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
            assertThat(recent).anyMatch(a -> a.getAlertType() == AlertType.DUPLICATE_EVENT);
            assertThat(qualityAlertRepository.count()).isGreaterThan(before);
        });
    }

    @Test
    void overlappingDuplicateSignalsPersistWithoutDeadLetter() throws Exception {
        String source = "duplicate-overlap-" + UUID.randomUUID();
        String repeatedEventId = "event-a-" + UUID.randomUUID();
        String otherEventId = "event-b-" + UUID.randomUUID();
        long openDeadLettersBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dead_letter_records WHERE recovery_state = 'OPEN'", Long.class);

        ingest(repeatedEventId, source, 1.0, 2.0);
        ingest(otherEventId, source, 3.0, 4.0);
        String overlappingIngestionId = ingest(repeatedEventId, source, 3.0, 4.0);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<QualityAlertEntity> duplicateAlerts = qualityAlertRepository
                    .findByIngestionIdOrderByCreatedAtAsc(overlappingIngestionId).stream()
                    .filter(alert -> alert.getAlertType() == AlertType.DUPLICATE_EVENT)
                    .toList();

            assertThat(rawEventRepository.findByIngestionId(overlappingIngestionId)).isPresent();
            assertThat(duplicateAlerts).hasSize(2);
            assertThat(duplicateAlerts)
                    .extracting(alert -> alert.getEvidenceJson().path("duplicate_kind").asText())
                    .containsExactlyInAnyOrder("REPEATED_EVENT_ID", "REPEATED_PAYLOAD");
            assertThat(duplicateAlerts.stream().map(QualityAlertEntity::getDetectorKey).distinct())
                    .hasSize(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM dead_letter_records WHERE recovery_state = 'OPEN'", Long.class))
                    .isEqualTo(openDeadLettersBefore);
        });
    }

    private String ingest(String eventId, String source, double bid, double ask) throws Exception {
        String body = """
                {"event_id":"%s","source":"%s","event_type":"market_tick",
                 "event_timestamp":"%s","payload":{"symbol":"BTC/USDT","bid":%s,"ask":%s}}
                """.formatted(eventId, source, Instant.now(), bid, ask);
        MvcResult response = mockMvc.perform(post("/api/v1/events")
                        .with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        return new ObjectMapper().readTree(response.getResponse().getContentAsString())
                .path("ingestion_id").asText();
    }

    @Test
    void lateEventsScenarioCreatesLateAlert() throws Exception {
        long before = qualityAlertRepository.count();

        mockMvc.perform(post("/api/v1/demo/run-scenario/late-events").with(asAdmin()).with(csrf()))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<QualityAlertEntity> recent = qualityAlertRepository.findAllByOrderByCreatedAtDesc(
                    org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
            assertThat(recent).anyMatch(a -> a.getAlertType() == AlertType.LATE_EVENT);
            assertThat(qualityAlertRepository.count()).isGreaterThan(before);
        });
    }

    @Test
    void normalFlowScenarioStaysAlertFreeForItsFreshSchema() throws Exception {
        long alertsBefore = qualityAlertRepository.count();

        mockMvc.perform(post("/api/v1/demo/run-scenario/normal-flow").with(asAdmin()).with(csrf()))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(rawEventRepository.count()).isGreaterThanOrEqualTo(4);
            assertThat(qualityAlertRepository.count()).isEqualTo(alertsBefore);
        });
    }

    @Test
    void staleSourceScenarioCreatesStaleAlertAndHealthRow() throws Exception {
        long before = qualityAlertRepository.count();

        mockMvc.perform(post("/api/v1/demo/run-scenario/stale-source").with(asAdmin()).with(csrf()))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<QualityAlertEntity> recent = qualityAlertRepository.findAllByOrderByCreatedAtDesc(
                    org.springframework.data.domain.PageRequest.of(0, 20)).getContent();
            assertThat(recent).anyMatch(a -> a.getAlertType() == AlertType.STALE_SOURCE);
            assertThat(sourceHealthRepository.findAllByOrderByHealthScoreAscSourceAsc())
                    .anyMatch(row -> "STALE".equals(row.getStatus()));
            assertThat(qualityAlertRepository.count()).isGreaterThan(before);
        });
    }
}
