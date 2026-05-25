package com.driftwatch.api;

import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertController.class)
class AlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QualityAlertRepository repository;

    @Test
    void listUsesCombinedFilterWhenTypeAndSourceAreBothProvided() throws Exception {
        QualityAlertEntity alert = alert(AlertType.SCHEMA_DRIFT, "demo-api");
        when(repository.findByAlertTypeAndSourceOrderByCreatedAtDesc(
                eq(AlertType.SCHEMA_DRIFT), eq("demo-api"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(alert)));

        mockMvc.perform(get("/alerts")
                        .param("type", "SCHEMA_DRIFT")
                        .param("source", "demo-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alert_type").value("SCHEMA_DRIFT"))
                .andExpect(jsonPath("$[0].source").value("demo-api"));

        verify(repository).findByAlertTypeAndSourceOrderByCreatedAtDesc(
                eq(AlertType.SCHEMA_DRIFT), eq("demo-api"), any(PageRequest.class));
    }

    private static QualityAlertEntity alert(AlertType type, String source) {
        QualityAlertEntity entity = new QualityAlertEntity();
        entity.setAlertType(type);
        entity.setSeverity(Severity.WARN);
        entity.setSource(source);
        entity.setEventType("market_tick");
        entity.setFieldPath("payload.ask");
        entity.setMessage("test");
        entity.setEvidenceJson(JsonNodeFactory.instance.objectNode().put("ok", true));
        entity.setCreatedAt(Instant.parse("2026-05-25T00:00:00Z"));
        return entity;
    }
}
