package com.driftwatch.quality.schema;

import java.util.Map;

/**
 * Value published to the compacted {@code schema-baselines-v1} topic and cached in the Streams
 * global store: the active baseline for one event type.
 */
public record BaselineMessage(
        String eventType,
        Long versionId,
        Map<String, String> leafTypes
) {
}