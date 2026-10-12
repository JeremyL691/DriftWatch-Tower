package com.driftwatch.event;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The raw_events columns are event_id VARCHAR(128), source and event_type VARCHAR(64). */
class DataEventLengthLimitTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    private static DataEvent event(String eventId, String source, String eventType) {
        return new DataEvent(eventId, source, eventType, Instant.parse("2026-10-01T00:00:00Z"), Map.of("bid", 1.0));
    }

    @Test
    void sourceLongerThan64IsRejected() {
        assertThat(VALIDATOR.validate(event("evt-1", "s".repeat(65), "market_tick")))
                .anyMatch(v -> v.getPropertyPath().toString().equals("source"));
    }

    @Test
    void eventTypeLongerThan64IsRejected() {
        assertThat(VALIDATOR.validate(event("evt-1", "binance", "t".repeat(65))))
                .anyMatch(v -> v.getPropertyPath().toString().equals("eventType"));
    }

    @Test
    void eventIdLongerThan128IsRejected() {
        assertThat(VALIDATOR.validate(event("e".repeat(129), "binance", "market_tick")))
                .anyMatch(v -> v.getPropertyPath().toString().equals("eventId"));
    }

    @Test
    void valuesAtTheColumnLimitsAreAccepted() {
        assertThat(VALIDATOR.validate(event("e".repeat(128), "s".repeat(64), "t".repeat(64)))).isEmpty();
    }
}
