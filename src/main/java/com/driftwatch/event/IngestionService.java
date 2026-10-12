package com.driftwatch.event;

import com.driftwatch.persistence.IngestionReceiptEntity;
import com.driftwatch.persistence.IngestionReceiptRepository;
import com.driftwatch.operations.DriftwatchMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
 *   <li>a keyed batch reserves every item's identity in one transaction before the first publish,
 *       so concurrent requests with one key share one identity per item, and a different batch
 *       under the same key is a 409 with nothing published; accepted/failed indexes are reported
 *       with the per-item ingestion ids</li>
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
    private static final Comparator<String> NULLS_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());

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
        Reservation reservation = reserve(event, idempotencyKey, digest, envelope.ingestionId().toString(),
                envelope.receivedAt());
        if (reservation.replayed()
                && IngestionReceiptEntity.STATE_CONFIRMED.equals(reservation.state())) {
            return new Acceptance(reservation.ingestionId(), event.eventId(), true, true);
        }
        // A replayed-but-unconfirmed receipt keeps its reserved identity and publishes again with
        // the first receipt time (guide 4.1: received_at does not change on retry), so the stream
        // recognises the same envelope as a redelivery.
        RawEnvelope reserved = new RawEnvelope(RawEnvelope.CONTRACT_VERSION,
                UUID.fromString(reservation.ingestionId()), event, reservation.receivedAt(),
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
        String batchId = UUID.randomUUID().toString();

        if (idempotencyKey == null) {
            List<Acceptance> accepted = new ArrayList<>();
            List<Integer> failed = new ArrayList<>();
            for (int index = 0; index < events.size(); index++) {
                DataEvent event = events.get(index);
                RawEnvelope envelope = envelope(event, null);
                if (!producer.publishAndAwait(envelope)) {
                    failed.add(index);
                    continue;
                }
                accepted.add(new Acceptance(envelope.ingestionId().toString(), event.eventId(), true, false));
            }
            return new BatchOutcome(batchId, accepted, failed, accepted.isEmpty());
        }

        // The key stands for the whole ordered batch (guide 4.2), so the digest compared for conflicts
        // is the batch digest. Every item's identity is reserved before the first publish.
        String batchDigest = digests.sha256(events.stream().map(digests::of).toList());
        Map<Integer, ReservedItem> reserved = reserveBatch(events, idempotencyKey, batchDigest, batchId);

        List<Acceptance> accepted = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            DataEvent event = events.get(index);
            ReservedItem item = reserved.get(index);
            if (IngestionReceiptEntity.STATE_CONFIRMED.equals(item.status())) {
                // A confirmed item is reported again and is not sent to the broker a second time.
                accepted.add(new Acceptance(item.ingestionId(), event.eventId(), true, true));
                continue;
            }
            // Guide 4.2: a retry re-sends only the items that are not confirmed, each with its reserved
            // identity and first receipt time, so the stream recognises it as a redelivery.
            RawEnvelope envelope = new RawEnvelope(RawEnvelope.CONTRACT_VERSION,
                    UUID.fromString(item.ingestionId()), event, item.receivedAt(),
                    RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, batchDigest);
            boolean confirmed = producer.publishAndAwait(envelope);
            recordBatchItem(event, idempotencyKey, batchId, item.ingestionId(), index, confirmed, batchDigest,
                    item.receivedAt());
            if (confirmed) {
                accepted.add(new Acceptance(item.ingestionId(), event.eventId(), true, item.replayed()));
            } else {
                failed.add(index);
            }
        }
        return new BatchOutcome(batchId, accepted, failed, accepted.isEmpty());
    }

    // ------------------------------------------------------------------ receipts

    /** {@code receivedAt} is the first receipt time of the reserved identity. */
    public record Reservation(String ingestionId, String state, boolean replayed, Instant receivedAt) {}

    /** One (source, event_type) receipt row of a keyed batch and the batch indexes it covers. */
    private record ReceiptGroup(IngestionReceiptEntity.Key key, DataEvent first, List<Integer> indexes) {}

    /**
     * The reserved state of one batch item. {@code replayed} is false only for the request that
     * created the reservation; every other request reports the item as a replay.
     */
    private record ReservedItem(String ingestionId, Instant receivedAt, String status, boolean replayed) {}

    /**
     * Reserves the identity in its own transaction. Two concurrent requests with one key race on
     * the primary key; the loser re-reads the winner's receipt and continues as a replay instead
     * of failing, so both callers see the same identity. Only a unique-key violation is treated
     * as that race; any other failure is rethrown at once.
     */
    public Reservation reserve(DataEvent event, String idempotencyKey, String digest, String newIngestionId) {
        return reserve(event, idempotencyKey, digest, newIngestionId, receivedNow());
    }

    public Reservation reserve(DataEvent event, String idempotencyKey, String digest, String newIngestionId,
                               Instant receivedAt) {
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
                // created_at doubles as the identity's first receipt time for retries.
                receipt.setCreatedAt(receivedAt);
                receipt.setExpiresAt(Instant.now().plus(IDEMPOTENCY_WINDOW));
                // persist, not save/merge: with an assigned id a merge would silently UPDATE a
                // concurrent winner's row and hand out two identities for one key.
                entityManager.persist(receipt);
                entityManager.flush();
                return new Reservation(newIngestionId, IngestionReceiptEntity.STATE_PENDING, false, receivedAt);
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
        return new Reservation(receipt.getIngestionId(), receipt.getPublishState(), true, receipt.getCreatedAt());
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
     * Reserves one identity per item of a keyed batch before anything is published (guide 4.1, 4.2).
     *
     * <p>Live receipts for the batch's keys are read first; a receipt with a different batch digest
     * is a 409 with nothing published. Every receipt row the batch still needs is then written in one
     * transaction, so a batch is either reserved as a whole or not at all. If another request reserved
     * one of the keys first, the rows it wrote are used when they are the same batch, and a different
     * batch is a 409. Rows that an earlier version left with missing items are completed under a row
     * lock.
     */
    private Map<Integer, ReservedItem> reserveBatch(List<DataEvent> events, String idempotencyKey,
                                                    String batchDigest, String batchId) {
        List<ReceiptGroup> groups = groupByReceiptKey(events, idempotencyKey);
        Map<IngestionReceiptEntity.Key, IngestionReceiptEntity> live = new LinkedHashMap<>();
        for (ReceiptGroup group : groups) {
            liveReceipt(group.first(), idempotencyKey).ifPresent(receipt -> live.put(group.key(), receipt));
        }
        requireSameBatch(live.values(), batchDigest);

        Set<IngestionReceiptEntity.Key> absent = new HashSet<>();
        Set<IngestionReceiptEntity.Key> partial = new HashSet<>();
        for (ReceiptGroup group : groups) {
            IngestionReceiptEntity receipt = live.get(group.key());
            if (receipt == null) {
                absent.add(group.key());
            } else if (!coversAll(receipt, group, events)) {
                partial.add(group.key());
            }
        }
        if (absent.isEmpty() && partial.isEmpty()) {
            return itemsOf(live, groups, events, Set.of());
        }

        Set<IngestionReceiptEntity.Key> created = new HashSet<>();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // Groups are taken in one fixed order, so two batches cannot deadlock on each other.
                for (ReceiptGroup group : groups) {
                    if (partial.contains(group.key())) {
                        IngestionReceiptEntity row = entityManager.find(IngestionReceiptEntity.class,
                                group.key(), LockModeType.PESSIMISTIC_WRITE);
                        if (row != null) {
                            requireSameBatch(List.of(row), batchDigest);
                            completeItems(row, group, events, batchId);
                            continue;
                        }
                    } else if (!absent.contains(group.key())) {
                        continue;
                    }
                    entityManager.persist(newBatchReceipt(group, events, idempotencyKey, batchDigest, batchId));
                    created.add(group.key());
                }
                entityManager.flush();
            });
        } catch (RuntimeException failure) {
            if (!isUniqueViolation(failure)) {
                throw failure;
            }
            // Another request reserved one of these keys first; continue as that reservation.
            return awaitBatchReservation(groups, events, batchDigest, failure);
        }
        return itemsOf(readRows(groups), groups, events, created);
    }

    /**
     * After a lost reservation race: waits briefly for the winner's rows to become visible, then
     * uses them if they are the same batch. A different batch is a 409 with nothing published.
     */
    private Map<Integer, ReservedItem> awaitBatchReservation(List<ReceiptGroup> groups, List<DataEvent> events,
                                                             String batchDigest, RuntimeException insertFailure) {
        for (int attempt = 0; attempt < 15; attempt++) {
            Map<IngestionReceiptEntity.Key, IngestionReceiptEntity> rows = readRows(groups);
            requireSameBatch(rows.values(), batchDigest);
            if (rows.size() == groups.size() && allCovered(rows, groups, events)) {
                return itemsOf(rows, groups, events, Set.of());
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

    private Map<IngestionReceiptEntity.Key, IngestionReceiptEntity> readRows(List<ReceiptGroup> groups) {
        Map<IngestionReceiptEntity.Key, IngestionReceiptEntity> rows = new LinkedHashMap<>();
        for (ReceiptGroup group : groups) {
            receiptRepository.findById(group.key()).ifPresent(receipt -> rows.put(group.key(), receipt));
        }
        return rows;
    }

    /** Every stored receipt must carry the batch digest; any other digest is a conflict. */
    private static void requireSameBatch(Collection<IngestionReceiptEntity> rows, String batchDigest) {
        for (IngestionReceiptEntity row : rows) {
            if (!batchDigest.equals(row.getRequestDigest())) {
                throw new ResponseStatusException(CONFLICT, CONFLICT_MESSAGE);
            }
        }
    }

    /** The reserved state of every item in the batch, keyed by batch index. */
    private Map<Integer, ReservedItem> itemsOf(Map<IngestionReceiptEntity.Key, IngestionReceiptEntity> rows,
                                               List<ReceiptGroup> groups, List<DataEvent> events,
                                               Set<IngestionReceiptEntity.Key> created) {
        Map<Integer, ReservedItem> items = new HashMap<>();
        for (ReceiptGroup group : groups) {
            IngestionReceiptEntity row = rows.get(group.key());
            for (int index : group.indexes()) {
                JsonNode entry = storedItem(row, index, events.get(index)).orElseThrow(
                        () -> new IllegalStateException("batch receipt has no reserved identity for item " + index));
                items.put(index, new ReservedItem(entry.path("ingestion_id").asText(),
                        Instant.parse(entry.path("received_at").asText()),
                        entry.path("status").asText(),
                        !created.contains(group.key())));
            }
        }
        return items;
    }

    private static boolean allCovered(Map<IngestionReceiptEntity.Key, IngestionReceiptEntity> rows,
                                      List<ReceiptGroup> groups, List<DataEvent> events) {
        for (ReceiptGroup group : groups) {
            IngestionReceiptEntity row = rows.get(group.key());
            if (row == null || !coversAll(row, group, events)) {
                return false;
            }
        }
        return true;
    }

    private static boolean coversAll(IngestionReceiptEntity row, ReceiptGroup group, List<DataEvent> events) {
        for (int index : group.indexes()) {
            if (storedItem(row, index, events.get(index)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<ReceiptGroup> groupByReceiptKey(List<DataEvent> events, String idempotencyKey) {
        Map<IngestionReceiptEntity.Key, List<Integer>> byKey = new LinkedHashMap<>();
        for (int index = 0; index < events.size(); index++) {
            byKey.computeIfAbsent(receiptKey(events.get(index), idempotencyKey), key -> new ArrayList<>())
                    .add(index);
        }
        List<ReceiptGroup> groups = new ArrayList<>();
        for (Map.Entry<IngestionReceiptEntity.Key, List<Integer>> entry : byKey.entrySet()) {
            groups.add(new ReceiptGroup(entry.getKey(), events.get(entry.getValue().get(0)), entry.getValue()));
        }
        groups.sort(Comparator.comparing((ReceiptGroup group) -> group.first().source(), NULLS_FIRST)
                .thenComparing(group -> group.first().eventType(), NULLS_FIRST));
        return groups;
    }

    /** The new receipt row for one (source, event_type) group, with a fresh identity per item. */
    private IngestionReceiptEntity newBatchReceipt(ReceiptGroup group, List<DataEvent> events,
                                                   String idempotencyKey, String batchDigest, String batchId) {
        Instant receivedAt = receivedNow();
        TreeMap<Integer, ObjectNode> entries = new TreeMap<>();
        for (int index : group.indexes()) {
            entries.put(index, itemEntry(index, events.get(index), UUID.randomUUID().toString(), receivedAt,
                    IngestionReceiptEntity.STATE_PENDING));
        }
        IngestionReceiptEntity receipt = new IngestionReceiptEntity();
        receipt.setSource(group.first().source());
        receipt.setEventType(group.first().eventType());
        receipt.setIdempotencyKey(idempotencyKey);
        receipt.setRequestDigest(batchDigest);
        receipt.setIngestionId(entries.firstEntry().getValue().path("ingestion_id").asText());
        receipt.setEventId(group.first().eventId());
        receipt.setPublishState(IngestionReceiptEntity.STATE_PENDING);
        receipt.setBatch(true);
        receipt.setBatchItems(batchPayload(batchId, entries));
        // created_at is the first receipt time of the row's identities, as for single receipts.
        receipt.setCreatedAt(receivedAt);
        receipt.setExpiresAt(Instant.now().plus(IDEMPOTENCY_WINDOW));
        return receipt;
    }

    /**
     * Gives an identity to every item of the group that a legacy partial write never reserved. Runs
     * under the row lock, so two requests cannot assign two identities to one item.
     */
    private void completeItems(IngestionReceiptEntity row, ReceiptGroup group, List<DataEvent> events,
                               String batchId) {
        TreeMap<Integer, ObjectNode> entries = itemEntries(row.getBatchItems());
        Instant receivedAt = receivedNow();
        for (int index : group.indexes()) {
            DataEvent event = events.get(index);
            if (storedItem(row, index, event).isEmpty()) {
                entries.put(index, itemEntry(index, event, UUID.randomUUID().toString(), receivedAt,
                        IngestionReceiptEntity.STATE_PENDING));
            }
        }
        row.setBatchItems(batchPayload(batchId, entries));
        row.setPublishState(summarise(entries));
    }

    /**
     * Records one item's state inside its (source, event_type, key) receipt. The item keeps the
     * identity and receipt time it was reserved with; only its status changes. The row is locked
     * for the read-modify-write so two items of one batch recorded at the same time cannot overwrite
     * each other.
     */
    private void recordBatchItem(DataEvent event, String idempotencyKey, String batchId, String identity,
                                 int index, boolean confirmed, String batchDigest, Instant receivedAt) {
        IngestionReceiptEntity.Key key = receiptKey(event, idempotencyKey);
        transactionTemplate.executeWithoutResult(status -> {
            IngestionReceiptEntity receipt = entityManager.find(IngestionReceiptEntity.class, key,
                    LockModeType.PESSIMISTIC_WRITE);
            boolean existed = receipt != null;
            if (!existed) {
                receipt = new IngestionReceiptEntity();
            }
            TreeMap<Integer, ObjectNode> entries = itemEntries(receipt.getBatchItems());
            entries.put(index, itemEntry(index, event, identity, receivedAt,
                    confirmed ? IngestionReceiptEntity.STATE_CONFIRMED : IngestionReceiptEntity.STATE_FAILED));

            receipt.setSource(event.source());
            receipt.setEventType(event.eventType());
            receipt.setIdempotencyKey(idempotencyKey);
            receipt.setRequestDigest(batchDigest);
            receipt.setIngestionId(identity);
            receipt.setEventId(event.eventId());
            // A row can hold several items, so its state summarises them: confirmed only when every
            // item is, failed as soon as one is. The per-item truth lives in batch_items.
            receipt.setPublishState(summarise(entries));
            receipt.setBatch(true);
            receipt.setBatchItems(batchPayload(batchId, entries));
            Instant now = Instant.now();
            receipt.setCreatedAt(receipt.getCreatedAt() == null ? now : receipt.getCreatedAt());
            receipt.setExpiresAt(receipt.getExpiresAt() == null ? now.plus(IDEMPOTENCY_WINDOW) : receipt.getExpiresAt());
            if (!existed) {
                entityManager.persist(receipt);
            }
        });
    }

    /** Stored item entries keyed by batch index, in index order. */
    private static TreeMap<Integer, ObjectNode> itemEntries(JsonNode batchItems) {
        TreeMap<Integer, ObjectNode> entries = new TreeMap<>();
        if (batchItems != null && batchItems.isObject() && batchItems.path("items").isArray()) {
            for (JsonNode entry : batchItems.path("items")) {
                if (entry.isObject()) {
                    entries.put(entry.path("index").asInt(), (ObjectNode) entry);
                }
            }
        }
        return entries;
    }

    private JsonNode batchPayload(String batchId, TreeMap<Integer, ObjectNode> entries) {
        ArrayNode items = objectMapper.createArrayNode();
        for (ObjectNode entry : entries.values()) {
            items.add(entry);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("batch_id", batchId);
        payload.set("items", items);
        return payload;
    }

    private ObjectNode itemEntry(int index, DataEvent event, String identity, Instant receivedAt, String status) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("index", index);
        item.put("event_id", event.eventId());
        item.put("ingestion_id", identity);
        item.put("received_at", receivedAt.toString());
        item.put("status", status);
        return item;
    }

    /** The row state implied by its items: CONFIRMED only when all are, FAILED once any is. */
    private static String summarise(Map<Integer, ObjectNode> entries) {
        boolean allConfirmed = true;
        boolean anyFailed = false;
        for (JsonNode entry : entries.values()) {
            String status = entry.path("status").asText();
            allConfirmed &= IngestionReceiptEntity.STATE_CONFIRMED.equals(status);
            anyFailed |= IngestionReceiptEntity.STATE_FAILED.equals(status);
        }
        return allConfirmed ? IngestionReceiptEntity.STATE_CONFIRMED
                : anyFailed ? IngestionReceiptEntity.STATE_FAILED
                : IngestionReceiptEntity.STATE_PENDING;
    }

    /**
     * The stored entry for one item, matched by index and event id, once it carries a reserved
     * identity and receipt time.
     */
    private static Optional<JsonNode> storedItem(IngestionReceiptEntity row, int index, DataEvent event) {
        if (row == null || row.getBatchItems() == null) {
            return Optional.empty();
        }
        for (JsonNode entry : row.getBatchItems().path("items")) {
            if (entry.path("index").asInt() == index
                    && Objects.equals(event.eventId(), entry.path("event_id").asText(null))
                    && !entry.path("ingestion_id").asText("").isBlank()
                    && !entry.path("received_at").asText("").isBlank()) {
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

    /**
     * Receipt times are kept at the database's microsecond precision, so an identity re-published
     * from its stored receipt carries exactly the received_at of its first envelope.
     */
    private static Instant receivedNow() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private RawEnvelope envelope(DataEvent event, String replayOf) {
        return new RawEnvelope(RawEnvelope.CONTRACT_VERSION, UUID.randomUUID(), event, receivedNow(),
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markState(DataEvent event, String idempotencyKey, String state) {
        receiptRepository.findById(receiptKey(event, idempotencyKey)).ifPresent(receipt -> {
            receipt.setPublishState(state);
            receiptRepository.save(receipt);
        });
    }
}
