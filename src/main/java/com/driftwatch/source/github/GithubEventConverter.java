package com.driftwatch.source.github;

import com.driftwatch.event.DataEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts one upstream GitHub event into the internal {@link DataEvent} plus the minimal payload
 * subset (execution guide, section 6.2): repository, event type, actor id, public flag and the
 * type-specific structural fields only. Bodies, emails, avatars and tokens are never collected,
 * and a missing optional field becomes an explicit null instead of being dropped silently.
 */
@Component
public class GithubEventConverter {

    private final ObjectMapper objectMapper;

    public GithubEventConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Conversion result: identity, timing and the minimised payload. */
    public record Converted(
            String githubEventId,
            String eventType,
            String upstreamType,
            Instant createdAt,
            String action,
            DataEvent event,
            JsonNode payload,
            String contentHash
    ) {}

    public Converted convert(JsonNode raw, String repository) {
        String rawId = raw.path("id").asText(null);
        if (rawId == null || rawId.isBlank()) {
            throw new IllegalArgumentException("github event without an id");
        }
        String upstreamType = raw.path("type").asText("UNKNOWN");
        String createdAtText = raw.path("created_at").asText(null);
        Instant createdAt = createdAtText == null ? Instant.now() : Instant.parse(createdAtText);
        // GitHub's events API carries every type-specific field inside `payload`; only id, type,
        // actor, repo, public and created_at are top-level. Reading them from the root silently
        // produced nulls for every ingested event of a real run (action null for all 334 events,
        // including types where GitHub always sends it), which in turn made NULL_SPIKE fire on
        // those permanently-null columns.
        JsonNode payloadNode = raw.path("payload");
        String action = payloadNode.path("action").isMissingNode() || payloadNode.path("action").isNull()
                ? null : payloadNode.path("action").asText(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repository", repositoryName(raw, repository));
        payload.put("repository_id", raw.path("repo").path("id").isNumber()
                ? raw.path("repo").path("id").asLong() : null);
        payload.put("github_event_type", upstreamType);
        payload.put("actor_id", raw.path("actor").path("id").isNumber()
                ? raw.path("actor").path("id").asLong() : null);
        payload.put("public", raw.path("public").isBoolean() ? raw.path("public").asBoolean() : null);
        payload.put("action", action);
        // Type-specific structural evidence only.
        putIfPresent(payload, "issue_number", payloadNode.path("issue").path("number"));
        putIfPresent(payload, "pull_request_number", payloadNode.path("pull_request").path("number"));
        putIfPresent(payload, "ref", payloadNode.path("ref"));
        putIfPresent(payload, "head", payloadNode.path("head"));
        putIfPresent(payload, "push_id", payloadNode.path("push_id"));
        putIfPresent(payload, "release_id", payloadNode.path("release").path("id"));
        putIfPresent(payload, "release_tag", payloadNode.path("release").path("tag_name"));
        putIfPresent(payload, "forkee_id", payloadNode.path("forkee").path("id"));
        putIfPresent(payload, "member_id", payloadNode.path("member").path("id"));

        String eventType = "github." + upstreamType;
        String eventId = "github:" + rawId;
        DataEvent event = new DataEvent(eventId, "github:" + repository, eventType, createdAt, payload);
        return new Converted(rawId, eventType, upstreamType, createdAt, action, event,
                objectMapper.valueToTree(payload), GithubEventsClient.sha256(raw.toString()));
    }

    private static void putIfPresent(Map<String, Object> payload, String key, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            payload.put(key, null);
            return;
        }
        payload.put(key, node.isNumber() ? node.asLong() : node.asText());
    }

    private static String repositoryName(JsonNode raw, String fallback) {
        String fullName = raw.path("repo").path("name").asText(null);
        return fullName == null || fullName.isBlank() ? fallback : fullName;
    }

    /** Raw id ordering helper: equal created_at values are ordered by raw id, never by number. */
    public static int compareForOrdering(JsonNode left, JsonNode right) {
        Instant leftCreated = Instant.parse(left.path("created_at").asText("1970-01-01T00:00:00Z"));
        Instant rightCreated = Instant.parse(right.path("created_at").asText("1970-01-01T00:00:00Z"));
        int byTime = leftCreated.compareTo(rightCreated);
        if (byTime != 0) {
            return byTime;
        }
        return left.path("id").asText("").compareTo(right.path("id").asText(""));
    }

    /** Normalised summary kept for traceability. */
    public ObjectNode summary(JsonNode raw) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", raw.path("id").asText(null));
        node.put("type", raw.path("type").asText("UNKNOWN"));
        node.put("created_at", raw.path("created_at").asText(null));
        return node;
    }
}
