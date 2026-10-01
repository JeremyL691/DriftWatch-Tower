package com.driftwatch.operations;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.persistence.BaselineOutboxEntity;
import com.driftwatch.persistence.DeadLetterRecordEntity;
import com.driftwatch.persistence.SourceOutboxEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Daily retention (execution guide, section 7.4).
 *
 * <p>Deletes in batches of at most 1000 rows per statement so a large table never locks for long,
 * and never touches evidence that is still needed: unresolved alerts and incidents, unrecovered
 * dead letters, pending outbox rows and collector/schema state are exempt. Deleting raw events
 * does not cascade to alerts — alert evidence outlives the raw payload it was derived from.
 * Processed receipts are only pruned once their raw row is gone, so a replay can never find a
 * receipt without its data.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    static final int BATCH_SIZE = 1000;
    static final Duration RAW_RETENTION = Duration.ofDays(30);
    static final Duration METRIC_RETENTION = Duration.ofDays(30);
    static final Duration RESOLVED_ALERT_RETENTION = Duration.ofDays(90);
    static final Duration COMPLETED_DLT_RETENTION = Duration.ofDays(90);
    static final Duration INBOX_IDENTITY_RETENTION = Duration.ofDays(35);

    private final JdbcTemplate jdbcTemplate;
    private final DriftwatchMetrics metrics;
    private final DriftwatchProperties properties;
    private final Clock clock;

    public RetentionService(JdbcTemplate jdbcTemplate,
                            DriftwatchMetrics metrics,
                            DriftwatchProperties properties,
                            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** Runs once a day; the first pass waits a minute so startup is never delayed. */
    @Scheduled(cron = "${driftwatch.retention.cron:0 30 3 * * *}")
    public void daily() {
        runNow(clock.instant());
    }

    /** One retention pass; returns the number of rows removed per table. */
    public Map<String, Long> runNow(Instant now) {
        Map<String, Long> pruned = new LinkedHashMap<>();
        long total = 0;
        total += prune(pruned, "raw_events", """
                DELETE FROM raw_events WHERE id IN (
                    SELECT r.id FROM raw_events r
                    WHERE r.received_at < ?
                      AND NOT EXISTS (SELECT 1 FROM source_outbox o
                                      WHERE o.ingestion_id = r.ingestion_id
                                        AND o.status = 'PENDING')
                      AND NOT EXISTS (SELECT 1 FROM dead_letter_records d
                                      WHERE d.ingestion_id = r.ingestion_id
                                        AND d.recovery_state = 'OPEN')
                    ORDER BY r.id LIMIT %d)
                """.formatted(BATCH_SIZE), now.minus(RAW_RETENTION));
        total += prune(pruned, "metric_windows",
                "DELETE FROM metric_windows WHERE id IN (SELECT id FROM metric_windows WHERE window_end < ?"
                        + " ORDER BY id LIMIT " + BATCH_SIZE + ")",
                now.minus(METRIC_RETENTION));
        total += prune(pruned, "quality_alerts", """
                DELETE FROM quality_alerts WHERE id IN (
                    SELECT a.id FROM quality_alerts a
                    WHERE a.status = 'RESOLVED' AND a.resolved_at < ?
                    ORDER BY a.id LIMIT %d)
                """.formatted(BATCH_SIZE), now.minus(RESOLVED_ALERT_RETENTION));
        total += prune(pruned, "alert_incidents", """
                DELETE FROM alert_incidents WHERE id IN (
                    SELECT i.id FROM alert_incidents i
                    WHERE i.status = 'RESOLVED' AND i.resolved_at < ?
                      AND NOT EXISTS (SELECT 1 FROM quality_alerts a WHERE a.incident_id = i.id)
                    ORDER BY i.id LIMIT %d)
                """.formatted(BATCH_SIZE), now.minus(RESOLVED_ALERT_RETENTION));
        total += prune(pruned, "dead_letter_records", """
                DELETE FROM dead_letter_records WHERE id IN (
                    SELECT d.id FROM dead_letter_records d
                    WHERE d.recovery_state <> 'OPEN' AND d.recovered_at < ?
                    ORDER BY d.id LIMIT %d)
                """.formatted(BATCH_SIZE), now.minus(COMPLETED_DLT_RETENTION));
        total += prune(pruned, "source_inbox", """
                DELETE FROM source_inbox WHERE id IN (
                    SELECT i.id FROM source_inbox i
                    WHERE i.received_at < ?
                      AND NOT EXISTS (SELECT 1 FROM source_outbox o
                                      WHERE o.inbox_id = i.id AND o.status = 'PENDING')
                    ORDER BY i.id LIMIT %d)
                """.formatted(BATCH_SIZE), now.minus(INBOX_IDENTITY_RETENTION));
        total += prune(pruned, "processed_receipts", """
                DELETE FROM processed_receipts WHERE ingestion_id IN (
                    SELECT p.ingestion_id FROM processed_receipts p
                    WHERE p.processed_at < ?
                      AND NOT EXISTS (SELECT 1 FROM raw_events r WHERE r.ingestion_id = p.ingestion_id)
                      AND NOT EXISTS (SELECT 1 FROM dead_letter_records d
                                      WHERE d.ingestion_id = p.ingestion_id)
                    ORDER BY p.processed_at LIMIT %d)
                """.formatted(BATCH_SIZE), now.minus(RAW_RETENTION));
        metrics.recordRetention(total);
        log.info("retention pass pruned {} rows: {}", total, pruned);
        return pruned;
    }

    /** Current retention settings and the last successful pass, for the operations API. */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("raw_retention_days", RAW_RETENTION.toDays());
        status.put("metric_retention_days", METRIC_RETENTION.toDays());
        status.put("resolved_alert_retention_days", RESOLVED_ALERT_RETENTION.toDays());
        status.put("completed_dead_letter_retention_days", COMPLETED_DLT_RETENTION.toDays());
        status.put("inbox_identity_retention_days", INBOX_IDENTITY_RETENTION.toDays());
        status.put("batch_size", BATCH_SIZE);
        status.put("unresolved_alerts", count("SELECT count(*) FROM quality_alerts WHERE status <> 'RESOLVED'"));
        status.put("open_incidents", count("SELECT count(*) FROM alert_incidents WHERE status = 'OPEN'"));
        status.put("open_dead_letters", count("SELECT count(*) FROM dead_letter_records WHERE recovery_state = 'OPEN'"));
        status.put("pending_source_outbox", count("SELECT count(*) FROM source_outbox WHERE status = 'PENDING'"));
        status.put("pending_baseline_outbox", count("SELECT count(*) FROM baseline_outbox WHERE status = 'PENDING'"));
        status.put("collector_states", count("SELECT count(*) FROM collector_state"));
        status.put("schema_versions", count("SELECT count(*) FROM schema_versions"));
        return status;
    }

    private long prune(Map<String, Long> pruned, String table, String sql, Instant cutoff) {
        long removed = 0;
        while (true) {
            int rows = jdbcTemplate.update(sql, java.sql.Timestamp.from(cutoff));
            removed += rows;
            if (rows < BATCH_SIZE) {
                break;
            }
        }
        if (removed > 0) {
            pruned.put(table, removed);
        }
        return removed;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
