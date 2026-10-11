package com.driftwatch.api;

import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AlertManagementIntegrationTest extends ContainerIntegrationTest {
    @Test void combinesAllFiltersAndBoundsPagination() throws Exception {
        seed("wanted", AlertType.LATE_EVENT, "OPEN");
        seed("other", AlertType.LATE_EVENT, "OPEN");
        seed("wanted", AlertType.DUPLICATE_EVENT, "OPEN");
        seed("wanted", AlertType.LATE_EVENT, "RESOLVED");
        mockMvc.perform(get("/api/v1/alerts?status=OPEN&source=wanted&type=LATE_EVENT").with(asAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].source").value("wanted"));
        mockMvc.perform(get("/api/v1/alerts?size=101").with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/alerts?page=-1").with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/alerts?status=MADE_UP").with(asAdmin())).andExpect(status().isBadRequest());
    }

    @Test void unfilteredAndTypeOnlyListsSucceedAndUnknownTypeIsBadRequest() throws Exception {
        seed("wanted", AlertType.LATE_EVENT, "OPEN");
        mockMvc.perform(get("/api/v1/alerts").with(asAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/alerts?type=LATE_EVENT").with(asAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/alerts?type=BOGUS").with(asAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("invalid value for parameter 'type'"));
    }

    private void seed(String source, AlertType type, String status) {
        QualityAlertEntity alert = new QualityAlertEntity();
        alert.setSource(source); alert.setAlertType(type); alert.setStatus(status);
        alert.setEventType("probe"); alert.setMessage("probe"); alert.setSeverity(Severity.INFO);
        alert.setEvidenceJson(JsonNodeFactory.instance.objectNode()); alert.setCreatedAt(Instant.now());
        qualityAlertRepository.save(alert);
    }
}
