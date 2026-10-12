package com.driftwatch.api;

import com.driftwatch.persistence.MetricWindowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetricWindowControllerTest {

    private final MetricWindowRepository repository = mock(MetricWindowRepository.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MetricWindowController(repository))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void sizeAboveTheMaximumIsRejectedWithoutQuerying() throws Exception {
        mockMvc.perform(get("/api/v1/metrics/windows").param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        verifyNoInteractions(repository);
    }

    @Test
    void zeroSizeAndNegativePageAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/metrics/windows").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/metrics/windows").param("page", "-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test
    void defaultPageIsOneHundredRowsAndTheMaximumIsAccepted() throws Exception {
        when(repository.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/metrics/windows")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/metrics/windows").param("size", "100")).andExpect(status().isOk());

        verify(repository, times(2)).search(null, null, null, PageRequest.of(0, 100));
    }
}
