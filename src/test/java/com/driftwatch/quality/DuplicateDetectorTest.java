package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateDetectorTest {

    private final StubState state = new StubState();
    private final RawEventRepository repo = (RawEventRepository) Proxy.newProxyInstance(
            RawEventRepository.class.getClassLoader(),
            new Class<?>[]{RawEventRepository.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "existsByEventId" -> state.existingEventIds.contains((String) args[0]);
                case "findFirstByPayloadHashAndReceivedAtAfterAndEventIdNot" -> state.payloadMatch;
                case "toString" -> "StubRawEventRepository";
                default -> defaultValue(method.getReturnType());
            });
    private final DuplicateDetector detector =
            new DuplicateDetector(repo, new ObjectMapper(), Duration.ofMinutes(5));

    private DetectionContext ctx(String eventId, String hash) {
        DataEvent e = new DataEvent(eventId, "binance", "market_tick",
                Instant.parse("2026-05-25T10:00:00Z"),
                Map.of("symbol", "BTC/USDT"));
        return new DetectionContext(e, hash, Instant.parse("2026-05-25T10:00:30Z"));
    }

    @Test
    void uniqueEventProducesNoAlerts() {
        state.existingEventIds = java.util.Set.of();
        state.payloadMatch = Optional.empty();

        assertThat(detector.detect(ctx("e1", "h1"))).isEmpty();
    }

    @Test
    void repeatedEventIdProducesAlert() {
        state.existingEventIds = java.util.Set.of("e1");
        state.payloadMatch = Optional.empty();

        List<DraftAlert> alerts = detector.detect(ctx("e1", "h1"));
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).type()).isEqualTo(AlertType.DUPLICATE_EVENT);
        assertThat(alerts.get(0).evidence().get("duplicate_kind").asText()).isEqualTo("REPEATED_EVENT_ID");
    }

    @Test
    void repeatedPayloadHashProducesAlertWithFirstSeen() {
        RawEventEntity prev = new RawEventEntity();
        prev.setEventId("e-prev");
        prev.setReceivedAt(Instant.parse("2026-05-25T09:59:00Z"));
        state.existingEventIds = java.util.Set.of();
        state.payloadMatch = Optional.of(prev);

        List<DraftAlert> alerts = detector.detect(ctx("e2", "h-shared"));
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).evidence().get("duplicate_kind").asText()).isEqualTo("REPEATED_PAYLOAD");
        assertThat(alerts.get(0).evidence().get("first_event_id").asText()).isEqualTo("e-prev");
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        if (returnType == char.class) return '\0';
        return null;
    }

    private static final class StubState {
        private java.util.Set<String> existingEventIds = java.util.Set.of();
        private Optional<RawEventEntity> payloadMatch = Optional.empty();
    }
}
