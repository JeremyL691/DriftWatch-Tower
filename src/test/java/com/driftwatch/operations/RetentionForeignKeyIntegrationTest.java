package com.driftwatch.operations;

import com.driftwatch.support.ContainerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the retention foreign keys (execution guide, section 7.4). source_outbox references
 * source_inbox and dead_letter_replays references dead_letter_records, both without ON DELETE. Before the
 * fix, an aged inbox row whose outbox row was SENT, or an aged REPLAYED dead letter with replay rows, made
 * the whole pass fail with a foreign-key violation. A pass must now delete those dependents together with
 * their parents, and prune expired idempotency receipts.
 */
class RetentionForeignKeyIntegrationTest extends ContainerIntegrationTest {

    @Autowired
    RetentionService retentionService;

    @Test
    void runNowRemovesAgedDependentRowsWithTheirParentsWithoutAForeignKeyViolation() {
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(120));
        String tag = "fk-" + System.nanoTime();
        String source = "github:" + tag;

        // An aged inbox row whose outbox row was published: the outbox row references the inbox row.
        jdbcTemplate.update("""
                        INSERT INTO source_inbox (source, github_event_id, event_type, created_at, ingestion_id,
                                                  mode, payload, content_hash, received_at)
                        VALUES (?, ?, 'github.PushEvent', ?, ?, 'LIVE', '{}'::jsonb, 'hash', ?)
                        """, source, tag, ts(old), tag, ts(old));
        Long inboxId = jdbcTemplate.queryForObject(
                "SELECT id FROM source_inbox WHERE ingestion_id = ?", Long.class, tag);
        jdbcTemplate.update("""
                        INSERT INTO source_outbox (source, ingestion_id, inbox_id, status, attempts, created_at, sent_at)
                        VALUES (?, ?, ?, 'SENT', 1, ?, ?)
                        """, source, tag, inboxId, ts(old), ts(old));

        // An aged, replayed dead letter with two replay attempts: the replay rows reference it.
        String diagnosticId = "fk-dlt-" + tag;
        jdbcTemplate.update("""
                        INSERT INTO dead_letter_records (diagnostic_id, stage, source, event_type, reason,
                                                         attempts, recovery_state, created_at, recovered_at)
                        VALUES (?, 'SINK', 'fk-src', 'fk_event', 'probe', 2, 'REPLAYED', ?, ?)
                        """, diagnosticId, ts(old), ts(old));
        Long deadLetterId = jdbcTemplate.queryForObject(
                "SELECT id FROM dead_letter_records WHERE diagnostic_id = ?", Long.class, diagnosticId);
        for (String attempt : new String[]{"first", "second"}) {
            jdbcTemplate.update("""
                            INSERT INTO dead_letter_replays (dead_letter_id, replay_attempt_id, stage, outcome,
                                                             detail, requested_at)
                            VALUES (?, ?, 'SINK', 'PUBLISHED', 'probe', ?)
                            """, deadLetterId, attempt + "-" + tag, ts(old));
        }

        // Idempotency receipts: one expired two days ago is pruned, one still valid is kept.
        jdbcTemplate.update("""
                        INSERT INTO ingestion_receipts (source, event_type, idempotency_key, request_digest,
                                                        ingestion_id, publish_state, expires_at)
                        VALUES ('fk-src', 'fk_event', ?, 'digest', ?, 'PUBLISHED', ?)
                        """, "expired-" + tag, "rcpt-expired-" + tag, ts(now.minus(Duration.ofDays(2))));
        jdbcTemplate.update("""
                        INSERT INTO ingestion_receipts (source, event_type, idempotency_key, request_digest,
                                                        ingestion_id, publish_state, expires_at)
                        VALUES ('fk-src', 'fk_event', ?, 'digest', ?, 'PUBLISHED', ?)
                        """, "live-" + tag, "rcpt-live-" + tag, ts(now.plus(Duration.ofDays(1))));

        Map<String, Long> pruned = retentionService.runNow(now);

        assertThat(count("SELECT count(*) FROM source_inbox WHERE ingestion_id = ?", tag))
                .as("the aged inbox row is removed with its published outbox row").isZero();
        assertThat(count("SELECT count(*) FROM source_outbox WHERE ingestion_id = ?", tag)).isZero();
        assertThat(count("SELECT count(*) FROM dead_letter_records WHERE diagnostic_id = ?", diagnosticId))
                .as("the aged replayed dead letter is removed").isZero();
        assertThat(count("SELECT count(*) FROM dead_letter_replays WHERE dead_letter_id = ?", deadLetterId))
                .as("its replay history goes with it").isZero();
        assertThat(count("SELECT count(*) FROM ingestion_receipts WHERE idempotency_key = ?", "expired-" + tag))
                .as("an expired receipt is pruned").isZero();
        assertThat(count("SELECT count(*) FROM ingestion_receipts WHERE idempotency_key = ?", "live-" + tag))
                .as("a receipt that is still valid is kept").isEqualTo(1);
        assertThat(pruned).containsKeys("source_inbox", "dead_letter_records", "ingestion_receipts");
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
