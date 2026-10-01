package com.driftwatch.api;

import com.driftwatch.support.ContainerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access protection contract: unauthenticated management access is rejected, the ingest token
 * can only ingest, browser mutations require a CSRF token, and the public health endpoint leaks
 * no details. Readiness reflects Kafka and Kafka Streams, not only the database.
 */
class SecurityIntegrationTest extends ContainerIntegrationTest {

    @Test
    void anonymousManagementAccessIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/events/recent")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicHealthReturnsStatusOnly() throws Exception {
        // Wait until the application is genuinely ready; readiness includes Kafka and Streams.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                mockMvc.perform(get("/actuator/health"))
                        .andExpect(status().isOk()));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void adminSeesHealthDetailsAndManagementEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").exists());
        mockMvc.perform(get("/api/v1/alerts").with(asAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void readinessIncludesKafkaAndStreams() throws Exception {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                mockMvc.perform(get("/actuator/health/readiness").with(asAdmin()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.components.kafka.status").value("UP"))
                        .andExpect(jsonPath("$.components.streams.status").value("UP"))
                        .andExpect(jsonPath("$.components.db.status").value("UP")));
    }

    @Test
    void ingestTokenCanPostEventsButNotManage() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event_id":"security-ingest-1","source":"security-test",
                                 "event_type":"security_probe","event_timestamp":"2026-10-01T00:00:00Z",
                                 "payload":{"bid":1.0}}
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/alerts").with(asIngest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/demo/run-scenario/normal-flow").with(asIngest()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidBearerTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event_id":"security-ingest-2","source":"security-test",
                                 "event_type":"security_probe","event_timestamp":"2026-10-01T00:00:00Z",
                                 "payload":{"bid":1.0}}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminBasicIngestIsAllowedWithoutBearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event_id":"security-ingest-3","source":"security-test",
                                 "event_type":"security_probe","event_timestamp":"2026-10-01T00:00:00Z",
                                 "payload":{"bid":1.0}}
                                """))
                .andExpect(status().isAccepted());
    }

    @Test
    void browserMutationRequiresCsrfToken() throws Exception {
        // Without a CSRF token the mutation is rejected.
        mockMvc.perform(post("/api/v1/alerts/999999/resolve")
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootCause\":\"probe\"}"))
                .andExpect(status().isForbidden());

        // With a valid token the request passes CSRF and reaches the handler (404: unknown alert).
        // The browser-readable XSRF-TOKEN cookie is verified over real HTTP in the compose
        // acceptance run, where MockMvc's request/response reuse cannot interfere.
        mockMvc.perform(post("/api/v1/alerts/999999/resolve")
                        .with(asAdmin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rootCause\":\"probe\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ingestEndpointDoesNotRequireCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event_id":"security-ingest-4","source":"security-test",
                                 "event_type":"security_probe","event_timestamp":"2026-10-01T00:00:00Z",
                                 "payload":{"bid":1.0}}
                                """))
                .andExpect(status().isAccepted());
    }

    @Test
    void malformedJsonIsAClientError() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"not json\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_payload"));
    }

    @Test
    void invalidEventFieldsAreRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .with(asIngest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event_id":"","source":"security-test",
                                 "event_type":"security_probe","event_timestamp":"2026-10-01T00:00:00Z",
                                 "payload":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

}
