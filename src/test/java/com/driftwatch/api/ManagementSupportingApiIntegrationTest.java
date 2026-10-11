package com.driftwatch.api;

import com.driftwatch.support.ContainerIntegrationTest;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ManagementSupportingApiIntegrationTest extends ContainerIntegrationTest {
    @Test void collectorExposesCheckpointAndBoundedGapEvidenceScopedToConfiguredSource() throws Exception {
        jdbcTemplate.update("DELETE FROM source_gaps");
        jdbcTemplate.update("DELETE FROM collector_state WHERE source = 'github:apache/kafka'");
        jdbcTemplate.update("""
                INSERT INTO collector_state(source,status,etag_applied,etag_candidate,pending_poll_run_id,observed_from,updated_at)
                VALUES ('github:apache/kafka','STAGED','applied','candidate',42,?,?)
                """, Timestamp.from(Instant.now().minusSeconds(600)), Timestamp.from(Instant.now()));
        for (int i = 0; i < 22; i++) {
            jdbcTemplate.update("""
                    INSERT INTO source_gaps(source,reason,missing_count,recovery_state,created_at)
                    VALUES ('github:apache/kafka','PAGINATION_TRUNCATED','unknown','OPEN',?)
                    """, Timestamp.from(Instant.now()));
        }
        jdbcTemplate.update("""
                INSERT INTO source_gaps(source,reason,missing_count,recovery_state,created_at)
                VALUES ('github:old/repository','NO_OVERLAP','unknown','OPEN',?)
                """, Timestamp.from(Instant.now()));
        mockMvc.perform(get("/api/v1/sources/collectors").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pending_poll_run_id").value(42))
                .andExpect(jsonPath("$[0].etag_candidate").value(true))
                .andExpect(jsonPath("$[0].observed_from").exists())
                .andExpect(jsonPath("$[0].open_gaps").value(22))
                .andExpect(jsonPath("$[0].recent_gaps.length()").value(20))
                .andExpect(jsonPath("$[0].recent_gaps[0].missing_count").value("unknown"));
    }

    @Test void persistedBaselinePublicationStatusNeverClaimsConsumerApplication() throws Exception {
        mockMvc.perform(get("/api/v1/schemas/unknown/baseline").with(asAdmin())).andExpect(status().isNotFound());
        jdbcTemplate.update("""
                INSERT INTO schema_versions(event_type,schema_hash,schema_json,first_seen_at,last_seen_at,status)
                VALUES ('publication_probe','probe','{}'::jsonb,?,?,'ACTIVE')
                """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        Long version = jdbcTemplate.queryForObject("SELECT id FROM schema_versions WHERE event_type='publication_probe'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO baseline_outbox(event_type,version_id,payload_json,status,attempts,created_at,sent_at)
                VALUES ('publication_probe',?,'{}'::jsonb,'SENT',1,?,?)
                """, version, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        mockMvc.perform(get("/api/v1/schemas/publication_probe/baseline").with(asAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version_id").value(version))
                .andExpect(jsonPath("$.baseline_sync.state").value("PUBLISHED"))
                .andExpect(jsonPath("$.baseline_sync.consumer_application").value("UNCONFIRMED"));
    }
}
