package com.driftwatch.event;

import com.driftwatch.persistence.IngestionReceiptEntity;
import com.driftwatch.persistence.IngestionReceiptRepository;
import com.driftwatch.operations.DriftwatchMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Ingest contract for the REST API (execution guide, section 4.2).
 *
 * <ul>
 *   <li>one logical ingestion gets one {@code ingestion_id}; the caller sees 202 only after the
 *       broker acknowledged the record, otherwise 503 with the idempotency retry hint</li>
 *   <li>an Idempotency-Key reserves the identity in its own transaction before publishing, so a
 *       restart cannot allocate a second identity; the same key with different content is 409</li>
 *   <li>a receipt is valid for 24 hours; an expired receipt no longer binds its key</li>
 *   <li>batches validate every item and check every receipt for conflicts before anything is
 *       published, and report accepted/failed indexes with per-item ingestion ids</li>
 * </ul>
 */
@Service
public class IngestionService {

    /** Receipts expire with the documented 24 hour idempotency window. */
    public static final Duration IDEMPOTENCY_WINDOW = Duration.ofHours(24);
    public static final int BATCH_MAX_ITEMS = 100;
    /** Matches the ingestion_receipts key column. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    /** Guide 4.2: payload nesting depth 20; the payload object itself is depth 1. */
    public static final int MAX_PAYLOAD_DEPTH = 20;
    private static final int MAX_EVENT_BYTES = 256 * 1024;
    private static final long MAX_BATCH_BYTES = 4L * 1024 * 1024;
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final String CONFLICT_MESSAGE = "Idempotency-Key was already used for different content";

    private final RawEventProducer producer;
    private final IngestionReceiptRepository receiptRepository;
    private final RequestDigests digests;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DriftwatchMetrics metrics;

    @PersistenceContext
    private EntityManager entityManager;

    public IngestionService(RawEventProducer producer,
                            IngestionReceiptRepository receiptRepository,
                            RequestDigests digests,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager,
                            DriftwatchMetrics metrics) {
        this.producer = producer;
        this.receiptRepository = receiptRepository;
        this.digests = digests;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
    }

    /** Outcome of one accepted (or already known) ingestion. */
    public record Acceptance(String ingestionId, String eventId, boolean confirmed, boolean replayed) {}

    public record BatchOutcome(String batchId,
                               List<Acceptance> accepted,
                               List<Integer> failedIndexes,
                               boolean allFailed) {}

    public Acceptance ingest(DataEvent event, String idempotencyKey) {
        requireValidIdempotencyKey(idempotencyKey);
        validateEvent(event);
        RawEnvelope envelope = envelope(event, null);
        Instant startedAt = Instant.now();
        if (idempotencyKey == null) {
            boolean confirmed = producer.publishAndAwait(envelope);
            metrics.recordIngest(Duration.between(startedAt, Instant.now()), confirmed);
            if (!confirmed) {
                throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                        "broker acknowledgement timed out; retry with an Idempotency-Key to keep the same identity");
            }
            return new Acceptance(envelope.ingestionId().toString(), event.eventId(), true, false);
        }

