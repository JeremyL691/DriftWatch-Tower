package com.driftwatch.demo;

import com.driftwatch.support.ContainerIntegrationTest;
import com.driftwatch.quality.AlertType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchemaDriftScenarioIntegrationTest extends ContainerIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void schemaDriftScenarioRegistersBothSchemasAndAlerts() throws Exception {
        mockMvc.perform(post("/demo/run-scenario/schema-drift"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            var versions = schemaVersionRepository.findByEventTypeOrderByFirstSeenAtAsc("demo_schema_event");
            assertThat(versions).hasSizeGreaterThanOrEqualTo(2);
            assertThat(versions).extracting("status").contains("ACTIVE", "DRIFTING");

            var alerts = qualityAlertRepository.findAllByOrderByCreatedAtDesc(
                    org.springframework.data.domain.PageRequest.of(0, 20)).getContent();
            assertThat(alerts).anyMatch(a -> a.getAlertType() == AlertType.SCHEMA_DRIFT);
        });
    }
}
