package com.driftwatch.demo;

import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.quality.AlertType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = {"raw-events"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.group-id=test-driftwatch-demo"
})
class DemoScenarioIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired QualityAlertRepository alertRepository;

    @Test
    void duplicateEventsScenarioCreatesDuplicateAlerts() throws Exception {
        long before = alertRepository.count();

        mockMvc.perform(post("/demo/run-scenario/duplicate-events"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<QualityAlertEntity> recent = alertRepository.findAllByOrderByCreatedAtDesc(
                    org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
            assertThat(recent).anyMatch(a -> a.getAlertType() == AlertType.DUPLICATE_EVENT);
            assertThat(alertRepository.count()).isGreaterThan(before);
        });
    }

    @Test
    void lateEventsScenarioCreatesLateAlert() throws Exception {
        long before = alertRepository.count();

        mockMvc.perform(post("/demo/run-scenario/late-events"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<QualityAlertEntity> recent = alertRepository.findAllByOrderByCreatedAtDesc(
                    org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
            assertThat(recent).anyMatch(a -> a.getAlertType() == AlertType.LATE_EVENT);
            assertThat(alertRepository.count()).isGreaterThan(before);
        });
    }
}
