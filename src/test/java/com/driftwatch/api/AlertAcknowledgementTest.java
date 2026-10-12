package com.driftwatch.api;

import com.driftwatch.persistence.AlertIncidentRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.driftwatch.source.AlertIncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acknowledge attribution and lifecycle timestamps. The real AlertIncidentService runs against
 * mocked repositories with a clock that carries nanoseconds, the same shape the database rounds.
 */
class AlertAcknowledgementTest {

    /** Nanosecond clock; the stored instant must be truncated to microseconds. */
    private static final Instant CLOCK = Instant.parse("2026-10-11T08:00:00.193835638Z");
    private static final Instant STORED = Instant.parse("2026-10-11T08:00:00.193835Z");

    private QualityAlertRepository alertRepository;
    private QualityAlertEntity alert;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        alertRepository = mock(QualityAlertRepository.class);
        AlertIncidentRepository incidentRepository = mock(AlertIncidentRepository.class);
        AlertIncidentService service = new AlertIncidentService(
                incidentRepository, alertRepository, Clock.fixed(CLOCK, ZoneOffset.UTC));

        alert = new QualityAlertEntity();
        alert.setAlertType(AlertType.LATE_EVENT);
        alert.setSeverity(Severity.WARN);
        alert.setSource("demo");
        alert.setEventType("orders");
        alert.setMessage("late");
        alert.setStatus(QualityAlertEntity.STATUS_OPEN);
        alert.setCreatedAt(Instant.parse("2026-10-11T07:00:00Z"));

        when(alertRepository.existsById(1L)).thenReturn(true);
        when(alertRepository.findById(1L)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(QualityAlertEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc = MockMvcBuilders.standaloneSetup(new AlertController(alertRepository, service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void acknowledgeWithoutABodyRecordsTheAuthenticatedPrincipal() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/1/acknowledge").principal(() -> "dashboard-admin")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        assertThat(alert.getAcknowledgedBy()).isEqualTo("dashboard-admin");
    }

    @Test
    void anExplicitAcknowledgedByStillWins() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/1/acknowledge").principal(() -> "dashboard-admin")
                        .contentType("application/json").content("{\"acknowledgedBy\":\"ops-oncall\"}"))
                .andExpect(status().isOk());

        assertThat(alert.getAcknowledgedBy()).isEqualTo("ops-oncall");
    }

    @Test
    void aBlankAcknowledgedByFallsBackToThePrincipal() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/1/acknowledge").principal(() -> "dashboard-admin")
                        .contentType("application/json").content("{\"acknowledgedBy\":\"  \"}"))
                .andExpect(status().isOk());

        assertThat(alert.getAcknowledgedBy()).isEqualTo("dashboard-admin");
    }

    @Test
    void withoutAnyIdentityTheAcknowledgerIsAnonymous() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/1/acknowledge")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        assertThat(alert.getAcknowledgedBy()).isEqualTo("anonymous");
    }

    @Test
    void acknowledgedAtIsStoredAtMicrosecondPrecisionAndRepeatKeepsIt() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/1/acknowledge").principal(() -> "dashboard-admin")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        assertThat(alert.getAcknowledgedAt()).isEqualTo(STORED);

        mockMvc.perform(post("/api/v1/alerts/1/acknowledge").principal(() -> "someone-else")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        assertThat(alert.getAcknowledgedAt()).isEqualTo(STORED);
        assertThat(alert.getAcknowledgedBy()).as("the first acknowledger is kept").isEqualTo("dashboard-admin");
    }

    @Test
    void resolvedAtIsStoredAtMicrosecondPrecision() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/1/resolve")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        assertThat(alert.getResolvedAt()).isEqualTo(STORED);
    }
}
