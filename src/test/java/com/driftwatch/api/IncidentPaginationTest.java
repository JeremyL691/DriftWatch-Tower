package com.driftwatch.api;

import com.driftwatch.persistence.AlertIncidentEntity;
import com.driftwatch.persistence.AlertIncidentRepository;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.source.AlertIncidentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentPaginationTest {

    private final AlertIncidentRepository incidents = mock(AlertIncidentRepository.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new IncidentController(
                    incidents, mock(QualityAlertRepository.class), mock(AlertIncidentService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void defaultsToTheFirstHundredNewestFirstWithAnIdTiebreak() throws Exception {
        when(incidents.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidents).findAll(captor.capture());
        Pageable page = captor.getValue();
        assertThat(page.getPageNumber()).isZero();
        assertThat(page.getPageSize()).isEqualTo(100);
        assertThat(page.getSort().toList()).containsExactly(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    void pageAndSizeSelectTheRequestedWindow() throws Exception {
        when(incidents.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/incidents").param("page", "2").param("size", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidents).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void invalidPagingIsRejectedWithoutQuerying() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        mockMvc.perform(get("/api/v1/incidents").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/incidents").param("page", "-1"))
                .andExpect(status().isBadRequest());
        verify(incidents, never()).findAll(any(Pageable.class));
    }

    @Test
    void theResponseRemainsAPlainArrayOfIncidents() throws Exception {
        AlertIncidentEntity incident = new AlertIncidentEntity();
        incident.setTitle("LATE_EVENT on demo");
        incident.setDescription("auto-correlated");
        incident.setStatus(AlertIncidentEntity.STATUS_OPEN);
        incident.setSource("demo");
        incident.setEventType("orders");
        incident.setCreatedAt(java.time.Instant.parse("2026-10-11T08:00:00Z"));
        Page<AlertIncidentEntity> page = new PageImpl<>(List.of(incident));
        when(incidents.findAll(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].title").value("LATE_EVENT on demo"));
    }
}
