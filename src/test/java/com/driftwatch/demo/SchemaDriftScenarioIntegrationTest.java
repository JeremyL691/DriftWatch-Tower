package com.driftwatch.demo;

import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.SchemaVersionRepository;
import com.driftwatch.quality.AlertType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = {"raw-events"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.group-id=test-driftwatch-schema"
})
class SchemaDriftScenarioIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired QualityAlertRepository alertRepository;
    @Autowired SchemaVersionRepository schemaRepository;

    @Test
    void schemaDriftScenarioRegistersBothSchemasAndAlerts() throws Exception {
        mockMvc.perform(post("/demo/run-scenario/schema-drift"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            var versions = schemaRepository.findByEventTypeOrderByFirstSeenAtAsc("demo_schema_event");
            assertThat(versions).hasSizeGreaterThanOrEqualTo(2);
            assertThat(versions).extracting("status").contains("ACTIVE", "DRIFTING");

            var alerts = alertRepository.findAllByOrderByCreatedAtDesc(
                    org.springframework.data.domain.PageRequest.of(0, 20)).getContent();
            assertThat(alerts).anyMatch(a -> a.getAlertType() == AlertType.SCHEMA_DRIFT);
        });
    }
}
