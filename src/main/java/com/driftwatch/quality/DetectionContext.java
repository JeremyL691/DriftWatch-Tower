package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;

import java.time.Instant;

public record DetectionContext(
        DataEvent event,
        String payloadHash,
        Instant receivedAt
) {}
