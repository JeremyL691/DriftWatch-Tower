package com.driftwatch.api;

import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ManagementConfigurationIntegrationTest extends ContainerIntegrationTest {
    @Autowired ObjectMapper mapper;
    @Autowired com.driftwatch.config.RuntimeConfiguration runtime;
    @Autowired com.driftwatch.config.DriftwatchProperties properties;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.driftwatch.quality.FieldRangeDetector rangeDetector;
    @Autowired com.driftwatch.quality.FieldFormatDetector formatDetector;
    @Autowired com.driftwatch.quality.LateEventDetector lateDetector;
    @Autowired com.driftwatch.stream.TopologySettings topologySettings;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired com.driftwatch.stream.QualityStreamsTopology topology;
    @Autowired com.driftwatch.stream.StreamSerdes serdes;

    @org.junit.jupiter.api.AfterEach
    void restoreStartupSettings() throws Exception {
        ObjectNode current = state();
        ObjectNode restore = request(current);
        restore.set("config", current.get("startup_defaults"));
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(restore.toString())).andExpect(status().isOk());
        // These tests share the container with other startup profiles; revision zero is environment-owned.
        jdbcTemplate.update("DELETE FROM management_configuration_history");
        jdbcTemplate.update("UPDATE management_configuration SET revision = 0 WHERE id = 1");
    }

    private ObjectNode state() throws Exception {
        return (ObjectNode) mapper.readTree(mockMvc.perform(get("/api/v1/configuration").with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ObjectNode request(ObjectNode state) {
        ObjectNode request = mapper.createObjectNode();
        request.set("config", state.get("saved").deepCopy());
        request.set("expected_revision", state.get("saved_revision"));
        return request;
    }

    @Test void accessAndCsrfBoundaries() throws Exception {
        mockMvc.perform(get("/api/v1/configuration")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/configuration").with(asIngest())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/configuration/history").with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(request(state()).toString())).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/configuration").with(asIngest()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request(state()).toString()))
                .andExpect(status().isForbidden());
    }

    @Test void savesLiveAndPendingFieldsRejectsStaleWriteAndAuditsNoSecrets() throws Exception {
        ObjectNode before = state();
        ObjectNode request = request(before);
        ObjectNode config = (ObjectNode) request.get("config");
        double newThreshold = config.get("null_threshold").asDouble() == 0.7 ? 0.8 : 0.7;
        config.put("null_threshold", newThreshold);
        config.put("github_repository", "apache/flink");
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_revision").value(before.get("saved_revision").asLong() + 1))
                .andExpect(jsonPath("$.effective.null_threshold").value(newThreshold))
                .andExpect(jsonPath("$.effective.github_repository").value("apache/kafka"))
                .andExpect(jsonPath("$.saved.github_repository").value("apache/flink"))
                .andExpect(jsonPath("$.pending_restart_fields").value(org.hamcrest.Matchers.hasItem("github_repository")));
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isConflict());
        String history = mockMvc.perform(get("/api/v1/configuration/history").with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).contains("changed_fields", "null_threshold", "SAVED").doesNotContain(adminPassword, ingestToken, "token", "password", "base_url");
        assertThat(jdbcTemplate.queryForObject("select count(*) from management_configuration_history where revision = ?",
                Long.class, before.get("saved_revision").asLong() + 1)).isEqualTo(1);
    }

    @Test void committedRulesReachActualDetectorBeansAndThresholdConsumers() throws Exception {
        ObjectNode request = request(state());
        ObjectNode config = (ObjectNode) request.get("config");
        config.put("late_threshold_ms", 1000);
        config.put("github_late_threshold_ms", 2000);
        config.put("null_threshold", 0.75); config.put("null_min_samples", 7);
        config.put("anomaly_ratio", 4.5); config.put("anomaly_min_count", 9);
        config.set("ranges", mapper.readTree("{\"management_probe\":{\"min\":0,\"max\":10}}"));
        config.put("format_patterns", "management_code=^OK$");
        java.time.Instant now = java.time.Instant.now();
        var event = new com.driftwatch.event.DataEvent("management-probe", "settings-test", "probe",
                now.minusSeconds(2), java.util.Map.of("management_probe", 11, "management_code", "BAD"));
        var context = new com.driftwatch.quality.DetectionContext(event, "hash", now);
        assertThat(rangeDetector.detect(context)).isEmpty();
        assertThat(formatDetector.detect(context)).isEmpty();
        assertThat(lateDetector.detect(context)).isEmpty();
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isOk());
        assertThat(rangeDetector.detect(context)).extracting(com.driftwatch.quality.DraftAlert::type)
                .containsExactly(com.driftwatch.quality.AlertType.FIELD_OUT_OF_RANGE);
        assertThat(formatDetector.detect(context)).extracting(com.driftwatch.quality.DraftAlert::type)
                .containsExactly(com.driftwatch.quality.AlertType.FIELD_FORMAT_MISMATCH);
        assertThat(lateDetector.detect(context)).hasSize(1);
        var githubEvent = new com.driftwatch.event.DataEvent("management-github", "github:apache/kafka", "probe",
                now.minusSeconds(2), java.util.Map.of());
        assertThat(lateDetector.detect(new com.driftwatch.quality.DetectionContext(githubEvent, "hash", now))).isEmpty();
        var thresholds = topologySettings.forEvent();
        assertThat(thresholds.nullSpikeThreshold()).isEqualTo(0.75);
        assertThat(thresholds.nullSpikeMinSamples()).isEqualTo(7);
        assertThat(thresholds.anomalySpikeRatio()).isEqualTo(4.5);
        assertThat(thresholds.anomalyMinCurrentCount()).isEqualTo(9);
    }

    @Test void liveSaveChangesActualRunningStreamsProcessorsWithoutResettingFiredState() throws Exception {
        var builder = new org.apache.kafka.streams.StreamsBuilder();
        topology.apply(builder);
        java.util.Properties driverProperties = new java.util.Properties();
        driverProperties.put(org.apache.kafka.streams.StreamsConfig.APPLICATION_ID_CONFIG, "management-live-test");
        driverProperties.put(org.apache.kafka.streams.StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        try (var driver = new org.apache.kafka.streams.TopologyTestDriver(builder.build(), driverProperties)) {
            var input = driver.createInputTopic(com.driftwatch.config.KafkaTopics.RAW_EVENTS_V1,
                    new org.apache.kafka.common.serialization.StringSerializer(), new org.apache.kafka.common.serialization.ByteArraySerializer());
            var output = driver.createOutputTopic(com.driftwatch.config.KafkaTopics.QUALITY_EVENTS_V1,
                    new org.apache.kafka.common.serialization.StringDeserializer(), serdes.processedEventSerde().deserializer());
            var baselines = driver.createInputTopic(com.driftwatch.config.KafkaTopics.SCHEMA_BASELINES,
                    new org.apache.kafka.common.serialization.StringSerializer(), serdes.baselineMessageSerde().serializer());
            baselines.pipeInput("runtime_null", new com.driftwatch.quality.schema.BaselineMessage("runtime_null", 1L, java.util.Map.of("ask", "NUMBER")));
            java.time.Instant t = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            pipe(input, "n1", "runtime_null", t, 1);
            assertThat(output.readValue().alerts()).noneMatch(a -> a.type() == com.driftwatch.quality.AlertType.NULL_SPIKE);
            pipe(input, "a1", "runtime_anomaly", t.minusSeconds(120), 1);
            pipe(input, "a2", "runtime_anomaly", t.minusSeconds(60), 2);
            pipe(input, "a3", "runtime_anomaly", t, 3);
            pipe(input, "a4", "runtime_anomaly", t.plusSeconds(1), 4);
            assertThat(output.readValuesToList()).flatExtracting(com.driftwatch.stream.ProcessedEvent::alerts)
                    .noneMatch(a -> a.type() == com.driftwatch.quality.AlertType.ANOMALY_SPIKE);
            ObjectNode request = request(state());
            ObjectNode config = (ObjectNode) request.get("config");
            config.put("null_threshold", 0.9); config.put("null_min_samples", 1);
            config.put("anomaly_ratio", 2.5); config.put("anomaly_min_count", 3);
            mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isOk());
            pipe(input, "n2", "runtime_null", t.plusSeconds(2), 2);
            assertThat(output.readValue().alerts()).anyMatch(a -> a.type() == com.driftwatch.quality.AlertType.NULL_SPIKE);
            pipe(input, "a5", "runtime_anomaly", t.plusSeconds(2), 5);
            assertThat(output.readValue().alerts()).anyMatch(a -> a.type() == com.driftwatch.quality.AlertType.ANOMALY_SPIKE);
            pipe(input, "n3", "runtime_null", t.plusSeconds(3), 3);
            pipe(input, "a6", "runtime_anomaly", t.plusSeconds(3), 6);
            assertThat(output.readValuesToList()).flatExtracting(com.driftwatch.stream.ProcessedEvent::alerts)
                    .noneMatch(a -> a.type() == com.driftwatch.quality.AlertType.NULL_SPIKE || a.type() == com.driftwatch.quality.AlertType.ANOMALY_SPIKE);
        }
    }

    private void pipe(org.apache.kafka.streams.TestInputTopic<String, byte[]> input, String id, String type,
                      java.time.Instant timestamp, int value) throws Exception {
        var event = new com.driftwatch.event.DataEvent(id, "runtime-settings", type, timestamp, java.util.Map.of("nonce", value));
        var envelope = com.driftwatch.event.RawEnvelope.forRest(event, java.time.Instant.now());
        input.pipeInput(com.driftwatch.quality.ScopeKey.of(event.source(), type), mapper.writeValueAsBytes(envelope), timestamp.toEpochMilli());
    }

    @Test void historyFailureRollsBackDatabaseAndNeverPublishesSnapshot() throws Exception {
        var spyJdbc = org.mockito.Mockito.spy(jdbcTemplate);
        var local = new com.driftwatch.config.RuntimeConfiguration(properties, spyJdbc, transactionManager);
        var before = local.state();
        ObjectNode request = request(state());
        ((ObjectNode) request.get("config")).put("null_threshold", 0.9);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("forced audit failure"))
                .when(spyJdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO management_configuration_history"),
                        org.mockito.ArgumentMatchers.any(Object[].class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> local.save(request, "test-admin"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(local.state()).isEqualTo(before);
        assertThat(local.snapshot().config()).isEqualTo(before.effective());
    }

    @Test void concurrentIndependentWritersCannotLoseAnUpdate() throws Exception {
        var first = new com.driftwatch.config.RuntimeConfiguration(properties, jdbcTemplate, transactionManager);
        var second = new com.driftwatch.config.RuntimeConfiguration(properties, jdbcTemplate, transactionManager);
        ObjectNode request = request(state());
        ((ObjectNode) request.get("config")).put("null_threshold", 0.85);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> writeFirst = () -> {
                start.await();
                try { first.save(request, "first-admin"); return 200; }
                catch (org.springframework.web.server.ResponseStatusException e) { return e.getStatusCode().value(); }
            };
            java.util.concurrent.Callable<Integer> writeSecond = () -> {
                start.await();
                try { second.save(request, "second-admin"); return 200; }
                catch (org.springframework.web.server.ResponseStatusException e) { return e.getStatusCode().value(); }
            };
            var a = pool.submit(writeFirst); var b = pool.submit(writeSecond); start.countDown();
            assertThat(java.util.List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(state().get("saved_revision").asLong()).isEqualTo(request.get("expected_revision").asLong() + 1);
    }

    @Test void fullSpringRestartLoadsSavedSettingsBeforeConsumersAndKeepsEnvironmentSecrets() throws Exception {
        String startupNamespace = topologySettings.windowPolicyNamespace();
        ObjectNode request = request(state());
        ObjectNode config = (ObjectNode) request.get("config");
        config.put("github_repository", "apache/flink");
        config.put("github_enabled", false);
        config.put("window_size_ms", 120000);
        config.put("state_retention_ms", 1800000);
        config.put("null_threshold", 0.8);
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isOk());
        assertThat(topologySettings.metricsWindowSize()).isEqualTo(java.time.Duration.ofMinutes(1));
        assertThat(topologySettings.forEvent().windowPolicyNamespace()).isEqualTo(startupNamespace);
        var restartProperties = new java.util.HashMap<String, Object>();
        restartProperties.put("server.port", "0");
        restartProperties.put("spring.datasource.url", environment.getProperty("spring.datasource.url"));
        restartProperties.put("spring.datasource.username", environment.getProperty("spring.datasource.username"));
        restartProperties.put("spring.datasource.password", environment.getProperty("spring.datasource.password"));
        restartProperties.put("spring.kafka.bootstrap-servers", environment.getProperty("spring.kafka.bootstrap-servers"));
        restartProperties.put("spring.kafka.listener.auto-startup", "false");
        restartProperties.put("driftwatch.streams.enabled", "false");
        restartProperties.put("driftwatch.source.github.scheduler-enabled", "false");
        // System defaults have lower precedence than profile YAML: supply datasource values via property source.
        try (var restarted = new org.springframework.boot.builder.SpringApplicationBuilder(com.driftwatch.DriftWatchApplication.class)
                .profiles("test").initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.MapPropertySource("restart-test", restartProperties))).run()) {
            var applied = restarted.getBean(com.driftwatch.config.RuntimeConfiguration.class);
            assertThat(applied.state().pending_restart_fields()).isEmpty();
            assertThat(applied.state().effective().github_repository()).isEqualTo("apache/flink");
            assertThat(applied.startupProperties().security()).isEqualTo(properties.security());
            assertThat(applied.startupProperties().source().github().token()).isEqualTo(properties.source().github().token());
            assertThat(restarted.getBean(com.driftwatch.stream.TopologySettings.class).metricsWindowSize()).isEqualTo(java.time.Duration.ofMinutes(2));
            assertThat(restarted.getBean(com.driftwatch.stream.TopologySettings.class).windowPolicyNamespace()).isNotEqualTo(startupNamespace);
            assertThat(restarted.getBean(com.driftwatch.quality.window.MetricWindowService.class).increment("restart-test", "probe", "count", java.time.Instant.parse("2026-10-10T00:01:00Z"), 1).windowStart())
                    .isEqualTo(java.time.Instant.parse("2026-10-10T00:00:00Z"));
            assertThat(restarted.getBeansOfType(com.driftwatch.source.github.GithubPoller.class)).hasSize(1);
        }
    }

    @Test void legacySavedHistoryLoadsVerbatimButNewPutRequiresCoupledFullSpanHistory() throws Exception {
        ObjectNode legacy = (ObjectNode) state().get("saved").deepCopy();
        legacy.put("anomaly_baseline_windows", 3);
        legacy.put("anomaly_min_history_windows", 1);
        jdbcTemplate.update("UPDATE management_configuration SET revision = 1, config = ?::jsonb WHERE id = 1", legacy.toString());
        var restarted = new com.driftwatch.config.RuntimeConfiguration(properties, jdbcTemplate, transactionManager);
        assertThat(restarted.state().saved().anomaly_min_history_windows()).isEqualTo(1);
        assertThat(restarted.startupProperties().detector().anomalySpike().minHistoryWindows()).isEqualTo(1);
        assertThat(mapper.readTree(jdbcTemplate.queryForObject("SELECT config::text FROM management_configuration WHERE id = 1", String.class))).isEqualTo(legacy);
        long history = jdbcTemplate.queryForObject("SELECT count(*) FROM management_configuration_history", Long.class);
        ObjectNode request = request(state());
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_configuration"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("anomaly_min_history_windows must equal anomaly_baseline_windows")));
        assertThat(state().get("saved_revision").asLong()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM management_configuration_history", Long.class)).isEqualTo(history);
        ((ObjectNode) request.get("config")).put("anomaly_min_history_windows", 3);
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isOk());
    }

    @Test void invalidWritesLeaveSavedAndHistoryUntouched() throws Exception {
        ObjectNode before = state();
        long history = jdbcTemplate.queryForObject("select count(*) from management_configuration_history", Long.class);
        String[] fields = {"null_threshold", "anomaly_ratio", "github_repository", "github_max_pages", "state_retention_ms", "format_patterns",
                "github_poll_interval_ms", "github_page_size", "anomaly_min_history_windows", "duplicate_window_ms", "github_enabled", "null_min_samples", "ranges", "late_threshold_ms", "window_size_ms"};
        Object[] values = {0.0, "NaN", "bad/repo/extra", 11, 1, "sku=[",
                299999, 101, 1001, 1, "true", 1.5, java.util.Map.of("probe", java.util.Map.of("min", "Infinity", "max", 5)), null, 500};
        for (int i = 0; i < fields.length; i++) {
            ObjectNode request = request(before);
            ((ObjectNode) request.get("config")).set(fields[i], mapper.valueToTree(values[i]));
            mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                    .andExpect(status().isBadRequest());
        }
        // "github:" + this name would not fit the 64 character source column of every collected event.
        ObjectNode longRepository = request(before);
        ((ObjectNode) longRepository.get("config")).put("github_repository", "owner/" + "r".repeat(52));
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(longRepository.toString()))
                .andExpect(status().isBadRequest());
        ObjectNode secret = request(before);
        ((ObjectNode) secret.get("config")).put("token", "must-not-be-stored");
        mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(secret.toString())).andExpect(status().isBadRequest());
        assertThat(state().get("saved_revision")).isEqualTo(before.get("saved_revision"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from management_configuration_history", Long.class)).isEqualTo(history);
    }

    @Test void putOmittingRequiredFieldsIsBadRequestAndLeavesRevisionUntouched() throws Exception {
        ObjectNode before = state();
        long history = jdbcTemplate.queryForObject("select count(*) from management_configuration_history", Long.class);
        for (String field : java.util.List.of("github_enabled", "null_threshold")) {
            ObjectNode request = request(before);
            ((ObjectNode) request.get("config")).remove(field);
            mockMvc.perform(put("/api/v1/configuration").with(asAdmin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                    .andExpect(status().isBadRequest());
        }
        ObjectNode after = state();
        assertThat(after.get("saved_revision")).isEqualTo(before.get("saved_revision"));
        assertThat(after.get("saved")).isEqualTo(before.get("saved"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from management_configuration_history", Long.class)).isEqualTo(history);
    }
}