        String digest = digests.of(event);
        Reservation reservation = reserve(event, idempotencyKey, digest, envelope.ingestionId().toString());
        if (reservation.replayed()
                && IngestionReceiptEntity.STATE_CONFIRMED.equals(reservation.state())) {
            return new Acceptance(reservation.ingestionId(), event.eventId(), true, true);
        }
        // A replayed-but-unconfirmed receipt keeps its reserved identity and publishes again.
        RawEnvelope reserved = new RawEnvelope(RawEnvelope.CONTRACT_VERSION,
                UUID.fromString(reservation.ingestionId()), event, envelope.receivedAt(),
                RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, null);
        boolean confirmed = producer.publishAndAwait(reserved);
        metrics.recordIngest(Duration.between(startedAt, Instant.now()), confirmed);
        markState(event, idempotencyKey, confirmed
                ? IngestionReceiptEntity.STATE_CONFIRMED
                : IngestionReceiptEntity.STATE_FAILED);
        if (!confirmed) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "broker acknowledgement timed out; retry with the same Idempotency-Key to keep identity "
                            + reservation.ingestionId());
        }
        return new Acceptance(reservation.ingestionId(), event.eventId(), true, reservation.replayed());
    }

    public BatchOutcome ingestBatch(List<DataEvent> events, String idempotencyKey) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("batch must contain at least one event");
        }
        if (events.size() > BATCH_MAX_ITEMS) {
            throw new IllegalArgumentException("batch may contain at most " + BATCH_MAX_ITEMS + " events");
        }
        requireValidIdempotencyKey(idempotencyKey);
        // Every item is validated before anything is published (guide 4.2).
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) == null) {
                throw new IllegalArgumentException("events[" + index + "] must not be null");
            }
            validateEvent(events.get(index));
        }
        rejectOversizedBatch(events);

        String batchDigest = idempotencyKey == null
                ? null
                : digests.sha256(events.stream().map(digests::of).toList());
        if (batchDigest != null) {
            // The whole batch is checked against every receipt it touches before the first publish,
            // so a conflict cannot leave a partially published batch behind.
            rejectBatchKeyConflicts(events, idempotencyKey, batchDigest);
        }
        String batchId = UUID.randomUUID().toString();

        List<Acceptance> accepted = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            DataEvent event = events.get(index);
            RawEnvelope envelope = envelope(event, batchDigest);
            if (batchDigest == null) {
                if (!producer.publishAndAwait(envelope)) {
                    failed.add(index);
                    continue;
                }
                accepted.add(new Acceptance(envelope.ingestionId().toString(), event.eventId(), true, false));
                continue;
            }

            // The receipt key is (source, event_type, key) and one batch routinely carries many
            // events of the same type, so a row represents every item of that type in the batch and
            // holds their individual states in batch_items (guide 4.3). The digest compared for
            // conflicts is the batch digest, because the key stands for the whole ordered batch
            // (guide 4.2).
            Optional<IngestionReceiptEntity> existing = liveReceipt(event, idempotencyKey);
            if (existing.isPresent()) {
                IngestionReceiptEntity receipt = existing.get();
                if (!batchDigest.equals(receipt.getRequestDigest())) {
                    // Only reachable if a concurrent request wrote this key after the pre-check.
                    throw new ResponseStatusException(CONFLICT, CONFLICT_MESSAGE);
                }
                Optional<JsonNode> item = batchItem(receipt, index, event.eventId());
                String identity = item.map(entry -> entry.path("ingestion_id").asText())
                        .filter(value -> !value.isBlank())
                        .orElseGet(() -> envelope.ingestionId().toString());
                if (item.isPresent() && IngestionReceiptEntity.STATE_CONFIRMED
                        .equals(item.get().path("status").asText())) {
                    accepted.add(new Acceptance(identity, event.eventId(), true, true));
                    continue;
                }
                // Guide 4.2: a retried batch re-sends only the items that are not confirmed, each
                // with its reserved identity, so a partially accepted batch can complete.
                RawEnvelope reservedItem = new RawEnvelope(RawEnvelope.CONTRACT_VERSION,
                        UUID.fromString(identity), event, envelope.receivedAt(),
                        RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, null);
                boolean confirmed = producer.publishAndAwait(reservedItem);
                recordBatchItem(event, idempotencyKey, batchId, identity, index, confirmed, batchDigest);
                if (confirmed) {
                    accepted.add(new Acceptance(identity, event.eventId(), true, true));
                } else {
                    failed.add(index);
                }
                continue;
            }

            String identity = envelope.ingestionId().toString();
            boolean confirmed = producer.publishAndAwait(envelope);
            recordBatchItem(event, idempotencyKey, batchId, identity, index, confirmed, batchDigest);
            if (confirmed) {
                accepted.add(new Acceptance(identity, event.eventId(), true, false));
            } else {
                failed.add(index);
            }
        }
        return new BatchOutcome(batchId, accepted, failed, accepted.isEmpty());
    }

    // ------------------------------------------------------------------ receipts

    public record Reservation(String ingestionId, String state, boolean replayed) {}

    /**
     * Reserves the identity in its own transaction. Two concurrent requests with one key race on
     * the primary key; the loser re-reads the winner's receipt and continues as a replay instead
     * of failing, so both callers see the same identity. Only a unique-key violation is treated
     * as that race; any other failure is rethrown at once.
     */
    public Reservation reserve(DataEvent event, String idempotencyKey, String digest, String newIngestionId) {
        IngestionReceiptEntity.Key key = receiptKey(event, idempotencyKey);
        Optional<IngestionReceiptEntity> existing = liveReceipt(event, idempotencyKey);
        if (existing.isPresent()) {
            return replayOf(existing.get(), digest);
        }
        try {
            return transactionTemplate.execute(status -> {
                IngestionReceiptEntity receipt = new IngestionReceiptEntity();
                receipt.setSource(event.source());
                receipt.setEventType(event.eventType());
                receipt.setIdempotencyKey(idempotencyKey);
                receipt.setRequestDigest(digest);
                receipt.setIngestionId(newIngestionId);
                receipt.setEventId(event.eventId());
                receipt.setPublishState(IngestionReceiptEntity.STATE_PENDING);
                receipt.setCreatedAt(Instant.now());
                receipt.setExpiresAt(Instant.now().plus(IDEMPOTENCY_WINDOW));
                // persist, not save/merge: with an assigned id a merge would silently UPDATE a
                // concurrent winner's row and hand out two identities for one key.
                entityManager.persist(receipt);
                entityManager.flush();
                return new Reservation(newIngestionId, IngestionReceiptEntity.STATE_PENDING, false);
            });
        } catch (RuntimeException insertFailure) {
            if (!isUniqueViolation(insertFailure)) {
                throw insertFailure;
            }
            // Another request won the primary key. Wait briefly for its row to become visible and
            // continue as that identity.
            for (int attempt = 0; attempt < 15; attempt++) {
                Optional<IngestionReceiptEntity> winner = receiptRepository.findById(key);
                if (winner.isPresent()) {
                    return replayOf(winner.get(), digest);
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw insertFailure;
        }
    }

    /**
     * True only for a unique-constraint violation: SQLSTATE 23505 anywhere in the cause chain, or a
     * DuplicateKeyException. The shared EntityManager's flush does not always translate the failure
     * into a DataIntegrityViolationException (it can surface as Hibernate's ConstraintViolationException),
     * so the SQLSTATE is the reliable signal.
     */
    static boolean isUniqueViolation(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException) {
                return true;
            }
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private Reservation replayOf(IngestionReceiptEntity receipt, String digest) {
        if (!receipt.getRequestDigest().equals(digest)) {
            throw new ResponseStatusException(CONFLICT, CONFLICT_MESSAGE);
        }
        return new Reservation(receipt.getIngestionId(), receipt.getPublishState(), true);
    }

    /**
     * The receipt for this key while it is inside its 24 hour window. An expired receipt no longer
     * binds the key: it is deleted (conditionally, so a concurrent fresh receipt is never removed)
     * and the caller proceeds as a first request with a new identity.
     */
    private Optional<IngestionReceiptEntity> liveReceipt(DataEvent event, String idempotencyKey) {
        Optional<IngestionReceiptEntity> found = receiptRepository.findById(receiptKey(event, idempotencyKey));
        if (found.isEmpty()) {
            return found;
        }
        Instant now = Instant.now();
        if (!found.get().getExpiresAt().isBefore(now)) {
            return found;
        }
        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery(
                        "DELETE FROM ingestion_receipts WHERE source = :source AND event_type = :eventType "
                                + "AND idempotency_key = :idempotencyKey AND expires_at < :now")
                .setParameter("source", event.source())
                .setParameter("eventType", event.eventType())
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate());
        return Optional.empty();
    }

    /**
     * Read-only pre-check for a keyed batch: every distinct (source, event_type, key) receipt the
     * batch touches must either be absent, expired, or carry the same batch digest. Runs before the
     * first publish so a conflict returns 409 with nothing published.
     */
    private void rejectBatchKeyConflicts(List<DataEvent> events, String idempotencyKey, String batchDigest) {
        Set<IngestionReceiptEntity.Key> keys = new LinkedHashSet<>();
        for (DataEvent event : events) {
            keys.add(receiptKey(event, idempotencyKey));
        }
        Instant now = Instant.now();
        for (IngestionReceiptEntity.Key key : keys) {
            Optional<IngestionReceiptEntity> existing = receiptRepository.findById(key);
            if (existing.isPresent()
                    && !existing.get().getExpiresAt().isBefore(now)
                    && !batchDigest.equals(existing.get().getRequestDigest())) {
                throw new ResponseStatusException(CONFLICT, CONFLICT_MESSAGE);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markState(DataEvent event, String idempotencyKey, String state) {
        receiptRepository.findById(receiptKey(event, idempotencyKey)).ifPresent(receipt -> {
            receipt.setPublishState(state);
            receiptRepository.save(receipt);
        });
    }

    /**
     * Records one item's state inside its (source, event_type, key) receipt. The row is created on
     * first use and the item entry is replaced on every retry, so what is stored always matches
     * what the broker last confirmed. The expiry is fixed when the receipt is first written.
     */
    private void recordBatchItem(DataEvent event, String idempotencyKey, String batchId, String identity,
                                 int index, boolean confirmed, String batchDigest) {
        IngestionReceiptEntity.Key key = receiptKey(event, idempotencyKey);
        IngestionReceiptEntity receipt = receiptRepository.findById(key).orElseGet(IngestionReceiptEntity::new);
        ArrayNode items = objectMapper.createArrayNode();
        JsonNode previous = receipt.getBatchItems();
        if (previous != null && previous.isObject() && previous.path("items").isArray()) {
            for (JsonNode entry : previous.path("items")) {
                if (entry.path("index").asInt() != index) {
                    items.add(entry);
                }
            }
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("index", index);
        item.put("event_id", event.eventId());
        item.put("ingestion_id", identity);
        item.put("status", confirmed ? IngestionReceiptEntity.STATE_CONFIRMED
                : IngestionReceiptEntity.STATE_FAILED);
        items.add(item);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("batch_id", batchId);
        payload.set("items", items);

        receipt.setSource(event.source());
        receipt.setEventType(event.eventType());
        receipt.setIdempotencyKey(idempotencyKey);
        receipt.setRequestDigest(batchDigest);
        receipt.setIngestionId(identity);
        receipt.setEventId(event.eventId());
        // A row can hold several items, so its state summarises them: confirmed only when every
        // item is, failed as soon as one is. The per-item truth lives in batch_items.
        boolean allConfirmed = true;
        boolean anyFailed = false;
        for (JsonNode entry : items) {
            String status = entry.path("status").asText();
            allConfirmed &= IngestionReceiptEntity.STATE_CONFIRMED.equals(status);
            anyFailed |= IngestionReceiptEntity.STATE_FAILED.equals(status);
        }
        receipt.setPublishState(allConfirmed ? IngestionReceiptEntity.STATE_CONFIRMED
                : anyFailed ? IngestionReceiptEntity.STATE_FAILED
                : IngestionReceiptEntity.STATE_PENDING);
        receipt.setBatch(true);
        receipt.setBatchItems(payload);
        Instant now = Instant.now();
        receipt.setCreatedAt(receipt.getCreatedAt() == null ? now : receipt.getCreatedAt());
        receipt.setExpiresAt(receipt.getExpiresAt() == null ? now.plus(IDEMPOTENCY_WINDOW) : receipt.getExpiresAt());
        receiptRepository.save(receipt);
    }

    /** The stored state of one batch item, matched by index and event id. */
    private Optional<JsonNode> batchItem(IngestionReceiptEntity receipt, int index, String eventId) {
        JsonNode items = receipt.getBatchItems();
        if (items == null || !items.isObject() || !items.path("items").isArray()) {
            return Optional.empty();
        }
        for (JsonNode entry : items.path("items")) {
            if (entry.path("index").asInt() == index && eventId.equals(entry.path("event_id").asText())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private IngestionReceiptEntity.Key receiptKey(DataEvent event, String idempotencyKey) {
        return new IngestionReceiptEntity.Key(event.source(), event.eventType(), idempotencyKey);
    }

    // ------------------------------------------------------------------ helpers

    /** A supplied Idempotency-Key must be non-blank and fit the 128 character receipt column. */
    private static void requireValidIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        if (idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }

    /** Everything that must hold before an item may be published. */
    private void validateEvent(DataEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        rejectOversized(event);
        if (exceedsPayloadDepth(event.payload())) {
            throw new IllegalArgumentException(
                    "payload nesting depth must not exceed " + MAX_PAYLOAD_DEPTH);
        }
    }

    /**
     * The payload object is depth 1 and every nested object or array adds one level. Scalars do
     * not add depth. Descent stops at the first container beyond the limit, so the work is bounded.
     */
    static boolean exceedsPayloadDepth(Map<String, Object> payload) {
        return payload != null && exceedsDepth(payload, 1);
    }

    private static boolean exceedsDepth(Object value, int level) {
        Collection<?> children;
        if (value instanceof Map<?, ?> map) {
            children = map.values();
        } else if (value instanceof Collection<?> list) {
            children = list;
        } else {
            return false;
        }
        if (level > MAX_PAYLOAD_DEPTH) {
            return true;
        }
        for (Object child : children) {
            if (exceedsDepth(child, level + 1)) {
                return true;
            }
        }
        return false;
    }

    private RawEnvelope envelope(DataEvent event, String replayOf) {
        return new RawEnvelope(RawEnvelope.CONTRACT_VERSION, UUID.randomUUID(), event, Instant.now(),
                RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, replayOf);
    }

    /** The batch is bounded by item count and by the total request size (guide 4.2). */
    private void rejectOversizedBatch(List<DataEvent> events) {
        long total = 0;
        for (DataEvent event : events) {
            try {
                total += objectMapper.writeValueAsBytes(event).length;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("event could not be serialised", e);
            }
        }
        if (total > MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("batch exceeds the 4 MiB total limit");
        }
    }

    private void rejectOversized(DataEvent event) {
        try {
            int size = objectMapper.writeValueAsBytes(event).length;
            if (size > MAX_EVENT_BYTES) {
                throw new IllegalArgumentException("event exceeds the 256 KiB limit");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("event could not be serialised", e);
        }
    }
}
