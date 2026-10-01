package com.driftwatch.source;

import com.driftwatch.persistence.AlertIncidentEntity;
import com.driftwatch.persistence.AlertIncidentRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.driftwatch.support.ContainerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G10, incident half (execution guide, section 7.2): correlation into incidents, concurrent
 * correlation producing one incident, resolve semantics, and side-effect-free reads.
 */
class IncidentLifecycleIntegrationTest extends ContainerIntegrationTest {

    @Autowired
    AlertIncidentService incidentService;
    @Autowired
    AlertIncidentRepository incidentRepository;
    @Autowired
    QualityAlertRepository alertRepository;

    private QualityAlertEntity alert(AlertType type, Severity severity, String source, String eventType) {
        QualityAlertEntity entity = new QualityAlertEntity();
        entity.setAlertType(type);
        entity.setSeverity(severity);
        entity.setSource(source);
        entity.setEventType(eventType);
        entity.setMessage(type + " on " + source);
        entity.setStatus(QualityAlertEntity.STATUS_OPEN);
        entity.setCreatedAt(Instant.now());
        entity.setEvidenceJson(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("probe", "phase-p5"));
        return alertRepository.save(entity);
    }

    @Test
    void nonInfoAlertsCorrelateIntoOneIncidentAndInfoAlertsDoNot() {
        String source = "incident-src-" + System.nanoTime();
        QualityAlertEntity first = alert(AlertType.LATE_EVENT, Severity.WARN, source, "orders");
        QualityAlertEntity second = alert(AlertType.SCHEMA_DRIFT, Severity.WARN, source, "orders");

        var incident = incidentService.correlate(first).orElseThrow();
        var sameIncident = incidentService.correlate(second).orElseThrow();

        assertThat(sameIncident.getId()).isEqualTo(incident.getId());
        assertThat(alertRepository.findById(second.getId()).orElseThrow().getIncidentId())
                .isEqualTo(incident.getId());

        QualityAlertEntity info = alert(AlertType.DUPLICATE_EVENT, Severity.INFO, source, "orders");
        assertThat(incidentService.correlate(info)).isEmpty();
        assertThat(alertRepository.findById(info.getId()).orElseThrow().getIncidentId()).isNull();
    }

    @Test
    void concurrentCorrelationCreatesExactlyOneIncident() throws Exception {
        String source = "incident-concurrent-" + System.nanoTime();
        List<QualityAlertEntity> alerts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            alerts.add(alert(AlertType.LATE_EVENT, Severity.WARN, source, "orders"));
        }
        Set<Long> incidentIds = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(alerts.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (QualityAlertEntity entity : alerts) {
            Callable<Void> task = () -> {
                start.await(5, TimeUnit.SECONDS);
                incidentService.correlate(entity).ifPresent(incident -> incidentIds.add(incident.getId()));
                return null;
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(incidentIds).as("one incident per scope under concurrency").hasSize(1);
        Integer incidents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alert_incidents WHERE source = ?", Integer.class, source);
        assertThat(incidents).isEqualTo(1);
    }

    @Test
    void resolvingAnIncidentResolvesItsUnresolvedAlerts() throws Exception {
        String source = "incident-resolve-" + System.nanoTime();
        QualityAlertEntity first = alert(AlertType.LATE_EVENT, Severity.WARN, source, "orders");
        QualityAlertEntity second = alert(AlertType.NULL_SPIKE, Severity.WARN, source, "orders");
        var incident = incidentService.correlate(first).orElseThrow();
        incidentService.correlate(second);

        mockMvc.perform(post("/api/v1/incidents/" + incident.getId() + "/resolve").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootCause\":\"upstream fix\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        assertThat(alertRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(QualityAlertEntity.STATUS_RESOLVED);
        assertThat(alertRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(QualityAlertEntity.STATUS_RESOLVED);
        assertThat(alertRepository.findById(second.getId()).orElseThrow().getRootCause())
                .isEqualTo("upstream fix");
    }

    @Test
    void resolvingTheLastAlertAutoResolvesTheIncident() throws Exception {
        String source = "incident-auto-" + System.nanoTime();
        QualityAlertEntity only = alert(AlertType.LATE_EVENT, Severity.WARN, source, "orders");
        var incident = incidentService.correlate(only).orElseThrow();

        mockMvc.perform(post("/api/v1/alerts/" + only.getId() + "/resolve").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootCause\":\"handled\"}"))
                .andExpect(status().isOk());

        AlertIncidentEntity refreshed = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AlertIncidentEntity.STATUS_RESOLVED);
        assertThat(refreshed.getResolvedAt()).isNotNull();
    }

    @Test
    void acknowledgeIsIdempotentAndIllegalTransitionsAreRejected() throws Exception {
        String source = "incident-ack-" + System.nanoTime();
        QualityAlertEntity alert = alert(AlertType.LATE_EVENT, Severity.WARN, source, "orders");

        mockMvc.perform(post("/api/v1/alerts/" + alert.getId() + "/acknowledge").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgedBy\":\"ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        Instant firstAck = alertRepository.findById(alert.getId()).orElseThrow().getAcknowledgedAt();

        mockMvc.perform(post("/api/v1/alerts/" + alert.getId() + "/acknowledge").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgedBy\":\"ops-again\"}"))
                .andExpect(status().isOk());
        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getAcknowledgedAt())
                .as("repeating an acknowledge keeps the original timestamp")
                .isEqualTo(firstAck);

        mockMvc.perform(post("/api/v1/alerts/" + alert.getId() + "/resolve").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        Instant resolvedAt = alertRepository.findById(alert.getId()).orElseThrow().getResolvedAt();

        mockMvc.perform(post("/api/v1/alerts/" + alert.getId() + "/resolve").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getResolvedAt()).isEqualTo(resolvedAt);

        mockMvc.perform(post("/api/v1/alerts/" + alert.getId() + "/acknowledge").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void dashboardAndHealthReadsHaveNoSideEffects() throws Exception {
        long alertsBefore = alertRepository.count();
        long incidentsBefore = incidentRepository.count();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/sources/health").with(asAdmin())).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/dashboard/api/summary").with(asAdmin())).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/alerts?size=5").with(asAdmin())).andExpect(status().isOk());
        }

        assertThat(alertRepository.count()).as("reads must not add alerts").isEqualTo(alertsBefore);
        assertThat(incidentRepository.count()).as("reads must not add incidents").isEqualTo(incidentsBefore);
    }
}
