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
 * Regression for collector evidence retention (execution guide, section 7.4). source_poll_runs and
 * source_gaps grew without bound. A pass must now prune aged poll runs that nothing references, and
 * aged gaps, while always keeping the newest 20 rows per source that the collector status view reads.
 */
class RetentionCollectorEvidenceIntegrationTest extends ContainerIntegrationTest {

    @Autowired
    RetentionService retentionService;

    @Test
    void runNowPrunesUnreferencedOldPollRunsAndGapsOutsideTheCollectorViewWithoutThrowing() {
        Instant now = Instant.now();
        Instant oldRun = now.minus(Duration.ofDays(40));
        Instant oldGap = now.minus(Duration.ofDays(100));
        Instant recent = now.minus(Duration.ofDays(1));
        String source = "github:collector-evidence-" + System.nanoTime();

        // 25 old poll runs (oldest first), then 3 recent ones. The newest 20 per source are the 3 recent
        // runs and old runs 8..24. Old runs 0 and 1 are protected by a pending checkpoint and an inbox row.
        long[] runIds = new long[28];
        for (int i = 0; i < 28; i++) {
            Instant started = i < 25 ? oldRun : recent;
            jdbcTemplate.update("""
                            INSERT INTO source_poll_runs (source, status, mode, started_at, finished_at)
                            VALUES (?, 'QUIET', 'LIVE', ?, ?)
                            """, source, ts(started), ts(started));
            runIds[i] = jdbcTemplate.queryForObject(
                    "SELECT max(id) FROM source_poll_runs WHERE source = ?", Long.class, source);
        }

        jdbcTemplate.update("""
                        INSERT INTO collector_state (source, status, pending_poll_run_id, updated_at)
                        VALUES (?, 'FETCHING', ?, ?)
                        """, source, runIds[0], ts(now));

        // The inbox row points at run 1. A PENDING outbox row keeps the inbox row itself from being pruned.
        String ingestionId = "collector-evidence-inbox-" + System.nanoTime();
        jdbcTemplate.update("""
                        INSERT INTO source_inbox (source, github_event_id, event_type, created_at, ingestion_id,
                                                  mode, payload, content_hash, poll_run_id, received_at)
                        VALUES (?, ?, 'github.PushEvent', ?, ?, 'LIVE', '{}'::jsonb, 'hash', ?, ?)
                        """, source, ingestionId, ts(oldRun), ingestionId, runIds[1], ts(oldRun));
        Long inboxId = jdbcTemplate.queryForObject(
                "SELECT id FROM source_inbox WHERE ingestion_id = ?", Long.class, ingestionId);
        jdbcTemplate.update("""
                        INSERT INTO source_outbox (source, ingestion_id, inbox_id, status, attempts, created_at)
                        VALUES (?, ?, ?, 'PENDING', 0, ?)
                        """, source, ingestionId, inboxId, ts(oldRun));

        // 25 old gaps (oldest first), then 2 recent ones. The newest 20 gaps are the 2 recent ones and old gaps 7..24.
        for (int i = 0; i < 27; i++) {
            Instant created = i < 25 ? oldGap : recent;
            jdbcTemplate.update("""
                            INSERT INTO source_gaps (source, reason, visible_from, visible_to, created_at)
                            VALUES (?, 'DOWNTIME', ?, ?, ?)
                            """, source, ts(created), ts(created), ts(created));
        }

        Map<String, Long> pruned = retentionService.runNow(now);

        // Runs: old, unreferenced and outside the newest 20 are deleted (old 2..7 = 6 runs).
        for (int i : new int[]{2, 3, 4, 5, 6, 7}) {
            assertThat(count("SELECT count(*) FROM source_poll_runs WHERE id = ?", runIds[i]))
                    .as("unreferenced old poll run %d outside the newest 20 is pruned", i).isZero();
        }
        // Runs: the pending-referenced and inbox-referenced ones survive.
        assertThat(count("SELECT count(*) FROM source_poll_runs WHERE id = ?", runIds[0]))
                .as("the pending checkpoint's run survives").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM source_poll_runs WHERE id = ?", runIds[1]))
                .as("the inbox-referenced run survives").isEqualTo(1);
        // Runs: the newest 20 per source survive, including the recent runs.
        for (int i = 8; i < 28; i++) {
            assertThat(count("SELECT count(*) FROM source_poll_runs WHERE id = ?", runIds[i]))
                    .as("run %d is within the newest 20 and survives", i).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM source_poll_runs WHERE source = ?", source)).isEqualTo(22);

        // Gaps: old gaps beyond the newest 20 are deleted (old 0..6 = 7 gaps), the other 18 old and 2 recent survive.
        assertThat(count("SELECT count(*) FROM source_gaps WHERE source = ? AND created_at < ?", source, ts(now.minus(Duration.ofDays(90)))))
                .as("old gaps outside the newest 20 are pruned, the newest 18 old gaps survive").isEqualTo(18);
        assertThat(count("SELECT count(*) FROM source_gaps WHERE source = ? AND created_at >= ?", source, ts(recent.minus(Duration.ofHours(1)))))
                .as("both recent gaps are kept").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM source_gaps WHERE source = ?", source)).isEqualTo(20);

        assertThat(pruned).containsKeys("source_poll_runs", "source_gaps");
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
