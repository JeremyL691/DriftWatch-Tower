package com.driftwatch.event;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataEventValidationTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    @Test
    void wellFormedEventHasNoViolations() {
        DataEvent event = new DataEvent(
                "evt-1", "binance", "market_tick",
                Instant.parse("2026-05-25T08:30:00Z"),
                Map.of("symbol", "BTC/USDT", "bid", 1.0)
        );
        assertThat(VALIDATOR.validate(event)).isEmpty();
    }

    @Test
    void blankEventIdRejected() {
        DataEvent event = new DataEvent(
                "  ", "binance", "market_tick", Instant.now(), Map.of("k", "v")
        );
        assertThat(VALIDATOR.validate(event))
                .anyMatch(v -> v.getPropertyPath().toString().equals("eventId"));
    }

    @Test
    void nullTimestampRejected() {
        DataEvent event = new DataEvent(
                "evt-1", "binance", "market_tick", null, Map.of("k", "v")
        );
        assertThat(VALIDATOR.validate(event))
                .anyMatch(v -> v.getPropertyPath().toString().equals("eventTimestamp"));
    }

    @Test
    void nullPayloadRejected() {
        DataEvent event = new DataEvent(
                "evt-1", "binance", "market_tick", Instant.now(), null
        );
        assertThat(VALIDATOR.validate(event))
                .anyMatch(v -> v.getPropertyPath().toString().equals("payload"));
    }
}
