package com.driftwatch.demo;

import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.support.ContainerIntegrationTest;
import com.driftwatch.quality.AlertType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoScenarioIntegrationTest extends ContainerIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void duplicateEventsScenarioCreatesDuplicateAlerts() throws Exception {
        long before = qualityAlertRepository.count();

        mockMvc.perform(post("/demo/run-scenario/duplicate-events"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<QualityAlertEntity> recent = qualityAlertRepository.findAllByOrderByCreatedAtDesc(
                    org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
            assertThat(recent).anyMatch(a -> a.getAlertType() == AlertType.DUPLICATE_EVENT);
            assertThat(qualityAlertRepository.count()).isGreaterThan(before);
        });
    }

    @Test
    void lateEventsScenarioCreatesLateAlert() throws Exception {
        long before = qualityAlertRepository.count();

        mockMvc.perform(post("/demo/run-scenario/late-events"))
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

        mockMvc.perform(post("/demo/run-scenario/normal-flow"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(rawEventRepository.count()).isGreaterThanOrEqualTo(4);
            assertThat(qualityAlertRepository.count()).isEqualTo(alertsBefore);
        });
    }

    @Test
    void staleSourceScenarioCreatesStaleAlertAndHealthRow() throws Exception {
        long before = qualityAlertRepository.count();

        mockMvc.perform(post("/demo/run-scenario/stale-source"))
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
