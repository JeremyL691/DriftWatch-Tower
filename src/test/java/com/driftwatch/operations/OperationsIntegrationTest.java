package com.driftwatch.operations;

import com.driftwatch.support.ContainerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate G11: retention rules and the metrics surface (execution guide, sections 7.3 and 7.4).
 *
 * <p>Retention must remove aged raw/metric/resolved rows in bounded batches while never touching
 * evidence that is still needed: unresolved alerts, open incidents, unrecovered dead letters,
 * pending outbox rows, collector state and schema versions. A receipt is only pruned once its raw
 * row is gone, so a replay can never find a receipt without its data.
 */
class OperationsIntegrationTest extends ContainerIntegrationTest {

    @Autowired
    RetentionService retentionService;
    @Autowired
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private Instant now = Instant.now();

    /**
     * Guide 5.3: coverage must make the difference between "no rule fired" and "the window
     * evaluated this" visible, including events whose baseline-dependent checks were skipped
     * because no ACTIVE baseline existed.
     */
    @Test
    void coverageSeparatesEvaluatedExcludedAndBaselineMissingEvents() throws Exception {
        Instant received = now.minusSeconds(60);
        // Four distinct states: evaluated with a baseline, excluded from the window, backfilled
        // with no evaluation at all, and evaluated but with the baseline-dependent checks skipped.
        insertEvaluated("cov-included", received, "INCLUDED", "APPLIED");
        insertEvaluated("cov-expired", received, "EXPIRED", "APPLIED");
        insertEvaluated("cov-bootstrap", received, "SKIPPED_MODE", null);
        insertEvaluated("cov-no-baseline", received, "INCLUDED", "PENDING");

        String json = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/events/coverage")
                                .param("hours", "1")
                                .with(asAdmin()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});

