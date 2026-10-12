package com.driftwatch.event;

import com.driftwatch.operations.DriftwatchMetrics;
import com.driftwatch.persistence.IngestionReceiptEntity;
import com.driftwatch.persistence.IngestionReceiptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Ingest contract rules that are checked before anything is published, plus the receipt race
 * rules. Runs without a broker or database: the repository, entity manager and producer are mocks.
 */
class IngestionServiceContractTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-10-01T00:00:00Z");

    private final RawEventProducer producer = mock(RawEventProducer.class);
    private final IngestionReceiptRepository receipts = mock(IngestionReceiptRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RequestDigests digests = new RequestDigests();
    private final IngestionService service = newService();

    private IngestionService newService() {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        IngestionService created = new IngestionService(producer, receipts, digests, mapper,
                mock(PlatformTransactionManager.class), mock(DriftwatchMetrics.class));
        ReflectionTestUtils.setField(created, "entityManager", entityManager);
        return created;
    }

    private static DataEvent event(String eventId, String source, String eventType, Map<String, Object> payload) {
        return new DataEvent(eventId, source, eventType, TIMESTAMP, payload);
    }

    /** A payload whose container depth is exactly {@code depth}; the payload object is depth 1. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOfDepth(int depth) {
        Object current = Map.of("leaf", "value");
        for (int level = 2; level <= depth; level++) {
            current = Map.of("child", current);
        }
        return (Map<String, Object>) current;
    }

    @Test
    void flatPayloadIsDepthOne() {
        assertThat(IngestionService.exceedsPayloadDepth(Map.of("bid", 1.0))).isFalse();
    }

    @Test
    void nestingUpToTheLimitIsAccepted() {
        assertThat(IngestionService.exceedsPayloadDepth(payloadOfDepth(IngestionService.MAX_PAYLOAD_DEPTH))).isFalse();
        assertThat(IngestionService.MAX_PAYLOAD_DEPTH).isEqualTo(20);
    }

    @Test
    void nestingBeyondTheLimitIsRejected() {
        assertThat(IngestionService.exceedsPayloadDepth(payloadOfDepth(21))).isTrue();
        assertThat(IngestionService.exceedsPayloadDepth(payloadOfDepth(25))).isTrue();
    }

    @Test
    void arraysAddNestingLevelsToo() {
        // The payload object is level 1; 19 nested lists put the innermost list at level 20.
        Object atLimit = "leaf";
        for (int i = 0; i < 19; i++) {
            atLimit = List.of(atLimit);
        }
        Object beyond = List.of(atLimit);
        assertThat(IngestionService.exceedsPayloadDepth(Map.of("items", atLimit))).isFalse();
        assertThat(IngestionService.exceedsPayloadDepth(Map.of("items", beyond))).isTrue();
    }

    @Test
    void singleEventWithTooDeepPayloadIsRejectedBeforePublishing() {
        DataEvent deep = event("deep-1", "unit-src", "unit_type", payloadOfDepth(25));

        assertThatThrownBy(() -> service.ingest(deep, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting depth");
        verifyNoInteractions(producer);
    }

    @Test
    void batchWithOneTooDeepItemPublishesNothing() {
        DataEvent valid = event("ok-1", "unit-src", "unit_type", Map.of("bid", 1.0));
        DataEvent deep = event("deep-2", "unit-src", "unit_type", payloadOfDepth(25));

        assertThatThrownBy(() -> service.ingestBatch(List.of(valid, deep), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting depth");
        verifyNoInteractions(producer);
    }

    @Test
    void idempotencyKeyLongerThan128CharactersIsRejectedUpFront() {
        DataEvent valid = event("key-1", "unit-src", "unit_type", Map.of("bid", 1.0));

        assertThatThrownBy(() -> service.ingest(valid, "k".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
        assertThatThrownBy(() -> service.ingestBatch(List.of(valid), "k".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(producer, receipts);
    }

    @Test
    void blankIdempotencyKeyIsRejectedUpFront() {
        DataEvent valid = event("key-2", "unit-src", "unit_type", Map.of("bid", 1.0));

        assertThatThrownBy(() -> service.ingest(valid, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> service.ingestBatch(List.of(valid), ""))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(producer, receipts);
    }

    @Test
    void nullBatchElementIsRejectedBeforePublishing() {
        DataEvent valid = event("null-1", "unit-src", "unit_type", Map.of("bid", 1.0));

        assertThatThrownBy(() -> service.ingestBatch(Arrays.asList(valid, null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("events[1]");
        verifyNoInteractions(producer);
    }

    @Test
    void uniqueViolationsAreRecognisedAndOtherFailuresAreNot() {
        assertThat(IngestionService.isUniqueViolation(new DataIntegrityViolationException(
                "duplicate", new SQLException("duplicate key value", "23505")))).isTrue();
        assertThat(IngestionService.isUniqueViolation(new DuplicateKeyException("duplicate"))).isTrue();
        assertThat(IngestionService.isUniqueViolation(new DataIntegrityViolationException(
                "not null", new SQLException("null value", "23502")))).isFalse();
        assertThat(IngestionService.isUniqueViolation(new IllegalStateException("boom"))).isFalse();
    }

    @Test
    void theRawPersistenceFormOfAUniqueViolationIsRecognised() {
        // Shape observed in the real flush: a persistence exception wrapping SQLSTATE 23505.
        RuntimeException raw = new jakarta.persistence.PersistenceException(
                "could not execute statement", new SQLException("duplicate key value", "23505"));
        assertThat(IngestionService.isUniqueViolation(raw)).isTrue();
        assertThat(IngestionService.isUniqueViolation(new IllegalStateException("connection",
                new SQLException("connection refused", "08006")))).isFalse();
    }

    @Test
    void aNonUniqueFailureWhileReservingIsRethrownAtOnce() {
        when(receipts.findById(any())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("connection lost")).when(entityManager).persist(any());
        DataEvent event = event("reserve-1", "unit-src", "unit_type", Map.of("bid", 1.0));

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.reserve(event, "reserve-key", digests.of(event), UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("connection lost");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        verify(receipts, times(1)).findById(any());
        assertThat(elapsedMillis).as("no 200 ms retry loop for a non-race failure").isLessThan(2000);
    }

    @Test
    void aUniqueViolationWhileReservingReturnsTheWinnersIdentity() {
        DataEvent event = event("race-1", "unit-src", "unit_type", Map.of("bid", 1.0));
        String digest = digests.of(event);
        IngestionReceiptEntity winner = new IngestionReceiptEntity();
        winner.setIngestionId("winner-identity");
        winner.setRequestDigest(digest);
        winner.setPublishState(IngestionReceiptEntity.STATE_PENDING);
        when(receipts.findById(any())).thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DataIntegrityViolationException("duplicate",
                new SQLException("duplicate key value", "23505"))).when(entityManager).persist(any());

        IngestionService.Reservation reservation =
                service.reserve(event, "race-key", digest, UUID.randomUUID().toString());

        assertThat(reservation.ingestionId()).isEqualTo("winner-identity");
        assertThat(reservation.replayed()).isTrue();
    }

    @Test
    void aKeyedBatchThatConflictsWithAnExistingReceiptPublishesNothing() {
        DataEvent earlierA = event("a-1", "unit-src", "unit_x", Map.of("bid", 1.0));
        DataEvent newB = event("b-1", "unit-src", "unit_y", Map.of("bid", 2.0));
        DataEvent changedA = event("a-1", "unit-src", "unit_x", Map.of("bid", 9.0));
        String key = "batch-conflict-key";

        IngestionReceiptEntity existing = new IngestionReceiptEntity();
        existing.setRequestDigest(digests.sha256(List.of(digests.of(earlierA))));
        existing.setIngestionId("earlier-identity");
        existing.setPublishState(IngestionReceiptEntity.STATE_CONFIRMED);
        existing.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        when(receipts.findById(any())).thenReturn(Optional.empty());
        when(receipts.findById(new IngestionReceiptEntity.Key("unit-src", "unit_x", key)))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.ingestBatch(List.of(newB, changedA), key))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(producer);
        verify(receipts, never()).save(any());
    }
}
