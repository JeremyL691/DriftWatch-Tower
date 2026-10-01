package com.driftwatch.source.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The converter must read type-specific fields from where GitHub's events API actually puts them.
 * A live run showed every one of 334 ingested events with a null {@code action} - including types
 * where GitHub always sends it - because the converter read the event root, while the API nests
 * them under {@code payload}. The shapes below are copied from a live
 * {@code /repos/apache/kafka/events} response.
 */
class GithubEventConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GithubEventConverter converter = new GithubEventConverter(objectMapper);

    @Test
    void readsTypeSpecificFieldsFromPayload() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {"id":"123","type":"PullRequestEvent","created_at":"2026-10-01T12:00:00Z",
                 "payload":{"action":"opened","number":42,"pull_request":{"number":42}},
                 "repo":{"id":7,"name":"apache/kafka"},"actor":{"id":9},"public":true}""");

        GithubEventConverter.Converted converted = converter.convert(raw, "apache/kafka");

        assertThat(converted.action()).isEqualTo("opened");
        JsonNode payload = converted.payload();
        assertThat(payload.get("action").asText()).isEqualTo("opened");
        assertThat(payload.get("pull_request_number").asLong()).isEqualTo(42);
        assertThat(payload.get("github_event_type").asText()).isEqualTo("PullRequestEvent");
        assertThat(payload.get("repository").asText()).isEqualTo("apache/kafka");
        assertThat(payload.get("actor_id").asLong()).isEqualTo(9);
    }

    @Test
    void fieldsTheTypeDoesNotCarryStayExplicitNull() throws Exception {
        JsonNode raw = objectMapper.readTree("""
                {"id":"124","type":"WatchEvent","created_at":"2026-10-01T12:00:01Z",
                 "payload":{"action":"started"},
                 "repo":{"id":7,"name":"apache/kafka"},"actor":{"id":9},"public":true}""");

        JsonNode payload = converter.convert(raw, "apache/kafka").payload();

        assertThat(payload.get("action").asText()).isEqualTo("started");
        assertThat(payload.get("forkee_id").isNull()).isTrue();
        assertThat(payload.get("pull_request_number").isNull()).isTrue();
        assertThat(payload.get("release_tag").isNull()).isTrue();
    }
}
