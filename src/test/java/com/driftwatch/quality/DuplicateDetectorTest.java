package com.driftwatch.quality;

import com.driftwatch.event.DataEvent;
import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DuplicateDetectorTest {

    private final RawEventRepository repo = mock(RawEventRepository.class);
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
        when(repo.existsByEventId(anyString())).thenReturn(false);
        when(repo.findFirstByPayloadHashAndReceivedAtAfterAndEventIdNot(anyString(), any(), anyString()))
                .thenReturn(Optional.empty());

        assertThat(detector.detect(ctx("e1", "h1"))).isEmpty();
    }

    @Test
    void repeatedEventIdProducesAlert() {
        when(repo.existsByEventId("e1")).thenReturn(true);
        when(repo.findFirstByPayloadHashAndReceivedAtAfterAndEventIdNot(anyString(), any(), anyString()))
                .thenReturn(Optional.empty());

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
        when(repo.existsByEventId("e2")).thenReturn(false);
        when(repo.findFirstByPayloadHashAndReceivedAtAfterAndEventIdNot(anyString(), any(), anyString()))
                .thenReturn(Optional.of(prev));

        List<DraftAlert> alerts = detector.detect(ctx("e2", "h-shared"));
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).evidence().get("duplicate_kind").asText()).isEqualTo("REPEATED_PAYLOAD");
        assertThat(alerts.get(0).evidence().get("first_event_id").asText()).isEqualTo("e-prev");
    }
}
