package com.driftwatch.dlt;

import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for the dead-letter management API (execution guide, sections 4.5 and 7.1): unknown ids are
 * 404, unsupported filters and paging are 400 rather than silently adjusted, every list is newest first, and
 * absent Kafka coordinates are JSON null.
 */
class DeadLetterApiIntegrationTest extends ContainerIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void replayOfAnUnknownDeadLetterIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/dead-letters/9999999999/replay").with(asAdmin()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsupportedFiltersAndPagingAreBadRequests() throws Exception {
        mockMvc.perform(get("/api/v1/dead-letters?stage=NOPE").with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/dead-letters?status=NOPE").with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/dead-letters?size=1000").with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/dead-letters?size=0").with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/dead-letters?page=-1").with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/dead-letters?stage=SINK&status=REPLAYED&size=100").with(asAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void everyListBranchIsNewestFirst() throws Exception {
        String tag = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            insertDeadLetter("SINK", "OPEN", "order-" + tag + "-" + i, false);
        }
        Long newest = jdbcTemplate.queryForObject("SELECT max(id) FROM dead_letter_records", Long.class);

        List<Long> unfiltered = ids("/api/v1/dead-letters?size=100");
        assertThat(unfiltered).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(unfiltered.get(0)).as("the unfiltered list starts with the newest row").isEqualTo(newest);
        assertThat(ids("/api/v1/dead-letters?stage=SINK&size=100"))
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(ids("/api/v1/dead-letters?status=OPEN&size=100"))
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(ids("/api/v1/dead-letters?stage=SINK&status=OPEN&size=100"))
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void absentKafkaCoordinatesAreJsonNullNotTheStringNull() throws Exception {
        insertDeadLetter("STREAM", "OPEN", "kafka-null-" + UUID.randomUUID(), false);
        long id = jdbcTemplate.queryForObject(
                "SELECT max(id) FROM dead_letter_records WHERE stage = 'STREAM'", Long.class);

        String json = mockMvc.perform(get("/api/v1/dead-letters/" + id).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> body = objectMapper.readValue(json, new TypeReference<>() {});
        Map<String, Object> kafka = objectMapper.convertValue(body.get("kafka"), new TypeReference<>() {});

        assertThat(kafka).containsKeys("topic", "partition", "offset");
        assertThat(kafka.get("topic")).isNull();
        assertThat(kafka.get("partition")).isNull();
        assertThat(kafka.get("offset")).isNull();
    }

    private List<Long> ids(String path) throws Exception {
        String json = mockMvc.perform(get(path).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = objectMapper.readValue(json, new TypeReference<>() {});
        return rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    private void insertDeadLetter(String stage, String state, String diagnosticId, boolean withKafka) {
        jdbcTemplate.update("""
                        INSERT INTO dead_letter_records (diagnostic_id, stage, source, event_type, reason, attempts,
                                                         recovery_state, kafka_topic, kafka_partition, kafka_offset,
                                                         created_at)
                        VALUES (?, ?, 'dlt-api-src', 'dlt_api_event', 'probe', 0, ?, ?, ?, ?, now())
                        """, diagnosticId, stage, state,
                withKafka ? "raw-events-v1" : null,
                withKafka ? 1 : null,
                withKafka ? 42L : null);
    }
}
