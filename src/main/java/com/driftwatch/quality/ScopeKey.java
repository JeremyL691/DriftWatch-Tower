package com.driftwatch.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Canonical encoding of a source/event-type scope (execution guide, section 4.1).
 *
 * <p>A canonical JSON array is used instead of concatenating with a separator: a source that
 * contains the separator character can then never collide with a different (source, eventType)
 * pair, and the same encoding works as a Kafka partition key, a state-store key and a database
 * lookup key.
 */
public final class ScopeKey {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScopeKey() {
    }

    /** Encodes one scope, for example {@code ["github:apache/kafka","github.PushEvent"]}. */
    public static String of(String source, String eventType) {
        try {
            return MAPPER.writeValueAsString(List.of(source == null ? "" : source,
                    eventType == null ? "" : eventType));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("scope key encoding failed", e);
        }
    }

    /** Encodes a scope plus additional components (field path, window start) in the same array. */
    public static String of(String source, String eventType, String... extra) {
        try {
            List<String> parts = new java.util.ArrayList<>();
            parts.add(source == null ? "" : source);
            parts.add(eventType == null ? "" : eventType);
            for (String value : extra) {
                parts.add(value == null ? "" : value);
            }
            return MAPPER.writeValueAsString(parts);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("scope key encoding failed", e);
        }
    }

    /** Recovers the two-element scope from a window/field key produced by {@link #of}. */
    public static String scopeOf(String windowKey) {
        try {
            var parts = MAPPER.readTree(windowKey);
            return MAPPER.writeValueAsString(List.of(parts.get(0).asText(), parts.get(1).asText()));
        } catch (Exception e) {
            return windowKey;
        }
    }
}