        assertThat(number(body, "total")).isEqualTo(4);
        assertThat(number(body, "included")).isEqualTo(2);
        Map<?, ?> excluded = (Map<?, ?>) body.get("excluded");
        assertThat(((Number) excluded.get("expired")).longValue()).isEqualTo(1);
        assertThat(((Number) excluded.get("skipped_mode")).longValue()).isEqualTo(1);
        assertThat(number(body, "excluded_total")).isEqualTo(2);
        assertThat(((Number) body.get("included_ratio")).doubleValue()).isBetween(0.49d, 0.51d);
        Map<?, ?> baseline = (Map<?, ?>) body.get("baseline");
        assertThat(((Number) baseline.get("applied")).longValue()).isEqualTo(2);
        assertThat(((Number) baseline.get("pending")).longValue()).isEqualTo(1);
    }

    private static long number(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private void insertEvaluated(String ingestionId, Instant received, String outcome, String baselineStatus) {
        jdbcTemplate.update("""
                        INSERT INTO raw_events (event_id, source, event_type, event_timestamp, received_at,
                                                payload_json, payload_hash, quality_status, ingestion_id,
                                                window_evaluation, baseline_status)
                        VALUES (?, 'cov-src', 'cov_event', ?, ?, '{}'::jsonb, 'hash', 'OK', ?,
                                jsonb_build_object('outcome', ?), ?)
                        """,
                ingestionId, Timestamp.from(received), Timestamp.from(received), ingestionId,
                outcome, baselineStatus);
    }

    private long rawRow(String ingestionId, String eventId, Instant receivedAt) {
        jdbcTemplate.update("""
                        INSERT INTO raw_events (event_id, source, event_type, event_timestamp, received_at,
                                                payload_json, payload_hash, quality_status, ingestion_id)
                        VALUES (?, 'ops-src', 'ops_event', ?, ?, '{}'::jsonb, 'hash', 'OK', ?)
                        """,
                eventId, Timestamp.from(receivedAt), Timestamp.from(receivedAt), ingestionId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM raw_events WHERE ingestion_id = ?", Long.class, ingestionId);
    }

    private long alert(String status, Instant resolvedAt) {
        jdbcTemplate.update("""
                        INSERT INTO quality_alerts (alert_type, severity, source, event_type, message,
                                                    evidence_json, created_at, status, resolved_at)
                        VALUES ('LATE_EVENT', 'WARN', 'ops-src', 'ops_event', 'probe', '{}'::jsonb, ?, ?, ?)
                        """,
                Timestamp.from(now.minusSeconds(3600)), status,
                resolvedAt == null ? null : Timestamp.from(resolvedAt));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM quality_alerts WHERE message = 'probe' ORDER BY id DESC LIMIT 1", Long.class);
    }

    private long deadLetter(String state, Instant recoveredAt) {
        jdbcTemplate.update("""
                        INSERT INTO dead_letter_records (diagnostic_id, stage, source, event_type, reason,
                                                         attempts, recovery_state, created_at, recovered_at)
                        VALUES (?, 'SINK', 'ops-src', 'ops_event', 'probe', 4, ?, ?, ?)
                        """,
                "ops-probe-" + System.nanoTime(), state, Timestamp.from(now.minusSeconds(7200)),
                recoveredAt == null ? null : Timestamp.from(recoveredAt));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dead_letter_records WHERE diagnostic_id LIKE 'ops-probe-%' ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    @Test
    void retentionRemovesAgedRowsButKeepsEvidenceStillNeeded() {
        Instant old = now.minus(java.time.Duration.ofDays(120));

        // Aged raw rows: one plain, one protected by a pending outbox row, one protected by an open dead letter.
        rawRow("ops-plain", "ops-plain", old);
        rawRow("ops-pending-outbox", "ops-pending-outbox", old);
        rawRow("ops-open-dlt", "ops-open-dlt", old);
        jdbcTemplate.update("""
                        INSERT INTO source_inbox (source, github_event_id, event_type, created_at, ingestion_id,
                                                  mode, payload, content_hash, received_at)
                        VALUES ('github:apache/kafka', ?, 'github.PushEvent', ?, 'ops-pending-outbox',
                                'LIVE', '{}'::jsonb, 'hash', ?)
                        """, "ops-inbox-" + System.nanoTime(), Timestamp.from(old), Timestamp.from(old));
        Long inboxId = jdbcTemplate.queryForObject(
                "SELECT id FROM source_inbox WHERE ingestion_id = 'ops-pending-outbox'", Long.class);
        jdbcTemplate.update("""
                        INSERT INTO source_outbox (source, ingestion_id, inbox_id, status, attempts, created_at)
                        VALUES ('github:apache/kafka', 'ops-pending-outbox', ?, 'PENDING', 0, ?)
                        """, inboxId, Timestamp.from(old));
        deadLetter("OPEN", null);
        jdbcTemplate.update("UPDATE dead_letter_records SET ingestion_id = 'ops-open-dlt' WHERE recovery_state = 'OPEN'");

        // Alerts: an old resolved one is pruned, an old unresolved one survives.
        alert("RESOLVED", old);
        alert("OPEN", null);

        // Dead letters: an old recovered one is pruned, the open one survives.
        deadLetter("REPLAYED", old);

        // Receipts: a recent ingestion keeps both its raw row and its receipt (a receipt is never
        // pruned while replayable data exists); an aged orphaned receipt is pruned.
        rawRow("ops-recent", "ops-recent", now.minusSeconds(60));
        jdbcTemplate.update("""
                        INSERT INTO processed_receipts (ingestion_id, processed_at, rule_version, content_digest)
                        VALUES ('ops-recent', ?, 'rules', 'digest')
                        """, Timestamp.from(now.minusSeconds(60)));
        jdbcTemplate.update("""
                        INSERT INTO processed_receipts (ingestion_id, processed_at, rule_version, content_digest)
                        VALUES ('ops-orphan', ?, 'rules', 'digest')
                        """, Timestamp.from(old));

        // Collector state and schema versions must never be pruned.
        jdbcTemplate.update("""
                        INSERT INTO collector_state (source, status, updated_at)
                        VALUES ('github:apache/kafka', 'READY', ?)
                        ON CONFLICT (source) DO NOTHING
                        """, Timestamp.from(now));
        long schemaRowsBefore = count("SELECT count(*) FROM schema_versions");

        Map<String, Long> pruned = retentionService.runNow(now);

        assertThat(count("SELECT count(*) FROM raw_events WHERE ingestion_id = 'ops-plain'")).isZero();
        assertThat(count("SELECT count(*) FROM raw_events WHERE ingestion_id = 'ops-pending-outbox'"))
                .as("a pending outbox row protects its raw event").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM raw_events WHERE ingestion_id = 'ops-open-dlt'"))
                .as("an unrecovered dead letter protects its raw event").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM quality_alerts WHERE source = 'ops-src' AND status = 'OPEN'"))
                .as("unresolved alerts are never pruned").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dead_letter_records"
                + " WHERE diagnostic_id LIKE 'ops-probe-%' AND recovery_state = 'OPEN'"))
                .as("open dead letters are never pruned").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dead_letter_records"
                + " WHERE diagnostic_id LIKE 'ops-probe-%' AND recovery_state = 'REPLAYED'"))
                .as("an aged recovered dead letter is pruned").isZero();
        assertThat(count("SELECT count(*) FROM processed_receipts WHERE ingestion_id = 'ops-recent'"))
                .as("a receipt stays while its raw row exists").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM raw_events WHERE ingestion_id = 'ops-recent'"))
                .as("recent data is not pruned").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processed_receipts WHERE ingestion_id = 'ops-orphan'"))
                .as("an orphaned aged receipt is pruned").isZero();
        assertThat(count("SELECT count(*) FROM collector_state WHERE source = 'github:apache/kafka'"))
                .isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM schema_versions")).isEqualTo(schemaRowsBefore);
        assertThat(pruned).containsKey("raw_events");

        Map<String, Object> status = retentionService.status();
        assertThat(status.get("raw_retention_days")).isEqualTo(30L);
        assertThat(status.get("batch_size")).isEqualTo(1000);
        assertThat((Long) status.get("open_dead_letters")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void registryExposesTheLowCardinalityMetricSet() {
        for (String meter : new String[]{
                "driftwatch_ingestion_ack_duration_seconds",
                "driftwatch_ingestion_failures_total",
                "driftwatch_processing_duration_seconds",
                "driftwatch_processing_failures_total",
                "driftwatch_collector_polls_total",
                "driftwatch_collector_failures_total",
                "driftwatch_collector_upstream_lag_seconds",
                "driftwatch_source_outbox_pending",
                "driftwatch_dead_letters_pending",
                "driftwatch_alerts_fired_total",
                "driftwatch_baseline_outbox_pending",
                "driftwatch_retention_rows_pruned"}) {
            assertThat(meterRegistry.find(meter).meters())
                    .as("meter %s must be registered", meter)
                    .isNotEmpty();
        }
        meterRegistry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getKey())
                        .as("no identity may become a label")
                        .isNotIn("event_id", "ingestion_id", "diagnostic_id")));
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
