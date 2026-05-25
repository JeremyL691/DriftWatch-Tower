package com.driftwatch.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RawEventProducerTest {

    @Test
    void partitionKeyGroupsEventsBySourceAndEventType() {
        DataEvent event = new DataEvent(
                "evt-1",
                "demo-api",
                "demo_schema_event",
                Instant.parse("2026-05-25T00:00:00Z"),
                Map.of("symbol", "BTC/USDT")
        );

        assertThat(RawEventProducer.partitionKey(event))
                .isEqualTo("demo-api|demo_schema_event");
    }
}
